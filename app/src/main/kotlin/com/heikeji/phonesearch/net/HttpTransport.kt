package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
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
 * - 只允许 [ProtocolProfile.API_HOSTS] 里的主机；
 * - path 必须以单斜杠开头，不能是 `//`，不能含 `?` 或 `#`；
 * - 禁止自动重定向；connect 15s；read 默认 30s；
 * - 响应正文有上限，超限直接失败。
 */
class HttpTransport {

    fun post(
        host: String,
        path: String,
        body: ByteArray,
        contentType: String,
        cookie: String?,
        acceptGzip: Boolean = false,
        readTimeoutMs: Int = ProtocolProfile.READ_TIMEOUT_MS,
        maxBytes: Int = ProtocolProfile.MAX_RESPONSE_BYTES,
        userAgent: String = ProtocolProfile.USER_AGENT,
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
            connection.setRequestProperty("User-Agent", userAgent)
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
        userAgent: String = ProtocolProfile.USER_AGENT,
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
            connection.setRequestProperty("User-Agent", userAgent)
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
                reader = (stream ?: InputStream.nullInputStream()).bufferedReader(),
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
            connection.setRequestProperty("User-Agent", userAgent)
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
        connection.connectTimeout = ProtocolProfile.CONNECT_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        return connection
    }

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
        if (host !in ProtocolProfile.API_HOSTS) throw ProtocolException("请求地址无效")
        if (!path.startsWith("/") || path.startsWith("//") ||
            path.contains("?") || path.contains("#")
        ) {
            throw ProtocolException("请求地址无效")
        }
    }
}
