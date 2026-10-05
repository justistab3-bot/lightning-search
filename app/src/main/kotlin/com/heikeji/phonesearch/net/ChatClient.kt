package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.aiwriting.SseParser
import com.heikeji.phonesearch.protocol.chat.ChatEventParser
import com.heikeji.phonesearch.protocol.chat.ChatRequest
import com.heikeji.phonesearch.protocol.chat.model.ChatEvent
import com.heikeji.phonesearch.protocol.chat.model.ChatTurn
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.protocol.sign.RequestSigner
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * 快问 AI 的 HTTP 通道。
 *
 * 和搜题同一套域名与签名，但**参数是明文表单**（不加密），响应是 SSE。
 * 见 [ChatRequest] 里的实测记录。
 */
class ChatClient(
    private val identity: DeviceIdentity,
    private val protocol: ProtocolContext,
    private val sessions: SessionRepository,
    private val transport: HttpTransport,
) {

    /** 建会话，返回 sessionId。 */
    fun createSession(grade: Int): String {
        val json = postJson(ChatRequest.PATH_CREATE, ChatRequest.createParams(grade))
        val sessionId = json?.optJSONObject("data")?.optString("sessionId").orEmpty()
        if (sessionId.isEmpty()) throw ProtocolException("建立会话失败")
        return sessionId
    }

    /** 推荐问题。失败不致命，返回空列表。 */
    fun guide(grade: Int): List<String> {
        val json = try {
            postJson(ChatRequest.PATH_GUIDE, ChatRequest.guideParams(grade))
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()
        val array: JSONArray = json.optJSONObject("data")?.optJSONArray("queryGuide") ?: return emptyList()
        val result = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val text = array.optJSONObject(i)?.optString("content").orEmpty()
            if (text.isNotEmpty()) result.add(text)
        }
        return result
    }

    /**
     * 带图提问并流式读取回答。
     *
     * 走 `/kdchat/photo/ask`：multipart 里第一部分是图片，其余是同一批表单字段。
     */
    fun askWithImage(
        sessionId: String,
        jpeg: ByteArray,
        content: String,
        history: List<ChatTurn>,
        grade: Int,
        thinkEnabled: Boolean,
        searchEnabled: Boolean,
        onEvent: (ChatEvent) -> Unit,
    ) {
        val params = ChatRequest.photoAskParams(
            sessionId = sessionId,
            content = content,
            history = history,
            grade = grade,
            thinkEnabled = thinkEnabled,
            searchEnabled = searchEnabled,
            picMd5 = md5Hex(jpeg),
        )
        val merged = signedParams(params)
        val boundary = ProtocolProfile.MULTIPART_BOUNDARY_PREFIX +
            UUID.randomUUID().toString().replace("-", "")
        val body = Multipart.build(boundary, jpeg, params = merged)

        streamRequest(
            path = ChatRequest.PATH_PHOTO_ASK,
            body = body,
            contentType = "multipart/form-data; boundary=$boundary",
            onEvent = onEvent,
        )
    }

    /**
     * 提问并流式读取回答。
     *
     * 阻塞执行，调用方负责放到 IO 线程；每个事件通过 [onEvent] 回调。
     */
    fun ask(
        sessionId: String,
        content: String,
        history: List<ChatTurn>,
        grade: Int,
        thinkEnabled: Boolean,
        searchEnabled: Boolean,
        onEvent: (ChatEvent) -> Unit,
    ) {
        val params = ChatRequest.askParams(
            sessionId = sessionId,
            content = content,
            history = history,
            grade = grade,
            thinkEnabled = thinkEnabled,
            searchEnabled = searchEnabled,
        )
        streamRequest(
            path = ChatRequest.PATH_ASK,
            body = signedBody(params),
            contentType = ProtocolProfile.FORM_CONTENT_TYPE,
            onEvent = onEvent,
        )
    }

    /** 发一个流式请求并把 SSE 事件翻译出来。 */
    private fun streamRequest(
        path: String,
        body: ByteArray,
        contentType: String,
        onEvent: (ChatEvent) -> Unit,
    ) {
        val handle = transport.postStream(
            host = ProtocolProfile.HOST_KDDZY,
            path = path,
            body = body,
            contentType = contentType,
            cookie = cookie(),
            readTimeoutMs = STREAM_READ_TIMEOUT_MS,
        )
        try {
            if (handle.statusCode >= 400) {
                throw ProtocolException("问答请求失败（HTTP ${handle.statusCode}）")
            }
            val parser = SseParser()
            while (true) {
                val line = try {
                    handle.reader.readLine()
                } catch (e: IOException) {
                    throw ProtocolException("网络中断，回答未完成")
                } ?: break
                val event = parser.feed(line.trimEnd('\r')) ?: continue
                for (parsed in ChatEventParser.parse(event)) onEvent(parsed)
            }
            parser.finish()?.let { event ->
                for (parsed in ChatEventParser.parse(event)) onEvent(parsed)
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            handle.close()
        }
    }

    /** 停止生成。失败无所谓，本地已经停了。 */
    fun stop(sessionId: String, answerId: String) {
        if (sessionId.isEmpty()) return
        try {
            postJson(
                ChatRequest.PATH_STOP,
                linkedMapOf("sessionId" to sessionId, "answerId" to answerId),
            )
        } catch (e: Exception) {
            // 收尾动作，静默
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun postJson(path: String, params: Map<String, String>): JSONObject? {
        val result = transport.post(
            host = ProtocolProfile.HOST_KDDZY,
            path = path,
            body = signedBody(params),
            contentType = ProtocolProfile.FORM_CONTENT_TYPE,
            cookie = cookie(),
        )
        protocol.calibrate(result.dateMillis)
        if (result.statusCode !in 200..299) {
            throw ProtocolException("请求失败（HTTP ${result.statusCode}）")
        }
        val json = try {
            JSONObject(result.body)
        } catch (e: Exception) {
            throw ProtocolException("服务端返回无法识别")
        }
        val errNo = json.optInt("errNo", 0)
        if (errNo != 0) {
            val message = json.optString("errstr").ifEmpty { "错误码 $errNo" }
            throw ProtocolException(message)
        }
        return json
    }

    /**
     * 合并公共参数并签名，返回编码好的表单体。
     *
     * 与搜题那套一致：公共参数 -> 覆盖 identityIdV2/occupationType/nt -> 签名 -> 补 `sign`/`_t_`/`kakorrhaphiophobia`。
     */
    private fun signedBody(params: Map<String, String>): ByteArray =
        UrlForm.encodeForm(signedParams(params)).toByteArray(Charsets.UTF_8)

    /** 同上，但返回参数表本身（multipart 需要）。 */
    private fun signedParams(params: Map<String, String>): LinkedHashMap<String, String?> {
        protocol.ensureInitialized()

        val merged = LinkedHashMap<String, String?>()
        merged.putAll(params)
        for ((key, value) in identity.publicParams()) {
            if (!merged.containsKey(key)) merged[key] = value
        }
        val session = sessions.current()
        merged["identityIdV2"] = (session?.identityIdV2 ?: 0).toString()
        merged["occupationType"] = (session?.occupationType ?: 0).toString()
        merged["nt"] = identity.networkType()

        val tSeconds = protocol.nowSeconds()
        val uptime = protocol.uptimeMillis()
        val sign = RequestSigner.sign(
            items = RequestSigner.toItems(merged),
            deviceSecretDigest = protocol.deviceSecretDigest(),
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        if (!RequestSigner.isUsable(sign)) throw ProtocolException("签名初始化失败，未发送请求")
        merged["sign"] = sign
        merged["_t_"] = tSeconds.toString()
        merged["kakorrhaphiophobia"] = uptime.toString()

        return merged
    }

    /** 图片的 md5（小写 hex），服务端用它去比对是否见过同一张图。 */
    private fun md5Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private fun cookie(): String? {
        if (ProtocolProfile.HOST_KDDZY !in ProtocolProfile.COOKIE_HOSTS) return null
        val kduss = sessions.kduss()
        return buildString {
            append("cuid=").append(UrlForm.encode(identity.cuid))
            if (kduss.isNotEmpty()) append("; KDUSS=").append(UrlForm.encode(kduss))
        }
    }

    private companion object {
        /** SSE 是长连接，用较长的**单次读**超时（不是总时长）。 */
        const val STREAM_READ_TIMEOUT_MS = 90_000

        val HEX = "0123456789abcdef".toCharArray()
    }
}
