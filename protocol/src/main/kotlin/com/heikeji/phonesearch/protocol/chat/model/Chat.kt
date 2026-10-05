package com.heikeji.phonesearch.protocol.chat.model

/** 对话里的角色。 */
enum class ChatRole { USER, ASSISTANT }

/**
 * 一轮对话。
 *
 * 发请求时会把历史拼成 `context` 数组，服务端据此维持上下文。
 */
data class ChatTurn(
    val role: ChatRole,
    val text: String,
    /** Unix 秒，服务端要求带上。 */
    val timeSeconds: Long,
    /** 本轮的回答分片里累计出来的思考过程（可能为空）。 */
    val reasoning: String = "",
)

/** 一次流式回答的实时状态。 */
data class ChatReply(
    val text: String = "",
    val reasoning: String = "",
    /** 思考耗时（毫秒），服务端随思考分片下发，取最后一个值。 */
    val reasoningCostMs: Long = 0,
    val done: Boolean = false,
) {
    val hasReasoning: Boolean get() = reasoning.isNotBlank()
    val isEmpty: Boolean get() = text.isBlank() && reasoning.isBlank()
}

/**
 * SSE 流里解析出来的一件事。
 *
 * 对应实测的三种 `event:`：`response` / `msg` / `close`，另有 `error`、`revoke` 等兜底。
 */
sealed interface ChatEvent {

    /** 流建立，服务端分配本轮 id。 */
    data class Started(
        val sessionId: String,
        val questionId: String,
        val answerId: String,
        val contentType: Int,
        /** 是否允许对这条回答做「深度思考重答」。 */
        val reaskThinkEnabled: Boolean,
    ) : ChatEvent

    /**
     * 一段内容。
     *
     * 深度思考时先来一串只有 [reasoning] 的分片，随后才是只有 [text] 的分片。
     */
    data class Delta(
        val text: String,
        val reasoning: String,
        val reasoningCostMs: Long,
        val source: String,
        val seq: Int,
    ) : ChatEvent

    /** 正常结束。 */
    data object Closed : ChatEvent

    /**
     * 服务端下发的指令，**也会带正文**。
     *
     * 实测「我叫小明，请记住」这条回答整段都走 `kdcommand` 而不是 `msg`：
     * ```
     * event:kdcommand
     * data:{"cmd":"setUsername","data":{"content":"{\"text\":\"小明你好…\",\"name\":\"小明\"}"}}
     * ```
     * 所以不能忽略，否则这类回答会显示成空白。
     */
    data class Command(val cmd: String, val text: String) : ChatEvent

    /** 服务端报错。 */
    data class Failed(val message: String) : ChatEvent

    /** 内容被风控撤回。 */
    data class Revoked(val type: Int, val reason: String) : ChatEvent

    /** 会话需要刷新（`kdrefresh` / `refresh`）。 */
    data class Refreshed(val sessionId: String) : ChatEvent
}
