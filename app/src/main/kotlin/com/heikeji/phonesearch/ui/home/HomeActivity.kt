package com.heikeji.phonesearch.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
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
import com.heikeji.phonesearch.ui.common.SearchEntry
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.dp
import com.heikeji.phonesearch.ui.common.showMessage
import com.heikeji.phonesearch.ui.login.LoginActivity
import com.heikeji.phonesearch.ui.result.ResultActivity
import com.heikeji.phonesearch.update.UpdateActivity
import com.heikeji.phonesearch.update.UpdateClient
import com.heikeji.phonesearch.update.UpdateInfo
import com.heikeji.phonesearch.update.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

        // 单击：应用内相机；长按：直接调系统相机
        binding.takePhotoButton.setOnClickListener {
            startActivity(CameraActivity.newIntent(this, currentMode()))
        }
        binding.takePhotoButton.setOnLongClickListener {
            launchSystemCamera()
            true
        }
    }

    private fun currentMode(): SearchMode =
        if (binding.modeGroup.checkedButtonId == R.id.modePage) SearchMode.PAGE else SearchMode.SINGLE

    // ------------------------------------------------------------------ 系统相机

    private var pendingCaptureFile: File? = null

    private val systemCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val file = pendingCaptureFile
        pendingCaptureFile = null

        if (result.resultCode != Activity.RESULT_OK || file == null) {
            file?.delete()
            return@registerForActivityResult
        }
        if (!file.isFile || file.length() == 0L) {
            file.delete()
            binding.root.showMessage(getString(R.string.system_camera_failed))
            return@registerForActivityResult
        }
        startActivity(
            SearchEntry.intentFor(this, file.absolutePath, currentMode(), extraRotation = 0),
        )
    }

    /**
     * 长按拍照搜题 -> 系统相机。
     *
     * 自己指定输出文件并用 FileProvider 授权，这样拿到的是**全分辨率**照片，
     * 而不是 `EXTRA_OUTPUT` 缺失时系统回传的缩略图。
     *
     * 系统相机自己会按 EXIF 记录方向，所以这里不做额外旋转（`extraRotation = 0`）。
     */
    private fun launchSystemCamera() {
        val dir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "system-${UUID.randomUUID()}.jpg")

        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            binding.root.showMessage(getString(R.string.system_camera_failed))
            return
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            binding.root.showMessage(getString(R.string.system_camera_unavailable))
            return
        }

        pendingCaptureFile = file
        try {
            systemCameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pendingCaptureFile = null
            file.delete()
            binding.root.showMessage(getString(R.string.system_camera_unavailable))
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
        // 每次进入首页静默查一次；有更新就把底部版本号变成可点的提示
        if (!updateChecked) {
            updateChecked = true
            checkUpdate(silent = true)
        }
    }

    // ------------------------------------------------------------------ 应用内更新

    private var updateChecked = false
    private var availableUpdate: UpdateInfo? = null

    /**
     * 从 Gitee 的 release 接口查最新版本。
     *
     * @param silent 自动检查时不弹「已是最新」的提示，避免每次进首页都打扰
     */
    private fun checkUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val current = currentVersionName()
            val latest = withContext(Dispatchers.IO) {
                runCatching { UpdateClient.fetchLatest() }.getOrNull()
            }
            val newer = latest?.takeIf { it.hasApk && Version.isNewer(it.versionName, current) }

            if (newer != null) {
                availableUpdate = newer
                binding.versionLabel.text = getString(R.string.update_badge, newer.versionName)
                binding.versionLabel.setTextColor(
                    ContextCompat.getColor(this@HomeActivity, R.color.terracotta),
                )
                binding.versionLabel.setOnClickListener { openUpdate(newer) }
            } else if (!silent) {
                binding.root.showMessage(
                    getString(R.string.update_up_to_date, current),
                )
            }
        }
    }

    private fun openUpdate(info: UpdateInfo) {
        startActivity(UpdateActivity.newIntent(this, info))
    }

    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    /**
     * 问候语只按时段来，不拼账号信息——服务端返回的 uname 就是手机号，不该出现在界面上。
     */
    private fun bindGreeting() {
        val greeting = Greetings.forNow()
        binding.dateLabel.text = todayLabel()
        binding.greeting.text = greeting.title
        binding.tagline.text = greeting.subtitle
        binding.versionLabel.text = appVersionLabel()
        // 点版本号 = 手动检查更新
        binding.versionLabel.setOnClickListener { checkUpdate(silent = false) }
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
