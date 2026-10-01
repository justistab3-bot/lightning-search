package com.heikeji.phonesearch.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.data.relativeTime
import com.heikeji.phonesearch.data.todayLabel
import com.heikeji.phonesearch.databinding.ActivityHomeBinding
import com.heikeji.phonesearch.databinding.ItemHistoryBinding
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.ui.camera.CameraActivity
import com.heikeji.phonesearch.ui.common.Greetings
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.dp
import com.heikeji.phonesearch.ui.login.LoginActivity
import com.heikeji.phonesearch.ui.result.ResultActivity
import java.io.File

/**
 * 首页：时段问候 + 主操作 + 搜题统计 + 最近搜题。
 *
 * 历史与统计在 onResume 刷新，这样从结果页返回时列表是新的。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val container by lazy { appContainer }
    private var entrancePlayed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.header.applySystemBarPadding(top = true, bottom = false, horizontal = true)
        binding.root.applySystemBarPadding(top = false, bottom = true, horizontal = true)

        if (container.sessions.current() == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        bindGreeting()
        setupModeToggle()
        binding.logoutButton.setOnClickListener { confirmLogout() }
    }

    /**
     * 搜题模式选择：单题 / 整页。
     *
     * 整页模式会跳过裁剪页（需要保留整页分辨率给服务端定位题框），拍完直接进整页结果页。
     */
    private fun setupModeToggle() {
        binding.modeGroup.check(R.id.modeSingle)
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            binding.modeHint.setText(
                if (checkedId == R.id.modePage) R.string.mode_hint_page
                else R.string.mode_hint_single,
            )
        }
        binding.modeHint.setText(R.string.mode_hint_single)
        binding.takePhotoButton.setOnClickListener {
            val mode = if (binding.modeGroup.checkedButtonId == R.id.modePage) {
                SearchMode.PAGE
            } else {
                SearchMode.SINGLE
            }
            startActivity(CameraActivity.newIntent(this, mode))
        }
    }

    override fun onResume() {
        super.onResume()
        bindStats()
        bindHistory()
        if (!entrancePlayed) {
            entrancePlayed = true
            playEntrance()
        }
    }

    /**
     * 问候语只按时段来，不拼账号信息——服务端返回的 uname 就是手机号，不该出现在界面上。
     */
    private fun bindGreeting() {
        val greeting = Greetings.forNow()
        binding.dateLabel.text = todayLabel()
        binding.greeting.text = greeting.title
        binding.tagline.text = greeting.subtitle
        binding.versionLabel.text = appVersionLabel()
    }

    /** 首页底部显示版本号，方便确认装的是哪一轮构建。 */
    private fun appVersionLabel(): String {
        val name = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        return if (name.isBlank()) "" else "v$name"
    }

    private fun bindStats() {
        binding.todayCount.text = container.history.todayCount().toString()
        binding.totalCount.text = container.history.totalCount().toString()
    }

    private fun bindHistory() {
        val entries = container.history.list(HISTORY_PREVIEW)
        binding.historyList.removeAllViews()
        binding.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        entries.forEachIndexed { index, entry ->
            val row = ItemHistoryBinding.inflate(layoutInflater, binding.historyList, false)
            row.historyBadge.subject = entry.subject
            row.historySubject.text = entry.subject.ifEmpty { getString(R.string.result_no_subject) }
            row.historyTime.text = relativeTime(entry.timestamp)
            row.historyThumb.setImageBitmap(decodeThumbnail(entry.questionFile))
            row.historyCard.setOnClickListener {
                startActivity(ResultActivity.historyIntent(this, entry.id))
            }
            binding.historyList.addView(row.root)

            row.root.alpha = 0f
            row.root.translationY = dp(12).toFloat()
            row.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(70L * (index + 4))
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** 依次淡入上移，让首屏有呼吸感。 */
    private fun playEntrance() {
        val simple = listOf(binding.header, binding.statsRow, binding.historySection, binding.logoutButton)
        simple.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = dp(18).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(70L * index)
                .setDuration(380L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        binding.heroCard.alpha = 0f
        binding.heroCard.translationY = dp(18).toFloat()
        binding.heroCard.scaleX = 0.97f
        binding.heroCard.scaleY = 0.97f
        binding.heroCard.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(70L)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun decodeThumbnail(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 240) sample *= 2
        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Exception) {
        null
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> logout() }
            .show()
    }

    private fun logout() {
        container.sessions.clear()
        container.protocol.reset()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    private companion object {
        const val HISTORY_PREVIEW = 6
    }
}
