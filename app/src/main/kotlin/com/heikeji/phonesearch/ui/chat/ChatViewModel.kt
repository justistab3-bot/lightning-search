package com.heikeji.phonesearch.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.heikeji.phonesearch.SearchApp
import com.heikeji.phonesearch.net.ChatClient
import com.heikeji.phonesearch.protocol.chat.model.ChatEvent
import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import com.heikeji.phonesearch.protocol.chat.model.ChatTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 界面上的一个气泡。 */
data class ChatBubble(
    val role: ChatRole,
    val text: String,
    val reasoning: String = "",
    val reasoningCostMs: Long = 0,
    val streaming: Boolean = false,
    val failed: Boolean = false,
    /** 用户发的图片（原图字节，直接展示）。 */
    val imageBytes: ByteArray? = null,
) {
    // data class 带数组字段时 equals/hashCode 要显式实现，否则每次比较都不相等，
    // RecyclerView 会白白重绑。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChatBubble) return false
        return role == other.role &&
            text == other.text &&
            reasoning == other.reasoning &&
            reasoningCostMs == other.reasoningCostMs &&
            streaming == other.streaming &&
            failed == other.failed &&
            (imageBytes === other.imageBytes || imageBytes?.contentEquals(other.imageBytes) == true)
    }

    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + reasoning.hashCode()
        result = 31 * result + reasoningCostMs.hashCode()
        result = 31 * result + streaming.hashCode()
        result = 31 * result + failed.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class ChatUiState(
    val connecting: Boolean = false,
    val bubbles: List<ChatBubble> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val thinkEnabled: Boolean = false,
    val searchEnabled: Boolean = false,
    /** 正在流式输出。 */
    val streaming: Boolean = false,
    /** 一次性提示（错误等），展示后清空。 */
    val message: String? = null,
) {
    val canSend: Boolean get() = !connecting && !streaming
    val isEmpty: Boolean get() = bubbles.isEmpty() && !connecting
}

/**
 * AI 解题的输入上下文：搜题结果里某一道题的关联信息。
 *
 * 官方 ai-pure-page 抓包对齐：`/kdchat/api/ask` 带图 multipart +
 * sid/subjectId/picSearchInfo(etid,pid)，服务端据此讲解对应那道题。
 */
data class AiSolveContext(
    val image: ByteArray,
    val sid: String,
    val subjectId: String,
    val etid: String,
    val pid: String,
    val subject: String,
    val pvalLabel: Int = 1,
)

