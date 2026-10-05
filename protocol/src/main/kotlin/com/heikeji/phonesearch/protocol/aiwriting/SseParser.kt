package com.heikeji.phonesearch.protocol.aiwriting

/**
 * 一条 SSE 事件。
 *
 * AI 作文的流长这样：
 * ```
 * id:511
 * event:msg
 * data:{"more":1,...}
 *
 * id:512
 * event:close
 * data:<nil>
 * ```
 */
data class SseEvent(
    val id: String,
    val event: String,
    val data: String,
)

/**
 * 增量式 SSE 解析器（`text/event-stream`）。
 *
 * 用法：逐行 [feed]，事件块结束时返回一条 [SseEvent]；流结束后调 [finish] 收尾。
 * 解析器不关心 data 的内容，交给上层。
 */
class SseParser {

    private val dataBuffer = StringBuilder()
    private var id = ""
    private var event = ""
    private var hasField = false

    /**
     * 送入一行（不含行尾换行符）。
     *
     * @return 该行触发了事件块结束时返回事件，否则返回 null
     */
    fun feed(line: String): SseEvent? {
        // 空行 = 事件块结束
        if (line.isEmpty()) return flush()

        // 以冒号开头是注释（心跳常用），忽略
        if (line.startsWith(":")) return null

        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        // 规范：冒号后若有一个空格，去掉它
        if (value.startsWith(" ")) value = value.substring(1)

        when (field) {
            "id" -> {
                id = value
                hasField = true
            }

            "event" -> {
                event = value
                hasField = true
            }

            "data" -> {
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(value)
                hasField = true
            }
            // retry 等字段这里用不到
        }
        return null
    }

    /** 流结束时调用：还有未派发的事件就补发。 */
    fun finish(): SseEvent? = if (hasField) flush() else null

    private fun flush(): SseEvent? {
        if (!hasField) return null
        val event = SseEvent(id = id, event = this.event, data = dataBuffer.toString())
        dataBuffer.setLength(0)
        id = ""
        this.event = ""
        hasField = false
        return event
    }
}
