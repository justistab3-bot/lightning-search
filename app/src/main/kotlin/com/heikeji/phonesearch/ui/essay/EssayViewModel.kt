package com.heikeji.phonesearch.ui.essay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.net.AiWritingClient
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingRequest
import com.heikeji.phonesearch.protocol.aiwriting.EssayLanguage
import com.heikeji.phonesearch.protocol.aiwriting.model.AiArticle
import com.heikeji.phonesearch.protocol.aiwriting.model.AiWritingEvent
import com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI 作文页的状态。 */
data class EssayUiState(
    val mode: WritingMode = WritingMode.QUICK,
    val language: EssayLanguage = EssayLanguage.CHINESE,
    val title: String = "",
    val wordCount: String = AiWritingRequest.DEFAULT_WORD_COUNT_CHINESE,
    val gradeId: Int = AiWritingRequest.DEFAULT_GRADE,
    /** 识别出的文体，识别到才有值。 */
    val queryType: String = "",
    val stage: Stage = Stage.IDLE,
    /** 流式过程中已收到的正文。 */
    val text: String = "",
    /** 提纲模式下的提纲文本。 */
    val outline: String = "",
    /** 完成后的权威结果。 */
    val article: AiArticle? = null,
    val failed: Boolean = false,
) {
    enum class Stage { IDLE, DETECTING, OUTLINING, WRITING, DONE }

    val running: Boolean
        get() = stage == Stage.DETECTING || stage == Stage.OUTLINING || stage == Stage.WRITING

    val done: Boolean get() = stage == Stage.DONE

    /** 是否处于「先列提纲」的中间态：等用户确认后再成文。 */
    val awaitingOutlineConfirm: Boolean
        get() = stage == Stage.OUTLINING && outline.isNotBlank()

    val hasResult: Boolean get() = text.isNotBlank()
}

/**
 * AI 作文。
 *
 * 流程（快速写作）：识别文体 -> 准备 -> 流式生成。
 * 英语作文跳过文体识别，且字数档位不同。
 */
class EssayViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val client: AiWritingClient = container.aiWriting
    private val cuid: String get() = container.identity.cuid

    private val _state = MutableStateFlow(EssayUiState())
    val state: StateFlow<EssayUiState> = _state.asStateFlow()

    private var job: Job? = null

    // ------------------------------------------------------------------ 用户输入

    fun onTitleChanged(title: String) {
        _state.value = _state.value.copy(title = title)
    }

    fun onModeChanged(mode: WritingMode) {
        val language = if (mode == WritingMode.ENGLISH) {
            EssayLanguage.ENGLISH
        } else {
            EssayLanguage.CHINESE
        }
        val wordCounts = AiWritingRequest.wordCountsOf(language)
        _state.value = _state.value.copy(
            mode = mode,
            language = language,
            // 切换语言时字数档位不通用，回到该语言的默认值
            wordCount = wordCounts.firstOrNull { it == _state.value.wordCount }
                ?: AiWritingRequest.defaultWordCount(language),
        )
    }

    fun onWordCountChanged(wordCount: String) {
        _state.value = _state.value.copy(wordCount = wordCount)
    }

    fun onGradeChanged(gradeId: Int) {
        _state.value = _state.value.copy(gradeId = gradeId)
    }

    // ------------------------------------------------------------------ 生成

    /** 开始生成；若正在生成则先取消。 */
    fun generate() {
        val current = _state.value
        val title = current.title.trim()
        if (title.isEmpty()) {
            _state.value = current.copy(failed = true, stage = EssayUiState.Stage.IDLE)
            return
        }

        job?.cancel()
        _state.value = current.copy(
            title = title,
            stage = EssayUiState.Stage.DETECTING,
            text = "",
            outline = "",
            article = null,
            queryType = "",
            failed = false,
        )

        job = viewModelScope.launch {
            try {
                runGeneration(title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    stage = EssayUiState.Stage.IDLE,
                    failed = true,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value.running) {
            _state.value = _state.value.copy(stage = EssayUiState.Stage.IDLE)
        }
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }

    private suspend fun runGeneration(title: String) {
        val snapshot = _state.value
        val mode = snapshot.mode
        val language = snapshot.language
        val wordCount = snapshot.wordCount
        val gradeId = snapshot.gradeId

        // 1. 文体识别（仅中文，失败不致命）
        var queryType = ""
        if (language == EssayLanguage.CHINESE) {
            queryType = withContext(Dispatchers.IO) {
                client.detectQueryType(cuid, title, gradeId)
            } ?: DEFAULT_QUERY_TYPE
            _state.value = _state.value.copy(queryType = queryType)
        } else {
            // 英语作文没有文体概念
            queryType = DEFAULT_QUERY_TYPE
        }

        // 2. 准备
        val prepared = withContext(Dispatchers.IO) {
            client.prepare(
                cuid = cuid,
                mode = mode,
                language = language,
                title = title,
                wordCount = wordCount,
                gradeId = gradeId,
                queryType = queryType,
                writeDate = System.currentTimeMillis() / 1000,
            )
        }

        // 3. 流式生成
        _state.value = _state.value.copy(stage = EssayUiState.Stage.WRITING)
        streamInto(title, wordCount, gradeId, mode, language, prepared)

        _state.value = _state.value.copy(stage = EssayUiState.Stage.DONE)

        // 收尾通知（失败无所谓）
        withContext(Dispatchers.IO) { client.acknowledge(cuid, prepared.sid) }
    }

    /**
     * 边读边更新。
     *
     * SSE 读取是阻塞的，放在 IO 线程；事件通过 Channel 回到主线程更新状态，
     * 这样 UI 更新始终在主线程，且取消时读取会随协程一起结束。
     */
    private suspend fun streamInto(
        title: String,
        wordCount: String,
        gradeId: Int,
        mode: WritingMode,
        language: EssayLanguage,
        prepared: AiWritingClient.Prepared,
    ) {
        val channel = Channel<AiWritingEvent>(Channel.UNLIMITED)
        coroutineScope {
            val producer = launch(Dispatchers.IO) {
                try {
                    client.stream(
                        cuid = cuid,
                        mode = mode,
                        language = language,
                        prepared = prepared,
                        title = title,
                        wordCount = wordCount,
                        gradeId = gradeId,
                    ) { event -> channel.trySend(event) }
                    channel.close()
                } catch (e: CancellationException) {
                    channel.close(e)
                    throw e
                } catch (e: Exception) {
                    // 让消费侧拿到原始异常
                    channel.close(e)
                }
            }

            try {
                for (event in channel) {
                    when (event) {
                        is AiWritingEvent.Delta -> {
                            _state.value = _state.value.copy(text = _state.value.text + event.text)
                        }

                        is AiWritingEvent.Finished -> {
                            // 权威结果：用分段正文覆盖流式拼接的结果
                            _state.value = _state.value.copy(
                                text = event.article.text,
                                article = event.article,
                                queryType = event.article.queryType.ifEmpty {
                                    _state.value.queryType
                                },
                            )
                        }

                        is AiWritingEvent.Closed -> Unit
                        is AiWritingEvent.Unknown -> Unit
                    }
                }
            } finally {
                producer.cancel()
            }
        }

        // 一个字符都没收到，按失败处理
        if (_state.value.text.isBlank()) {
            throw ProtocolException("没有收到内容")
        }
    }

    private companion object {
        /** 文体识别失败时的兜底。 */
        const val DEFAULT_QUERY_TYPE = "记叙文"
    }
}
