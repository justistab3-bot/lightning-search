package com.heikeji.phonesearch.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.databinding.ActivityUpdateBinding
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.displayMessage
import com.heikeji.phonesearch.ui.common.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内更新：展示新版本 -> 直接下载 -> 调系统安装器。
 *
 * 全程不打开浏览器：
 * - 下载走 Gitee release 附件的直链（`UpdateClient.downloadApk`）
 * - 安装走 `ACTION_VIEW` + FileProvider 授权（`ApkInstaller`）
 *
 * 唯一的例外是 Android 8+ 的「安装未知应用」授权页——那是系统设置，不是浏览器。
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateBinding
    private var info: UpdateInfo? = null
    private var downloadedApk: File? = null
    private var busy = false

    /** 从「安装未知应用」设置页回来：授权成功就继续装。 */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (ApkInstaller.needsUnknownSourcesPermission(this)) {
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.setText(R.string.update_need_permission)
        } else {
            installNow()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        info = readInfo(intent)
        val current = info
        if (current == null) {
            finish()
            return
        }

        binding.versionText.text = getString(
            R.string.update_version_format,
            currentVersionName(),
            current.versionName,
        )
        binding.changelogText.text = MarkdownLite.plain(current.changelog)
            .ifBlank { current.title }

        binding.actionButton.setOnClickListener { onAction() }
        binding.laterButton.setOnClickListener { finish() }

        // 上次已经下好同一个版本就直接给安装按钮
        val cached = File(ApkInstaller.updateDir(this), current.apkFileName)
        if (cached.isFile && cached.length() > 0) {
            downloadedApk = cached
            showReadyToInstall()
        }
    }

    private fun onAction() {
        if (busy) return
        if (downloadedApk != null) {
            installNow()
        } else {
            download()
        }
    }

    // ------------------------------------------------------------------ 下载

    private fun download() {
        val current = info ?: return
        if (busy) return
        busy = true

        binding.actionButton.isEnabled = false
        binding.progressRow.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.progressText.text = ""
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.setText(R.string.update_downloading)

        lifecycleScope.launch {
            try {
                val target = File(ApkInstaller.updateDir(this@UpdateActivity), current.apkFileName)
                val apk = withContext(Dispatchers.IO) {
                    UpdateClient.downloadApk(current.apkUrl, target) { done, total ->
                        runOnUiThread { renderProgress(done, total) }
                    }
                }
                downloadedApk = apk
                busy = false
                showReadyToInstall()
            } catch (e: Exception) {
                busy = false
                binding.progressRow.visibility = View.GONE
                binding.actionButton.isEnabled = true
                binding.actionButton.setText(R.string.update_retry)
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = getString(
                    R.string.update_download_failed,
                    e.displayMessage(getString(R.string.update_check_failed)),
                )
            }
        }
    }

    private fun renderProgress(done: Long, total: Long) {
        if (total > 0) {
            binding.progressBar.isIndeterminate = false
            val percent = ((done * 100) / total).toInt().coerceIn(0, 100)
            binding.progressBar.progress = percent
            binding.progressText.text = "$percent%"
        } else {
            binding.progressBar.isIndeterminate = true
            binding.progressText.text = "${done / 1024 / 1024} MB"
        }
    }

    // ------------------------------------------------------------------ 安装

    private fun showReadyToInstall() {
        binding.progressRow.visibility = View.GONE
        binding.actionButton.isEnabled = true
        binding.actionButton.setText(R.string.update_install)
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.setText(R.string.update_downloaded)
    }

    private fun installNow() {
        val apk = downloadedApk ?: return

        if (ApkInstaller.needsUnknownSourcesPermission(this)) {
            binding.statusText.visibility = View.VISIBLE
            binding.statusText.setText(R.string.update_need_permission)
            runCatching { settingsLauncher.launch(ApkInstaller.unknownSourcesSettingsIntent(this)) }
                .onFailure {
                    binding.root.showMessage(getString(R.string.update_install_failed))
                }
            return
        }

        try {
            startActivity(ApkInstaller.installIntent(this, apk))
        } catch (e: ActivityNotFoundException) {
            binding.root.showMessage(getString(R.string.update_install_failed))
        }
    }

    // ------------------------------------------------------------------ 辅助

    private fun currentVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()

    companion object {
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHANGELOG = "changelog"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE_NAME = "file_name"

        fun newIntent(context: Context, info: UpdateInfo): Intent =
            Intent(context, UpdateActivity::class.java)
                .putExtra(EXTRA_VERSION, info.versionName)
                .putExtra(EXTRA_TAG, info.tagName)
                .putExtra(EXTRA_TITLE, info.title)
                .putExtra(EXTRA_CHANGELOG, info.changelog)
                .putExtra(EXTRA_URL, info.apkUrl)
                .putExtra(EXTRA_FILE_NAME, info.apkFileName)

        private fun readInfo(intent: Intent): UpdateInfo? {
            val version = intent.getStringExtra(EXTRA_VERSION) ?: return null
            val url = intent.getStringExtra(EXTRA_URL) ?: return null
            val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return null
            if (url.isEmpty() || fileName.isEmpty()) return null
            return UpdateInfo(
                versionName = version,
                tagName = intent.getStringExtra(EXTRA_TAG).orEmpty(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                changelog = intent.getStringExtra(EXTRA_CHANGELOG).orEmpty(),
                apkUrl = url,
                apkFileName = fileName,
            )
        }
    }
}

/** 把 release 说明的 markdown 处理成可读纯文本。 */
private object MarkdownLite {

    private val tableSeparator = Regex("^\\s*\\|?[\\s:|-]{4,}\\|?\\s*$")

    fun plain(markdown: String): String {
        if (markdown.isBlank()) return ""
        return markdown
            .lineSequence()
            .filterNot { tableSeparator.matches(it) }
            .joinToString("\n") { line ->
                line.trim()
                    .removePrefix("### ").removePrefix("## ").removePrefix("# ")
                    .replace("**", "")
                    .replace("`", "")
                    .replace(Regex("^[-*+]\\s+"), "· ")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                    .trimEnd()
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
