package com.heikeji.phonesearch.protocol.aiwriting

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.heikeji.phonesearch.protocol.aiwriting.model.AiArticle
import com.heikeji.phonesearch.protocol.aiwriting.model.AiParagraph
import com.heikeji.phonesearch.protocol.aiwriting.model.AiWritingEvent

/**
 * 把一条 SSE 事件解析成业务事件。
 *
 * 服务端每个 `msg` 事件的 `data` 是一个 JSON，里面：
 * - `more`：1 表示还有后续，0 表示这是最后一条
 * - `articleResult[]`：本次新增的**片段**（通常 1-2 个字），打字机效果用
 * - `session.selected[]`（仅最后一条）：分好段的**完整文章**，这是权威结果
 *
 * 片段是增量不是全量 —— 实测 511 个事件的片段拼起来正好等于 `wordCount`。
 */
object AiWritingEventParser {

    fun parse(event: SseEvent): List<AiWritingEvent> {
        when (event.event) {
            "close" -> return listOf(AiWritingEvent.Closed)
            "msg" -> Unit
            else -> return listOf(AiWritingEvent.Unknown(event.event, event.data))
        }

        val json = try {
            JsonParser.parseString(event.data)
        } catch (e: RuntimeException) {
            return listOf(AiWritingEvent.Unknown(event.event, event.data))
        }
        if (!json.isJsonObject) {
            return listOf(AiWritingEvent.Unknown(event.event, event.data))
        }
        val root = json.asJsonObject

        val events = ArrayList<AiWritingEvent>(2)

        // 增量片段
        val piece = pieceOf(root)
        if (piece.isNotEmpty()) events.add(AiWritingEvent.Delta(piece))

        // 结束事件带完整文章
        if (root.intOr("more", 1) == 0) {
            events.add(AiWritingEvent.Finished(articleOf(root, piece)))
        }
        return events
    }

    /** `articleResult[].content` 拼接。 */
    private fun pieceOf(root: JsonObject): String {
        val list = root.get("articleResult")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return ""
        val sb = StringBuilder()
        for (i in 0 until list.size()) {
            val element = list.get(i)
            if (!element.isJsonObject) continue
            val content = element.asJsonObject.get("content")
            if (content == null || content.isJsonNull || !content.isJsonPrimitive) continue
            sb.append(content.asString)
        }
        return sb.toString()
    }

    /**
     * 优先用 `session.selected[]`（分好段的完整文章）；
     * 拿不到时退化成 `articleResult[].content`。
     */
    private fun articleOf(root: JsonObject, fallback: String): AiArticle {
        val paragraphs = ArrayList<AiParagraph>()

        val selected = root.get("session")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("selected")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray

        if (selected != null) {
            for (i in 0 until selected.size()) {
                val element = selected.get(i)
                if (!element.isJsonObject) continue
                val node = element.asJsonObject
                val content = node.str("content")
                if (content.isEmpty()) continue
                paragraphs.add(
                    AiParagraph(
                        id = node.str("paragraph_id"),
                        content = content,
                        paraIndex = i,
                    ),
                )
            }
        }

        if (paragraphs.isEmpty() && fallback.isNotEmpty()) {
            paragraphs.add(AiParagraph(id = "", content = fallback, paraIndex = 0))
        }

        // 首段可能是 markdown 标题（`# 标题`），抽出来单独显示，别把 `#` 露给用户
        val first = paragraphs.firstOrNull()
        val heading = first?.let { HEADING.matchEntire(it.content.trim())?.groupValues?.get(1)?.trim() }
            .orEmpty()
        val body = if (heading.isNotEmpty()) paragraphs.drop(1) else paragraphs

        return AiArticle(
            title = root.str("title"),
            queryType = root.str("queryType"),
            articleType = root.str("articleType"),
            wordCount = root.intOr("wordCount", 0),
            sid = root.str("sid"),
            sessionId = root.str("sessionId"),
            paragraphs = body,
            heading = heading,
        )
    }

    /** `# 标题` / `## 标题` / … */
    private val HEADING = Regex("^#{1,6}\\s*(.+)$")

    private fun JsonObject.str(name: String): String {
        val element = get(name) ?: return ""
        if (element.isJsonNull || !element.isJsonPrimitive) return ""
        return element.asString
    }

    private fun JsonObject.intOr(name: String, default: Int): Int {
        val element = get(name) ?: return default
        if (element.isJsonNull || !element.isJsonPrimitive) return default
        return element.asString.trim().toIntOrNull() ?: default
    }
}
