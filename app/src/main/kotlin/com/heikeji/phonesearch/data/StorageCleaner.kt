package com.heikeji.phonesearch.data

import android.content.Context
import java.io.File

/** 各类占用，供「存储占用」展示。 */
data class StorageUsage(
    val apkBytes: Long,
    val captureBytes: Long,
    val historyBytes: Long,
    val webViewBytes: Long,
) {
    val totalBytes: Long get() = apkBytes + captureBytes + historyBytes + webViewBytes

    fun format(): String = formatBytes(totalBytes)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 磁盘回收。
 *
 * 起因：应用数据涨到了 98 MB。根因是**更新用的安装包从来不删** ——
 * `filesDir/updates/` 里每下载一个版本就留一个 APK（约 12 MB），
 * 而且它在 `files/` 下，系统清缓存不会动它。装过七八个版本就上百兆。
 *
 * 这里回收的全是「删掉也能重新生成」的东西：
 * 1. `updates/` 只留**最新的一个** APK，其余删掉
 * 2. `cache/captures` 下的相机原图按天数过期（系统相机拍的全分辨率照片，单张好几兆）
 * 3. `history/` 里不在索引中的孤儿目录删掉（索引写失败会留下孤儿）
 *
 * 不碰账号、会话、设置，也不碰 `history/` 里的有效条目。
 * 所有方法都是纯文件操作，**必须在后台线程调用**。
 */
object StorageCleaner {

    /** 相机临时图保留天数。 */
    private const val CAPTURE_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * WebView 缓存上限。
     *
     * 答案页是 WebView，里面的答案图片来自 CDN，Chromium 会把它们缓存到
     * `cache/WebView`。搜得越多长得越大，而且系统不一定会回收。
     * 超了才清 —— 清了之后首次打开答案要重新下载图片。
     */
    const val WEBVIEW_CACHE_LIMIT_BYTES = 32L * 1024 * 1024

    /**
     * 启动时扫一遍。
     *
     * @return 回收掉的字节数
     */
    fun sweep(context: Context): Long =
        pruneUpdates(context) + pruneCaptures(context) + pruneHistoryOrphans(context) +
            trimWebViewCache(context)

    /** 统计当前占用。 */
    fun usage(context: Context): StorageUsage = StorageUsage(
        apkBytes = dirSize(updatesDir(context)),
        captureBytes = dirSize(capturesDir(context)),
        historyBytes = dirSize(File(context.filesDir, HISTORY_DIR)),
        webViewBytes = webViewCacheBytes(context),
    )

    /** WebView 缓存占用。 */
    fun webViewCacheBytes(context: Context): Long = dirSize(webViewCacheDir(context))

    /**
     * WebView 缓存超限就删掉。
     *
     * **只在启动时调用** —— 那一刻还没有任何 WebView 实例，目录不会被 Chromium 占住。
     * 运行中要清就走 [clearWebViewCache]（主线程 + WebView API）。
     *
     * @return 回收掉的字节数
     */
    fun trimWebViewCache(context: Context): Long {
        val dir = webViewCacheDir(context)
        val size = dirSize(dir)
        if (size <= WEBVIEW_CACHE_LIMIT_BYTES) return 0L
        dir.deleteRecursively()
        return size
    }

    /**
     * 运行中清 WebView 缓存。
     *
     * **必须在主线程调用**，且 WebView 实例也必须在主线程构造。
     */
    fun clearWebViewCache(webView: android.webkit.WebView?) {
        runCatching {
            webView?.clearCache(true)
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    /**
     * 清掉临时文件（安装包 + 相机图），保留历史记录。
     *
     * @return 回收掉的字节数
     */
    fun clearTransient(context: Context): Long {
        var freed = dirSize(updatesDir(context))
        updatesDir(context).deleteRecursively()
        freed += dirSize(capturesDir(context))
        capturesDir(context).deleteRecursively()
        freed += pruneStrayCaptures(context)
        return freed
    }

    /** 连历史记录一起清。调用方需先跟用户确认。 */
    fun clearAll(context: Context): Long {
        var freed = clearTransient(context)
        val history = File(context.filesDir, HISTORY_DIR)
        freed += dirSize(history)
        history.deleteRecursively()
        return freed
    }

    // ------------------------------------------------------------------ 各项

    /**
     * `updates/` 只留最新一个 APK。
     *
     * 按修改时间排序而不是文件名 —— 文件名带版本号，
     * 字符串排序会在 `v1.9.0` / `v1.10.0` 这种地方出错。
     */
    private fun pruneUpdates(context: Context): Long {
        val dir = updatesDir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return 0L
        var freed = 0L

        // 下载中断留下的 .part 一律清掉
        val parts = files.filter { it.name.endsWith(PART_SUFFIX) }
        for (part in parts) {
            freed += part.length()
            part.delete()
        }

        val apks = files.filter { it.name.endsWith(APK_SUFFIX) }
        if (apks.size <= 1) return freed

        val keep = apks.maxByOrNull { it.lastModified() }
        for (apk in apks) {
            if (apk == keep) continue
            freed += apk.length()
            apk.delete()
        }
        return freed
    }

    /** `cache/captures` 下的相机原图按天数过期。 */
    private fun pruneCaptures(context: Context): Long {
        val deadline = System.currentTimeMillis() - CAPTURE_MAX_AGE_MS
        var freed = 0L
        for (file in capturesDir(context).listFiles().orEmpty()) {
            if (file.isFile && file.lastModified() < deadline) {
                freed += file.length()
                file.delete()
            }
        }
        return freed
    }

    /**
     * 清掉散在 `cacheDir` 根下的相机/裁剪临时图。
     *
     * 只认固定的文件名前缀，**不碰** WebView 等其他子目录 —— 那些删了会破坏状态。
     */
    private fun pruneStrayCaptures(context: Context): Long {
        var freed = 0L
        for (file in context.cacheDir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            if (STRAY_PREFIXES.none { file.name.startsWith(it) }) continue
            freed += file.length()
            file.delete()
        }
        return freed
    }

    /** 删掉索引里已经没有的 history 目录。 */
    private fun pruneHistoryOrphans(context: Context): Long {
        val root = File(context.filesDir, HISTORY_DIR)
        if (!root.isDirectory) return 0L

        val indexFile = File(root, INDEX_FILE)
        val known = try {
            if (!indexFile.isFile) {
                emptySet()
            } else {
                val array = org.json.JSONArray(indexFile.readText(Charsets.UTF_8))
                (0 until array.length())
                    .mapNotNull { array.optJSONObject(it)?.optString("id") }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
        } catch (e: Exception) {
            // 索引读不出来就什么都别删，免得把有效记录清掉
            return 0L
        }

        var freed = 0L
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory || dir.name in known) continue
            freed += dirSize(dir)
            dir.deleteRecursively()
        }
        return freed
    }

    // ------------------------------------------------------------------ 工具

    private fun updatesDir(context: Context): File = File(context.filesDir, UPDATES_DIR)

    private fun capturesDir(context: Context): File = File(context.cacheDir, CAPTURES_DIR)

    private fun webViewCacheDir(context: Context): File = File(context.cacheDir, WEBVIEW_DIR)

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    private const val UPDATES_DIR = "updates"
    private const val CAPTURES_DIR = "captures"
    private const val HISTORY_DIR = "history"
    private const val WEBVIEW_DIR = "WebView"
    private const val INDEX_FILE = "index.json"
    private const val PART_SUFFIX = ".part"
    private const val APK_SUFFIX = ".apk"

    /** 散落在 cacheDir 根下的临时图前缀（见 CameraActivity / CropActivity / HomeActivity）。 */
    private val STRAY_PREFIXES = listOf("capture-", "question-", "system-")
}
