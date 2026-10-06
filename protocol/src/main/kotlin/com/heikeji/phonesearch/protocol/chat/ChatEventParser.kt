package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.aiwriting.SseEvent
import com.heikeji.phonesearch.protocol.chat.model.ChatEvent
import com.heikeji.phonesearch.protocol.core.json.Json
import com.heikeji.phonesearch.protocol.core.json.arrOrNull
import com.heikeji.phonesearch.protocol.core.json.objOrNull
import com.heikeji.phonesearch.protocol.core.json.strOrEmpty
import com.google.gson.JsonObject

/**
 * 把一条 SSE 事件翻译成 [ChatEvent]。
 *
 * 实测 `/kdchat/api/ask` 的帧（`thinkEnabled=1`，一次「你好」共 100 帧）：
 * ```
 * id:1
 * event:response
 * data:{"sessionId":142120923415,"questionId":142120923423,"answerId":142120923424,
 *       "contentType":26,"kdEnableReaskThink":1,...}
 *
 * id:2
 * event:msg
 * data:{"source":"kw","data":{"contentType":26,"seq":2,
 *       "content":"{\"text\":\"\",\"reasoningContent\":\"用户\",\"reasoningCostTime\":121}"}}
 *
 * id:100
 * event:close
 * data:<nil>
 * ```
 *
 * 三个容易踩的点：
 * 1. `event` 是 SSE 的 `event:` 行，**不在** JSON 里；
 * 2. `data` 本身是 JSON 对象，但它内部的 `data.content` 是**字符串**，要二次解析；
 * 3. `close` 的 data 是字面量 `<nil>`，不是合法 JSON。
 */
object ChatEventParser {

    /** 服务端用这个表示「没有数据」。 */
    private const val NIL = "<nil>"

    fun parse(event: SseEvent): List<ChatEvent> = when (event.event) {
        "response" -> listOfNotNull(started(event.data))
        "msg" -> listOfNotNull(delta(event.data))
        "close" -> listOf(ChatEvent.Closed)
        "error" -> listOf(ChatEvent.Failed(messageOf(event.data)))
        "revoke" -> listOfNotNull(revoked(event.data))
        "kdrefresh", "refresh" -> listOfNotNull(refreshed(event.data))
        "kdcommand" -> listOfNotNull(command(event.data))
        // audio / reset 等本应用暂不处理
        else -> emptyList()
    }

    /**
     * `kdcommand` 也携带正文。
     *
     * 结构：`{"cmd":"setUsername","data":{"content":"<JSON 字符串>"}}`，
     * 内层 content 形如 `{"text":"...","name":"小明"}`。
     */
    private fun command(data: String): ChatEvent.Command? {
        val root = objOrNull(data) ?: return null
        val cmd = root.strOrEmpty("cmd")
        val inner = root.objOrNull("data") ?: return null
        val content = inner.objOrNull("content") ?: parseLoose(inner.strOrEmpty("content"))
        val text = content.strOrEmpty("text")
        // 没有正文的指令（如 deleteRecord）交给上层忽略
        if (cmd.isEmpty() && text.isEmpty()) return null
        return ChatEvent.Command(cmd = cmd, text = text)
    }

    // ------------------------------------------------------------------ 各事件

    private fun started(data: String): ChatEvent.Started? {
        val root = objOrNull(data) ?: return null
        return ChatEvent.Started(
            sessionId = root.strOrEmpty("sessionId"),
            questionId = root.strOrEmpty("questionId"),
            answerId = root.strOrEmpty("answerId"),
            contentType = root.intOrZero("contentType"),
            reaskThinkEnabled = root.intOrZero("kdEnableReaskThink") == 1,
        )
    }

    private fun delta(data: String): ChatEvent.Delta? {
        val root = objOrNull(data) ?: return null
        val inner = root.objOrNull("data") ?: return null
        // content 是 JSON 字符串；万一以后改成对象也照样吃
        val content = inner.objOrNull("content") ?: parseLoose(inner.strOrEmpty("content"))
        return ChatEvent.Delta(
            text = content.strOrEmpty("text"),
            reasoning = content.strOrEmpty("reasoningContent"),
            reasoningCostMs = content.longOrZero("reasoningCostTime"),
            source = root.strOrEmpty("source"),
            seq = inner.intOrZero("seq"),
        )
    }

    private fun revoked(data: String): ChatEvent.Revoked? {
        val root = objOrNull(data) ?: return null
        val inner = root.objOrNull("data") ?: root
        return ChatEvent.Revoked(
            type = inner.strOrEmpty("type").toIntOrNull() ?: inner.intOrZero("type"),
            reason = inner.strOrEmpty("reason"),
        )
    }

    private fun refreshed(data: String): ChatEvent.Refreshed? {
        val root = objOrNull(data) ?: return null
        val sessionId = root.strOrEmpty("sessionId").ifEmpty { root.objOrNull("data").strOrEmpty("sessionId") }
        return if (sessionId.isEmpty()) null else ChatEvent.Refreshed(sessionId)
    }

    /** `error` 的载荷没有样本，尽量从各层里捞出可读文案。 */
    private fun messageOf(data: String): String {
        val root = objOrNull(data) ?: return data.ifEmpty { "服务端返回错误" }
        for (key in listOf("errstr", "message", "msg", "error")) {
            val value = root.strOrEmpty(key)
            if (value.isNotEmpty()) return value
        }
        val inner = root.objOrNull("data")
        if (inner != null) {
            for (key in listOf("errstr", "message", "msg", "error")) {
                val value = inner.strOrEmpty(key)
                if (value.isNotEmpty()) return value
            }
        }
        return "服务端返回错误"
    }

    // ------------------------------------------------------------------ 工具

    private fun objOrNull(text: String): JsonObject? {
        if (text.isEmpty() || text == NIL) return null
        return try {
            Json.parseObject(text, "对话流格式无法识别")
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLoose(text: String): JsonObject? = objOrNull(text)

    // json 包里只有 strOrEmpty / objOrNull / arrOrNull，数值这两个在这里补
    private fun JsonObject?.intOrZero(key: String): Int =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    private fun JsonObject?.longOrZero(key: String): Long =
        this?.get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}
