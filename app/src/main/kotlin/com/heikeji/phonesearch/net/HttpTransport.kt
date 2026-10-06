package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.NetConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 一次 HTTP 响应。 */
data class HttpResult(
    val statusCode: Int,
    val body: String,
    /** 服务端 Date 头，0 表示缺失。 */
    val dateMillis: Long,
)

/**
 * 一个打开的流式响应。
 *
 * 调用方逐行读 [reader]，结束时必须 [close]（会断开连接）。
 */
class StreamHandle internal constructor(
    private val connection: HttpURLConnection,
    val statusCode: Int,
    val reader: java.io.BufferedReader,
) : java.io.Closeable {

    override fun close() {
        try {
            reader.close()
        } catch (e: Exception) {
            // 收尾动作，忽略
        }
        connection.disconnect()
    }
}

/**
 * 底层 HTTP（对应原 P0.c.f 里 HttpURLConnection 的使用方式）。
 *
 * 硬性约束：
 * - 只允许 [NetConfig.API_HOSTS] 里的主机；
 * - path 必须以单斜杠开头，不能是 `//`，不能含 `?` 或 `#`；
 * - 禁止自动重定向；connect 15s；read 默认 30s；
 * - 响应正文有上限，超限直接失败。
 */
class HttpTransport(
    /**
     * 官方手机客户端的附加请求头（Dp-Ticket、zyb-*、na__kf_source__、Trace 等）。
     *
     * 每次请求重新调用（Trace 与票据都是按请求现算的）；空值头会被跳过，
     * 与官方 `j.java` 里「空头不加」的行为一致。仅对作业帮系主机生效。
     */
    private val phoneHeaders: () -> Map<String, String> = { emptyMap() },
) {

    fun post(
        host: String,
        path: String,
        body: ByteArray,
        contentType: String,
        cookie: String?,
        acceptGzip: Boolean = false,
        readTimeoutMs: Int = NetConfig.READ_TIMEOUT_MS,
        maxBytes: Int = NetConfig.MAX_RESPONSE_BYTES,
        userAgent: String = NetConfig.USER_AGENT,
    ): HttpResult {
        validate(host, path)
        val connection = open(host + path)
        return try {
            connection.requestMethod = "POST"
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Accept-Encoding",
                if (acceptGzip) "gzip" else "identity",
            )
            connection.setRequestProperty("User-Agent", applyPhoneIdentity(connection, host, userAgent))
            connection.setRequestProperty("X-Wap-Proxy-Cookie", "none")
            connection.setRequestProperty("Content-Type", contentType)
            if (!cookie.isNullOrEmpty()) {
                connection.setRequestProperty("Cookie", cookie)
            }
            connection.setFixedLengthStreamingMode(body.size)

            connection.outputStream.use { it.write(body) }
            read(connection, maxBytes, acceptGzip)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 流式 POST，用于 `text/event-stream`。
     *
     * 与 [post] 的区别：**不缓冲**，把连接和 reader 交给调用方边读边处理，
     * 读完必须 [StreamHandle.close]。
     */
    fun postStream(
        host: String,
        path: String,
        body: ByteArray,
        contentType: String,
        cookie: String?,
        readTimeoutMs: Int,
        accept: String = "text/event-stream",
        userAgent: String = NetConfig.USER_AGENT,
    ): StreamHandle {
        validate(host, path)
        val connection = open(host + path)
        return try {
            connection.requestMethod = "POST"
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Accept", accept)
            // SSE 不能压缩，否则解不出来
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", applyPhoneIdentity(connection, host, userAgent))
            connection.setRequestProperty("X-Wap-Proxy-Cookie", "none")
            connection.setRequestProperty("Content-Type", contentType)
            if (!cookie.isNullOrEmpty()) {
                connection.setRequestProperty("Cookie", cookie)
            }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val stream: InputStream? =
                if (status >= 400) connection.errorStream else connection.inputStream
            StreamHandle(
                connection = connection,
                statusCode = status,
                // 不能用 InputStream.nullInputStream()：那是 API 33 才有的，
                // 本应用最低支持 5.0。
                reader = (stream ?: ByteArrayInputStream(ByteArray(0))).bufferedReader(),
            )
        } catch (e: Exception) {
            connection.disconnect()
            throw e
        }
    }

    /** 用于官方验证页的 HTML 抓取。 */    fun getHtml(
        url: String,
        readTimeoutMs: Int,
        maxBytes: Int,
        userAgent: String,
        cookie: String? = null,
    ): HttpResult {
        val connection = open(url)
        return try {
            connection.requestMethod = "GET"
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", applyPhoneIdentity(connection, url, userAgent))
            if (!cookie.isNullOrEmpty()) {
                connection.setRequestProperty("Cookie", cookie)
            }
            read(connection, maxBytes, acceptGzip = false)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = NetConfig.CONNECT_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        return connection
    }

    /**
     * 对作业帮系主机换上官方手机客户端的身份：WebView UA + Dp-Ticket/zyb-* 等附加头。
     * 返回最终使用的 UA。
     */
    private fun applyPhoneIdentity(
        connection: HttpURLConnection,
        hostOrUrl: String,
        baseUserAgent: String,
    ): String {
        if (!isPhoneHost(hostOrUrl)) return baseUserAgent
        for ((key, value) in phoneHeaders()) {
            if (key.isNotEmpty() && value.isNotEmpty()) {
                connection.setRequestProperty(key, value)
            }
        }
        return phoneUserAgent()
    }

    private fun isPhoneHost(hostOrUrl: String): Boolean =
        hostOrUrl.contains("kuaiduizuoye.com") ||
            hostOrUrl.contains("zuoyebang.com") ||
            hostOrUrl.contains("zybang.com")

    /** 官方 7.7.0 的 WebView UA，按本机 Build 动态拼（与官方 App 同源）。 */
    private fun phoneUserAgent(): String =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
            "${android.os.Build.MODEL} Build/${android.os.Build.ID}; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/153.0.8010.36 Mobile Safari/537.36"

    private fun read(
        connection: HttpURLConnection,
        maxBytes: Int,
        acceptGzip: Boolean,
    ): HttpResult {
        val status = connection.responseCode
        val dateMillis = connection.getHeaderFieldDate("Date", 0L)
        val stream: InputStream? =
            if (status >= 400) connection.errorStream else connection.inputStream
        if (stream == null) return HttpResult(status, "", dateMillis)

        var input: InputStream = stream
        if (acceptGzip && "gzip".equals(connection.contentEncoding, ignoreCase = true)) {
            input = GZIPInputStream(input)
        }
        val text = input.use { readLimited(it, maxBytes) }
        return HttpResult(status, text, dateMillis)
    }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (out.size() + read > maxBytes) throw IOException("响应内容过大")
            out.write(buffer, 0, read)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun validate(host: String, path: String) {
        if (host !in NetConfig.API_HOSTS) throw ProtocolException("请求地址无效")
        if (!path.startsWith("/") || path.startsWith("//") ||
            path.contains("?") || path.contains("#")
        ) {
            throw ProtocolException("请求地址无效")
        }
    }
}
