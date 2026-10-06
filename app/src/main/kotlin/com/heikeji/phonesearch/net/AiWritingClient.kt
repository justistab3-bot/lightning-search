package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingEventParser
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingRequest
import com.heikeji.phonesearch.protocol.aiwriting.EssayLanguage
import com.heikeji.phonesearch.protocol.aiwriting.SseParser
import com.heikeji.phonesearch.protocol.aiwriting.model.AiWritingEvent
import com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode
import com.heikeji.phonesearch.protocol.core.codec.UrlForm
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

/**
 * AI 作文的 HTTP 通道。
 *
 * 与搜题那套的区别：
 * - 独立域名 `api.kuaiduizuoye.com`；
 * - **没有签名**，只要 `cuid` + `appid` + 版本号几个头；
 * - 生成接口是 SSE，要边读边吐。
 */
class AiWritingClient {

    /** 一次「准备」的结果。 */
    data class Prepared(
        val sid: String,
        val sessionId: String,
        val queryType: String,
    )

    /**
     * 标题 -> 文体识别。失败不致命：调用方可以退化成默认文体。
     */
    fun detectQueryType(cuid: String, title: String, gradeId: Int): String? {
        val body = AiWritingRequest.writingIntentBody(cuid, title, gradeId)
        val json = postJson(cuid, AiWritingRequest.PATH_WRITING_INTENT, "", body)
            ?: return null
        return json.optJSONObject("data")?.optString("queryType")?.takeIf { it.isNotEmpty() }
    }

    /**
     * 准备生成，拿到 `sid` / `sessionId`。
     *
     * @param queryType 中文字体名（如「记叙文」）；英语作文该值不参与，随便传。
     */
    fun prepare(
        cuid: String,
        mode: WritingMode,
        language: EssayLanguage,
        title: String,
        wordCount: String,
        gradeId: Int,
        queryType: String,
        writeDate: Long,
        describe: String,
    ): Prepared {
        val path = AiWritingRequest.preInstantPath(mode, language)
        val query =
            AiWritingRequest.preInstantQuery(title, wordCount, gradeId, language, describe)
        val body = AiWritingRequest.preInstantBody(
            title = title,
            queryType = queryType,
            wordCount = wordCount,
            gradeId = gradeId,
            writeDate = writeDate,
            language = language,
            describe = describe,
        )
        val json = postJson(cuid, path, query, body)
            ?: throw ProtocolException("准备生成失败")
        val data = json.optJSONObject("data") ?: throw ProtocolException("准备生成失败")
        val sid = data.optString("sid")
        val sessionId = data.optString("sessionId")
        if (sid.isEmpty() || sessionId.isEmpty()) throw ProtocolException("准备生成失败")
        return Prepared(sid = sid, sessionId = sessionId, queryType = queryType)
    }

    /**
     * 流式生成。
     *
     * 阻塞读取，调用方负责放到 IO 线程并处理取消。
     * 每个事件通过 [onEvent] 回调；返回时流已结束。
     */
    fun stream(
        cuid: String,
        mode: WritingMode,
        language: EssayLanguage,
        prepared: Prepared,
        title: String,
        wordCount: String,
        gradeId: Int,
        describe: String,
        onEvent: (AiWritingEvent) -> Unit,
    ) {
        val path = AiWritingRequest.instantPath(mode, language)
        val query = AiWritingRequest.instantQuery(
            sid = prepared.sid,
            cuid = cuid,
            sessionId = prepared.sessionId,
            title = title,
            wordCount = wordCount,
            gradeId = gradeId,
            language = language,
            describe = describe,
        )
        val url = NetConfig.HOST_API_KDDZY + path + "?" + query
        val connection = open(url)
        try {
            connection.requestMethod = "GET"
            connection.readTimeout = STREAM_READ_TIMEOUT_MS
            applyCommonHeaders(connection, cuid)
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Pragma", "no-cache")

            val status = connection.responseCode
            if (status >= 400) {
                throw ProtocolException("生成失败（HTTP $status）")
            }

            val parser = SseParser()
            connection.inputStream.bufferedReader().use { reader ->
                readStream(reader, parser, onEvent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ProtocolException("网络中断，生成未完成")
        } finally {
            connection.disconnect()
        }
    }

    /** 通知服务端「已读」。失败无所谓，不影响本地结果。 */
    fun acknowledge(cuid: String, sid: String) {
        if (sid.isEmpty()) return
        try {
            val query = AiWritingRequest.cungongRefreshQuery(sid)
            getJson(cuid, AiWritingRequest.PATH_CUNGONG_REFRESH, query)
        } catch (e: Exception) {
            // 静默：这是收尾动作
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun readStream(
        reader: BufferedReader,
        parser: SseParser,
        onEvent: (AiWritingEvent) -> Unit,
    ) {
        while (true) {
            val line = reader.readLine() ?: break
            val event = parser.feed(line.trimEnd('\r')) ?: continue
            for (parsed in AiWritingEventParser.parse(event)) {
                onEvent(parsed)
            }
        }
        parser.finish()?.let { event ->
            for (parsed in AiWritingEventParser.parse(event)) {
                onEvent(parsed)
            }
        }
    }

    private fun postJson(cuid: String, path: String, query: String, body: String): JSONObject? {
        val url = NetConfig.HOST_API_KDDZY + path + if (query.isEmpty()) "" else "?$query"
        val connection = open(url)
        return try {
            connection.requestMethod = "POST"
            connection.readTimeout = NetConfig.READ_TIMEOUT_MS
            connection.doOutput = true
            applyCommonHeaders(connection, cuid)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")

            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            parseJson(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(cuid: String, path: String, query: String): JSONObject? {
        val url = NetConfig.HOST_API_KDDZY + path + if (query.isEmpty()) "" else "?$query"
        val connection = open(url)
        return try {
            connection.requestMethod = "GET"
            connection.readTimeout = NetConfig.READ_TIMEOUT_MS
            applyCommonHeaders(connection, cuid)
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return null
            parseJson(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJson(text: String): JSONObject? = try {
        val json = JSONObject(text)
        // errNo 非 0 视为失败
        if (json.optInt("errNo", 0) != 0) null else json
    } catch (e: Exception) {
        null
    }

    private fun applyCommonHeaders(connection: HttpURLConnection, cuid: String) {
        connection.setRequestProperty("appid", NetConfig.APP_ID)
        connection.setRequestProperty("cuid", cuid)
        connection.setRequestProperty("vc", NetConfig.VC)
        connection.setRequestProperty("vcname", NetConfig.VC_NAME)
        connection.setRequestProperty("channel", NetConfig.CHANNEL)
        connection.setRequestProperty("os", NetConfig.OS)
        connection.setRequestProperty("Origin", NetConfig.HOST_KDDZY)
        connection.setRequestProperty("Referer", NetConfig.HOST_KDDZY + "/")
        connection.setRequestProperty("X-Requested-With", NetConfig.PKG_NAME)
        connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
        connection.setRequestProperty("User-Agent", NetConfig.AI_WRITING_USER_AGENT)
    }

    private fun open(url: String): HttpURLConnection {
        if (!url.startsWith(NetConfig.HOST_API_KDDZY + "/")) {
            throw ProtocolException("请求地址无效")
        }
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = NetConfig.CONNECT_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        return connection
    }

    private companion object {
        /** SSE 是长连接，用较长的**单次读**超时（不是总时长）。 */
        const val STREAM_READ_TIMEOUT_MS = 90_000
    }
}
