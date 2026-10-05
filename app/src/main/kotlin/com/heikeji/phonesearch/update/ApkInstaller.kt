package com.heikeji.phonesearch.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 调用系统安装器安装已下载的 APK。
 *
 * 全程不经过浏览器：`ACTION_VIEW` + `application/vnd.android.package-archive` +
 * FileProvider 授权，直接把安装界面拉起来。
 */
object ApkInstaller {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** Android 8.0 起需要「安装未知应用」权限。 */
    fun needsUnknownSourcesPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    /**
     * 跳转到本应用的「安装未知应用」授权页。
     *
     * 这是系统设置页，不是浏览器。
     */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /** 构造安装意图；文件必须落在 FileProvider 暴露的目录下。 */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 已下载的 APK 存放目录（files 下，不会被系统清缓存清掉）。 */
    fun updateDir(context: Context): File =
        File(context.filesDir, "updates").apply { mkdirs() }
}