class ChatViewModel(
    private val client: ChatClient,
    private val grade: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /** 已完成的用户提问，作为下一轮的 `context`。 */
    private val history = ArrayList<ChatTurn>()

    private var sessionId: String = ""
    private var currentAnswerId: String = ""
    private var streamJob: Job? = null

    /** AI 解题排队：会话建好前先存着，建好后自动开讲。 */
    private var pendingAiSolve: AiSolveContext? = null

    fun start() {
        if (sessionId.isNotEmpty() || _state.value.connecting) return
        _state.value = _state.value.copy(connecting = true)
        viewModelScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { client.createSession(grade) }
                sessionId = id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    connecting = false,
                    message = e.message ?: "建立会话失败",
                )
                return@launch
            }
            // AI 解题入口排队中：建完会话直接开讲，不走推荐问题。
            val ai = pendingAiSolve
            if (ai != null) {
                pendingAiSolve = null
                _state.value = _state.value.copy(connecting = false)
                runAiSolve(ai)
                return@launch
            }
            _state.value = _state.value.copy(connecting = false)
            // 推荐问题失败不影响对话
            val suggestions = withContext(Dispatchers.IO) {
                runCatching { client.guide(grade) }.getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(suggestions = suggestions)
        }
    }

    /** AI 解题入口：会话就绪则直接开讲，否则等 [start] 建好会话后自动开讲。 */
    fun startAiSolve(context: AiSolveContext) {
        if (sessionId.isNotEmpty()) {
            runAiSolve(context)
            return
        }
        pendingAiSolve = context
        start()
    }

    fun send(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty() || !_state.value.canSend) return
        runStream(question = text, image = null, displayText = text)
    }

    /**
     * 带图提问。
     *
     * @param image 已经压过的 JPEG；服务端要求 100KB 量级，调用方负责缩放
     * @param caption 图片附带的文字，可为空
     */
    fun sendImage(image: ByteArray, caption: String) {
        if (image.isEmpty() || !_state.value.canSend) return
        val text = caption.trim()
        runStream(question = text, image = image, displayText = text)
    }

    private fun runStream(question: String, image: ByteArray?, displayText: String) {
        val state = _state.value
        _state.value = state.copy(
            bubbles = state.bubbles +
                ChatBubble(role = ChatRole.USER, text = displayText, imageBytes = image) +
                ChatBubble(role = ChatRole.ASSISTANT, text = "", streaming = true),
            suggestions = emptyList(),
            streaming = true,
            message = null,
        )

        val think = state.thinkEnabled
        val search = state.searchEnabled
        val context = ArrayList(history)

        streamJob = viewModelScope.launch {
            var answer = ""
            var reasoning = ""
            var costMs = 0L
            var failure: String? = null

            try {
                withContext(Dispatchers.IO) {
                    val onEvent: (ChatEvent) -> Unit = { event ->
                        when (event) {
                            is ChatEvent.Started -> currentAnswerId = event.answerId
                            is ChatEvent.Delta -> {
                                answer += event.text
                                reasoning += event.reasoning
                                if (event.reasoningCostMs > 0) costMs = event.reasoningCostMs
                                updateStreaming(answer, reasoning, costMs)
                            }

                            ChatEvent.Closed -> Unit
                            // kdcommand 也带正文（实测「我叫小明」整段走这里）
                            is ChatEvent.Command -> if (event.text.isNotEmpty()) {
                                answer += event.text
                                updateStreaming(answer, reasoning, costMs)
                            }

                            is ChatEvent.Failed -> failure = event.message
                            is ChatEvent.Revoked -> failure = "回答被撤回：${event.reason}"
                            is ChatEvent.Refreshed -> if (event.sessionId.isNotEmpty()) {
                                sessionId = event.sessionId
                            }
                        }
                    }

                    if (image == null) {
                        client.ask(
                            sessionId = sessionId,
                            content = question,
                            history = context,
                            grade = grade,
                            thinkEnabled = think,
                            searchEnabled = search,
                            onEvent = onEvent,
                        )
                    } else {
                        client.askWithImage(
                            sessionId = sessionId,
                            jpeg = image,
                            content = question,
                            history = context,
                            grade = grade,
                            thinkEnabled = think,
                            searchEnabled = search,
                            onEvent = onEvent,
                        )
                    }
                }
            } catch (e: CancellationException) {
                // 用户主动停止：保留已经吐出来的内容
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "网络异常"
            }

            // 有内容才算一轮有效对话，进上下文
            if (answer.isNotEmpty()) {
                history.add(ChatTurn(ChatRole.USER, question.ifEmpty { "[图片]" }, System.currentTimeMillis() / 1000))
            }
            currentAnswerId = ""
            finalizeStream(failure, answer, reasoning, costMs)
        }
    }

    /**
     * AI 解题流：带搜题结果上下文（sid/subjectId/etid/pid）讲解指定题目。
     *
     * 与 [runStream] 的区别：走 [ChatClient.askAiSolve]（官方 ai-pure-page 链路）。
     * 会话是本页专属的，讲完之后用户还能继续追问。
     */
    private fun runAiSolve(ctx: AiSolveContext) {
        val state = _state.value
        _state.value = state.copy(
            bubbles = state.bubbles +
                ChatBubble(role = ChatRole.USER, text = ctx.subject, imageBytes = ctx.image) +
                ChatBubble(role = ChatRole.ASSISTANT, text = "", streaming = true),
            suggestions = emptyList(),
            streaming = true,
            message = null,
        )

        streamJob = viewModelScope.launch {
            var answer = ""
            var reasoning = ""
            var costMs = 0L
            var failure: String? = null

            try {
                withContext(Dispatchers.IO) {
                    val onEvent: (ChatEvent) -> Unit = { event ->
                        when (event) {
                            is ChatEvent.Started -> currentAnswerId = event.answerId
                            is ChatEvent.Delta -> {
                                answer += event.text
                                reasoning += event.reasoning
                                if (event.reasoningCostMs > 0) costMs = event.reasoningCostMs
                                updateStreaming(answer, reasoning, costMs)
                            }

                            ChatEvent.Closed -> Unit
                            is ChatEvent.Command -> if (event.text.isNotEmpty()) {
                                answer += event.text
                                updateStreaming(answer, reasoning, costMs)
                            }

                            is ChatEvent.Failed -> failure = event.message
                            is ChatEvent.Revoked -> failure = "回答被撤回：${event.reason}"
                            is ChatEvent.Refreshed -> if (event.sessionId.isNotEmpty()) {
                                sessionId = event.sessionId
                            }
                        }
                    }

                    client.askAiSolve(
                        sessionId = sessionId,
                        jpeg = ctx.image,
                        grade = grade,
                        subjectId = ctx.subjectId,
                        sid = ctx.sid,
                        etid = ctx.etid,
                        pid = ctx.pid,
                        pvalLabel = ctx.pvalLabel,
                        onEvent = onEvent,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "网络异常"
            }

            if (answer.isNotEmpty()) {
                history.add(
                    ChatTurn(
                        ChatRole.USER,
                        "[图片] ${ctx.subject}".trim(),
                        System.currentTimeMillis() / 1000,
                    ),
                )
            }
            currentAnswerId = ""
            finalizeStream(failure, answer, reasoning, costMs)
        }
    }

    /** 停止生成：本地立即定格，同时通知服务端。 */
    fun stopStreaming() {
        val answerId = currentAnswerId
        val session = sessionId
        streamJob?.cancel()
        streamJob = null
        currentAnswerId = ""
        // 取消会跳过协程尾部，所以这里自己收尾，否则气泡一直转圈
        finalizeStream(null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client.stop(session, answerId) }
        }
    }

    /**
     * 把正在流的那条定格，并可选地报错。
     *
     * [finalText] 等传入最终累积值：节流发布可能漏掉最后 ~120ms 的内容，
     * 定格时必须用完整文本覆盖一次。
     */
    private fun finalizeStream(
        failure: String?,
        finalText: String? = null,
        finalReasoning: String? = null,
        finalCostMs: Long? = null,
    ) {
        val bubbles = _state.value.bubbles.toMutableList()
        val last = bubbles.lastIndex
        if (last >= 0 && bubbles[last].role == ChatRole.ASSISTANT) {
            val bubble = bubbles[last]
            val text = finalText ?: bubble.text
            bubbles[last] = bubble.copy(
                text = text,
                reasoning = finalReasoning ?: bubble.reasoning,
                reasoningCostMs = finalCostMs ?: bubble.reasoningCostMs,
                streaming = false,
                failed = failure != null && text.isEmpty(),
            )
        }
        _state.value = _state.value.copy(
            bubbles = bubbles,
            streaming = false,
            message = failure,
        )
    }

    fun toggleThink() {
        _state.value = _state.value.copy(thinkEnabled = !_state.value.thinkEnabled)
    }

    fun toggleSearch() {
        _state.value = _state.value.copy(searchEnabled = !_state.value.searchEnabled)
    }

    fun clear() {
        streamJob?.cancel()
        streamJob = null
        history.clear()
        sessionId = ""
        currentAnswerId = ""
        _state.value = ChatUiState(
            thinkEnabled = _state.value.thinkEnabled,
            searchEnabled = _state.value.searchEnabled,
        )
        start()
    }

    fun consumeMessage() {
        if (_state.value.message != null) _state.value = _state.value.copy(message = null)
    }

    /** 流式期间只按这个间隔发布一次状态，避免逐字刷新卡 UI。 */
    private var lastStreamPublishAt = 0L

    /** 流式期间只改最后一个气泡，避免整列表重建。 */
    private fun updateStreaming(text: String, reasoning: String, costMs: Long) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStreamPublishAt < STREAM_PUBLISH_INTERVAL_MS) return
        lastStreamPublishAt = now
        val bubbles = _state.value.bubbles.toMutableList()
        val last = bubbles.lastIndex
        if (last < 0) return
        bubbles[last] = bubbles[last].copy(
            text = text,
            reasoning = reasoning,
            reasoningCostMs = costMs,
        )
        _state.value = _state.value.copy(bubbles = bubbles)
    }

    companion object {
        /** 流式发布的节流间隔：AI 答案很长，逐字刷新会把主线程压垮。 */
        private const val STREAM_PUBLISH_INTERVAL_MS = 120L

        fun factory(app: SearchApp, grade: Int): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(app.container.chat, grade) as T
            }
    }
}
