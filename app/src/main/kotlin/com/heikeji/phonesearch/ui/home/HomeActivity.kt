package com.heikeji.phonesearch.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.analytics.Analytics
import com.heikeji.phonesearch.data.StorageCleaner
import com.heikeji.phonesearch.data.StorageUsage
import com.heikeji.phonesearch.data.formatBytes
import com.heikeji.phonesearch.data.relativeTime
import com.heikeji.phonesearch.data.UserPrefs
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingRequest
import com.heikeji.phonesearch.ui.onboarding.OnboardingActivity
import com.heikeji.phonesearch.databinding.ActivityHomeBinding
import com.heikeji.phonesearch.databinding.ItemHistoryBinding
import com.heikeji.phonesearch.databinding.DialogRecentSearchesBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.heikeji.phonesearch.protocol.search.model.SearchMode
import com.heikeji.phonesearch.ui.camera.CameraActivity
import com.heikeji.phonesearch.ui.common.Greetings
import com.heikeji.phonesearch.ui.common.SearchEntry
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.dp
import com.heikeji.phonesearch.ui.common.showMessage
import com.heikeji.phonesearch.ui.chat.ChatActivity
import com.heikeji.phonesearch.ui.essay.EssayActivity
import com.heikeji.phonesearch.ui.privacy.PrivacyActivity
import com.heikeji.phonesearch.ui.login.LoginActivity
import com.heikeji.phonesearch.ui.result.ResultActivity
import com.heikeji.phonesearch.update.UpdateActivity
import com.heikeji.phonesearch.update.UpdateClient
import com.heikeji.phonesearch.update.UpdateInfo
import com.heikeji.phonesearch.update.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 首页集中展示拍照搜题，历史与统计仅在打开最近搜题面板时加载。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val container by lazy { appContainer }
    private var historyJob: Job? = null
    private var historyDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applySystemBarPadding(horizontal = true)
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        pendingCaptureFile = savedInstanceState?.getString(STATE_CAPTURE_PATH)?.let(::File)
        pendingCaptureMode = if (savedInstanceState?.getBoolean(STATE_CAPTURE_PAGE) == true) {
            SearchMode.PAGE
        } else {
            SearchMode.SINGLE
        }

        // 隐私政策同意：没同意之前不做任何事，也不初始化统计 SDK
        if (!Analytics.hasConsent(this)) {
            showConsentDialog()
            return
        }

        // 已有账号的升级用户直接进入首页；首次使用者选择年级即可开始。
        if (!UserPrefs.onboarded(this) && container.sessions.current() == null) {
            startActivity(OnboardingActivity.newIntent(this))
            finish()
            return
        }

        bindGreeting()
        setupModeToggle()
        binding.essayButton.setOnClickListener {
            startActivity(Intent(this, EssayActivity::class.java))
        }
        binding.chatButton.setOnClickListener {
            startActivity(ChatActivity.newIntent(this))
        }
        binding.historyToggle.setOnClickListener { showRecentSearches() }
    }

    /**
     * 首次启动的隐私政策同意。
     *
     * 合规要求：**用户点「同意」之前不能初始化统计 SDK**（[Analytics.setConsent]
     * 里做这件事）。不同意就退出应用 —— 不给「先用了再说」的模糊空间。
     */
    private fun showConsentDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.privacy_consent_title)
            .setMessage(R.string.privacy_consent_message)
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_agree) { _, _ ->
                Analytics.setConsent(this, granted = true)
                recreate()
            }
            .setNegativeButton(R.string.privacy_disagree) { _, _ ->
                Analytics.setConsent(this, granted = false)
                binding.root.showMessage(getString(R.string.privacy_disagree_toast))
                finishAffinity()
            }
            .setNeutralButton(R.string.privacy_action_open) { _, _ ->
                // 点「查看」时先不落决定，回来还会再问一次
                startActivity(PrivacyActivity.newIntent(this))
            }
            .show()
    }

    /**
     * 搜题模式选择：单题 / 整页。
     *
     * 整页模式会跳过裁剪页（需要保留整页分辨率给服务端定位题框），拍完直接进整页结果页。
     */
    private fun setupModeToggle() {
        binding.modeGroup.check(getSharedPreferences(PREFS_HOME, MODE_PRIVATE)
            .getInt("searchMode", R.id.modeSingle))
        binding.modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            getSharedPreferences(PREFS_HOME, MODE_PRIVATE).edit()
                .putInt("searchMode", checkedId).apply()
            binding.modeHint.setText(
                if (checkedId == R.id.modePage) R.string.mode_hint_page
                else R.string.mode_hint_single,
            )
        }
        binding.modeHint.setText(
            if (currentMode() == SearchMode.PAGE) R.string.mode_hint_page else R.string.mode_hint_single,
        )

        // 单击：系统相机（默认，对焦/闪光灯体验更好）；长按：应用内相机（带旋转校正）
        binding.takePhotoButton.setOnClickListener { launchSystemCamera() }
        binding.takePhotoButton.setOnLongClickListener {
            startActivity(CameraActivity.newIntent(this, currentMode()))
            true
        }
    }

    private fun currentMode(): SearchMode =
        if (binding.modeGroup.checkedButtonId == R.id.modePage) SearchMode.PAGE else SearchMode.SINGLE

    // ------------------------------------------------------------------ 系统相机

    private var pendingCaptureFile: File? = null
    private var pendingCaptureMode = SearchMode.SINGLE

    override fun onSaveInstanceState(outState: Bundle) {
        pendingCaptureFile?.let { outState.putString(STATE_CAPTURE_PATH, it.absolutePath) }
        outState.putBoolean(STATE_CAPTURE_PAGE, pendingCaptureMode == SearchMode.PAGE)
        super.onSaveInstanceState(outState)
    }

    /**
     * 相机权限回调：同意后立即拉起系统相机。
     *
     * 背景（U-APM 崩溃日志定位）：重装后运行时权限会被重置，此时若直接启动
     * ACTION_IMAGE_CAPTURE，系统会抛
     * `SecurityException: ... with revoked permission android.permission.CAMERA`。
     * 所以拉起系统相机之前必须先检查/申请权限，和内置相机（CameraActivity）一致。
     */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchSystemCameraNow()
    }

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
            SearchEntry.intentFor(this, file.absolutePath, pendingCaptureMode, extraRotation = 0),
        )
    }

    /**
     * 单击拍照搜题 -> 系统相机（默认入口）。
     *
     * 自己指定输出文件并用 FileProvider 授权，这样拿到的是**全分辨率**照片，
     * 而不是 `EXTRA_OUTPUT` 缺失时系统回传的缩略图。
     *
     * 系统相机自己会按 EXIF 记录方向，所以这里不做额外旋转（`extraRotation = 0`）。
     */
    private fun launchSystemCamera() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchSystemCameraNow()
    }

    /** 权限已就绪，直接拉起系统相机。 */
    private fun launchSystemCameraNow() {
        try {
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
                clipData = ClipData.newRawUri("capture", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // resolveActivity 走 PackageManager 的 Binder，系统服务异常时会抛
            // RemoteException（线上 1.31.0–1.33.2 有上报），这里整体兜住。
            val resolved = runCatching { intent.resolveActivity(packageManager) }.getOrNull()
            if (resolved == null) {
                binding.root.showMessage(getString(R.string.system_camera_unavailable))
                return
            }

            pendingCaptureFile = file
            pendingCaptureMode = currentMode()
            try {
                systemCameraLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                pendingCaptureFile = null
                file.delete()
                binding.root.showMessage(getString(R.string.system_camera_unavailable))
            }
        } catch (t: Throwable) {
            // 兜底：任何系统相机拉起失败都不应该让应用崩掉。
            pendingCaptureFile = null
            binding.root.showMessage(getString(R.string.system_camera_failed))
        }
    }

    override fun onResume() {
        super.onResume()
        bindGreeting()
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
                bindVersion()
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

    /** 按时段问候与鼓励，不显示账号信息。 */
    private fun bindGreeting() {
        val greeting = Greetings.forNow()
        binding.greeting.text = greeting.title
        binding.tagline.text = greeting.subtitle
        bindVersion()
        binding.versionLabel.setOnLongClickListener {
            showSettingsDialog()
            true
        }
    }

    private fun bindVersion() {
        val update = availableUpdate
        binding.versionLabel.text = update?.let { getString(R.string.update_badge, it.versionName) }
            ?: appVersionLabel()
        binding.versionLabel.setTextColor(ContextCompat.getColor(this,
            if (update != null) R.color.brand else R.color.ink_tertiary))
        binding.versionLabel.setOnClickListener {
            if (update != null) openUpdate(update) else checkUpdate(silent = false)
        }
    }

    /** 首页底部显示版本号，方便确认装的是哪一轮构建。 */
    // ------------------------------------------------------------------ 存储占用

    /** 协议诊断：设备身份与请求日志。 */
    private fun showProtocolStatus() {
        val identity = container.identity
        val sb = StringBuilder()
        sb.append("账号：").append(if (container.sessions.current() == null) "未登录" else "已登录").append('\n')
        sb.append("did：").append(identity.did.ifEmpty { "（空）" }).append('\n')
        sb.append("digGrade：").append(identity.digGrade).append('\n')
        sb.append("cuid：").append(identity.cuid.take(8)).append("…")
        sb.append("\n\n—— 日志尾部 ——\n")
        sb.append(readProtocolLogTail())
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_protocol_status)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.storage_ok, null)
            .show()
    }

    /** 读取应用私有日志的最后 30 行。 */
    private fun readProtocolLogTail(): String = try {
        val file = java.io.File(getExternalFilesDir(null), "protocol-log.txt")
        if (!file.isFile) "（还没有日志）"
        else file.readLines().takeLast(30).joinToString("\n")
    } catch (e: Exception) {
        "（日志读取失败：${e.message}）"
    }

    /**
     * 长按版本号打开设置。
     *
     * 三件事：统计开关（合规要求必须能关）、存储占用明细、隐私政策入口。
     * 应用体积是「用着用着变大」的：安装包缓存、相机原图、WebView 缓存的答案图
     * 都会积累，所以这里也把明细摊开给用户看。
     */
    private fun showSettingsDialog() {
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) { StorageCleaner.usage(this@HomeActivity) }
            val analyticsOn = Analytics.isEnabled(this@HomeActivity)
            val total = usage.format()
            val session = container.sessions.current()
            val gradeLabel = UserPrefs.gradeLabel(
                UserPrefs.effectiveGrade(this@HomeActivity, session?.grade),
            )

            val items = arrayOf(
                getString(R.string.settings_grade, gradeLabel),
                if (session == null) {
                    getString(R.string.settings_not_logged_in)
                } else {
                    getString(R.string.settings_logout, session.userName.ifEmpty { "已登录" })
                },
                getString(
                    if (analyticsOn) R.string.settings_analytics_on else R.string.settings_analytics_off,
                ),
                getString(R.string.settings_storage, total),
                getString(R.string.settings_privacy),
                getString(R.string.settings_clear),
                getString(R.string.settings_protocol_status),
            )

            androidx.appcompat.app.AlertDialog.Builder(this@HomeActivity)
                .setTitle(R.string.settings_title)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> pickGrade()
                        1 -> if (session == null) {
                            startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                        } else {
                            confirmLogout()
                        }
                        2 -> toggleAnalytics(!analyticsOn)
                        3 -> showStorageDetail(usage)
                        4 -> startActivity(PrivacyActivity.newIntent(this@HomeActivity))
                        5 -> clearTransient()
                        6 -> showProtocolStatus()
                    }
                }
                .setNegativeButton(R.string.storage_ok, null)
                .show()
        }
    }

    /**
     * 改年级。
     *
     * 登录状态下这里改的是**本地年级**，不会覆盖账号里的年级 —— 请求时仍然是
     * 「账号年级优先」（见 [UserPrefs.effectiveGrade]）。退出登录后本地的这个值就生效了。
     */
    private fun pickGrade() {
        val labels = AiWritingRequest.GRADES.map { it.second }.toTypedArray()
        val current = UserPrefs.effectiveGrade(this, container.sessions.current()?.grade)
        val checked = AiWritingRequest.GRADES.indexOfFirst { it.first == current }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_grade_pick)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val grade = AiWritingRequest.GRADES.getOrNull(which)?.first ?: return@setSingleChoiceItems
                UserPrefs.setGrade(this, grade)
                dialog.dismiss()
                binding.root.showMessage(getString(R.string.settings_grade_changed, labels[which]))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 统计开关。关掉后不再上报（已上报的撤不回来，隐私政策里写明了）。 */
    private fun toggleAnalytics(enable: Boolean) {
        Analytics.setEnabled(this, enable)
        binding.root.showMessage(
            getString(
                if (enable) R.string.settings_analytics_turned_on
                else R.string.settings_analytics_turned_off,
            ),
        )
    }

    private fun showStorageDetail(usage: StorageUsage) {
        val rows = listOf(
            getString(R.string.storage_apk) to usage.apkBytes,
            getString(R.string.storage_capture) to usage.captureBytes,
            getString(R.string.storage_webview) to usage.webViewBytes,
            getString(R.string.storage_history) to usage.historyBytes,
        )
        val message = buildString {
            for ((label, bytes) in rows) {
                append(label).append("：").append(formatBytes(bytes)).append('\n')
            }
            append('\n').append(getString(R.string.storage_total))
                .append("：").append(usage.format())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.storage_title)
            .setMessage(message)
            .setPositiveButton(R.string.storage_ok, null)
            .show()
    }

    private fun clearTransient() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val bytes = StorageCleaner.clearTransient(this@HomeActivity)
                // WebView 缓存要在主线程用 API 清
                withContext(Dispatchers.Main) {
                    runCatching {
                        val webView = android.webkit.WebView(this@HomeActivity)
                        StorageCleaner.clearWebViewCache(webView)
                        webView.destroy()
                    }
                }
                bytes + StorageCleaner.webViewCacheBytes(this@HomeActivity)
            }
            binding.root.showMessage(getString(R.string.storage_cleared, formatBytes(freed)))
        }
    }

    private fun appVersionLabel(): String {
        val name = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        return if (name.isBlank()) "" else "v$name"
    }

    /** 历史按需打开，不在首页布局或用户偏好中保留展开状态。 */
    private fun showRecentSearches() {
        if (historyDialog != null) return
        val panel = DialogRecentSearchesBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        historyDialog = dialog
        dialog.setContentView(panel.root)
        panel.closeHistoryButton.setOnClickListener { dialog.dismiss() }
        panel.historyStats.setText(R.string.home_history_loading)
        dialog.setOnShowListener {
            resizeHistoryPanel(panel, dialog, 0)
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.setOnDismissListener {
            historyJob?.cancel()
            historyJob = null
            historyDialog = null
            panel.historyList.removeAllViews()
        }
        dialog.show()
        bindHistory(panel, dialog)
    }

    private fun resizeHistoryPanel(panel: DialogRecentSearchesBinding, dialog: BottomSheetDialog, count: Int) {
        val available = binding.root.height - binding.root.paddingTop - binding.root.paddingBottom - dp(16)
        val desired = if (count == 0) dp(240) else dp(144 + count * 96)
        val height = minOf(available, dp(720), desired).coerceAtLeast(dp(120))
        panel.root.layoutParams = panel.root.layoutParams.apply { this.height = height }
        dialog.behavior.peekHeight = height
    }

    private fun bindHistory(panel: DialogRecentSearchesBinding, dialog: BottomSheetDialog) {
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            val (entries, today, total) = withContext(Dispatchers.IO) {
                Triple(container.history.list(HISTORY_PREVIEW),
                    container.history.todayCount(), container.history.totalCount())
            }
            panel.historyStats.text = getString(R.string.home_history_stats,
                today, total)
            panel.historyEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            resizeHistoryPanel(panel, dialog, entries.size)
            entries.forEach { entry ->
                val row = ItemHistoryBinding.inflate(layoutInflater, panel.historyList, false)
                (row.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = 0
                    marginEnd = 0
                }
                if (resources.configuration.screenWidthDp < 320) {
                    row.historyBadge.visibility = View.GONE
                    row.historyThumb.layoutParams = row.historyThumb.layoutParams.apply {
                        width = dp(40)
                        height = dp(40)
                    }
                }
                row.historyBadge.subject = entry.subject
                row.historySubject.text = entry.subject.ifEmpty { getString(R.string.result_no_subject) }
                row.historyTime.text = relativeTime(entry.timestamp)
                row.historyCard.setOnClickListener {
                    dialog.dismiss()
                    startActivity(ResultActivity.historyIntent(this@HomeActivity, entry.id))
                }
                panel.historyList.addView(row.root)
                row.historyThumb.setImageBitmap(withContext(Dispatchers.IO) {
                    decodeThumbnail(entry.questionFile)
                })
            }
        }
    }

    override fun onPause() {
        historyDialog?.dismiss()
        super.onPause()
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
        recreate()
    }

    private companion object {
        const val HISTORY_PREVIEW = 6
        const val PREFS_HOME = "home_ui"
        const val STATE_CAPTURE_PATH = "pendingCapturePath"
        const val STATE_CAPTURE_PAGE = "pendingCapturePage"
    }
}
