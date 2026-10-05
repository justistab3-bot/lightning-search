package com.heikeji.phonesearch.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新源的客户端：查最新版本 + 下载 APK。
 *
 * 不签名、不带 Cookie、不碰业务会话——这是公开的 release 接口，与应用协议无关。
 * 只允许 https。
 */
object UpdateClient {

    /**
     * 查最新 release；没有可用 APK 时返回 null。
     *
     * 先问 `/releases/latest`。**如果它没有 apk 附件**（附件上传失败、或只是个说明用的
     * release），就退到列表里挑**版本号最高且带 apk** 的那个 —— 否则用户会永远卡在
     * 「已是最新」，明明新版本就在那儿却升不上去。
     */
    fun fetchLatest(): UpdateInfo? {
        val latest = httpGetText(UpdateConfig.LATEST_RELEASE_URL)?.let { parseRelease(it) }
        if (latest != null && latest.hasApk) return latest

        // 兜底：扫列表，取版本号最高的可用项
        val listBody = httpGetText("${UpdateConfig.RELEASES_URL}?per_page=30") ?: return latest
        return parseReleaseList(listBody)?.takeIf { it.hasApk } ?: latest
    }

    /** 从列表 JSON 里挑出**版本号最高且带 apk** 的那个。 */
    internal fun parseReleaseList(body: String): UpdateInfo? = try {
        val array = org.json.JSONArray(body)
        val all = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { parseRelease(it.toString()) }
        }
        pickBest(all)
    } catch (e: Exception) {
        null
    }

    /** 版本号最高的可用项；没有带 apk 的就返回 null。 */
    internal fun pickBest(candidates: List<UpdateInfo>): UpdateInfo? =
        candidates
            .filter { it.hasApk }
            .maxWithOrNull { a, b -> Version.compare(a.versionName, b.versionName) }

    /** 解析一个 release JSON；缺 tag 时返回 null，缺 apk 时 [UpdateInfo.hasApk] 为 false。 */
    internal fun parseRelease(body: String): UpdateInfo? {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return null
        }

        val tag = json.optString("tag_name", "").ifEmpty { return null }
        val assets = json.optJSONArray("assets")

        // 选第一个 .apk 附件
        var apkUrl = ""
        var apkName = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                    apkUrl = url
                    apkName = name
                    break
                }
            }
        }

        return UpdateInfo(
            versionName = tag.removePrefix("v").removePrefix("V"),
            tagName = tag,
            title = json.optString("name", "").ifEmpty { tag },
            changelog = json.optString("body", ""),
            apkUrl = apkUrl,
            apkFileName = apkName,
        )
    }

    /**
     * 下载 APK 到 [target]（先写 `.part` 再改名，避免半截文件被当成完整包）。
     *
     * @param onProgress 已下载字节 / 总字节；总字节未知时为 -1
     */
    fun downloadApk(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        if (!url.startsWith("https://")) throw IOException("更新地址不是 https")

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            readTimeout = UpdateConfig.READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("下载失败（HTTP ${connection.responseCode}）")
            }
            val total = connection.contentLengthLong
            if (total > UpdateConfig.MAX_APK_BYTES) throw IOException("安装包过大，已中止")

            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")

            var downloaded = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(UpdateConfig.BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        downloaded += read
                        if (downloaded > UpdateConfig.MAX_APK_BYTES) {
                            part.delete()
                            throw IOException("安装包过大，已中止")
                        }
                        output.write(buffer, 0, read)
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }

            if (downloaded < MIN_APK_BYTES) {
                part.delete()
                throw IOException("下载内容不是有效的安装包")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** 用 HEAD 拿一下文件大小，失败返回 -1（Gitee 的附件不一定支持 HEAD）。 */
    fun contentLength(url: String): Long {
        if (!url.startsWith("https://")) return -1
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = UpdateConfig.READ_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (connection.responseCode in 200..299) connection.contentLengthLong else -1
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            -1
        }
    }

    private fun httpGetText(url: String): String? {
        if (!url.startsWith("https://")) return null
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
                readTimeout = UpdateConfig.READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (out.size() + read > UpdateConfig.MAX_METADATA_BYTES) return null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
                String(bytes, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private const val USER_AGENT = "LightningSearch-Android"
    private const val MIN_APK_BYTES = 1024L * 1024
}
