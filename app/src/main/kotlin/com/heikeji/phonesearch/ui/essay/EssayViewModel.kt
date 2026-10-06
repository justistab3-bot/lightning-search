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
    /** 手动指定的文体；null 表示自动识别。 */
    val genre: String? = null,
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
    enum class Stage { IDLE, DETECTING, OUTLINING, WRITING, REWRITING, DONE }

    val running: Boolean
        get() = stage == Stage.DETECTING || stage == Stage.OUTLINING ||
            stage == Stage.WRITING || stage == Stage.REWRITING

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

    /** 流式节流：攒下的增量文本与上次发布时间（对齐聊天的节流策略）。 */
    private var pendingText = ""
    private var lastDeltaPublishAt = 0L

    // ------------------------------------------------------------------ 用户输入

    fun onTitleChanged(title: String) {
        _state.value = _state.value.copy(title = title)
    }

    fun onModeChanged(mode: WritingMode) {
        val previous = _state.value
        if (mode == previous.mode) return
        // 换模式/语言后旧结果不再适用：取消在跑的生成并清空
        job?.cancel()
        job = null

        val language = if (mode == WritingMode.ENGLISH) {
            EssayLanguage.ENGLISH
        } else {
            EssayLanguage.CHINESE
        }
        val wordCounts = AiWritingRequest.wordCountsOf(language)
        val languageChanged = language != previous.language

        _state.value = previous.copy(
            mode = mode,
            language = language,
            // 切换语言时字数档位不通用，回到该语言的默认值
            wordCount = wordCounts.firstOrNull { it == previous.wordCount }
                ?: AiWritingRequest.defaultWordCount(language),
            // 中英互换时标题语言也对不上，一并清掉
            title = if (languageChanged) "" else previous.title,
            // 文体是中文作文的概念，英语没有
            genre = if (language == EssayLanguage.ENGLISH) null else previous.genre,
            text = "",
            outline = "",
            article = null,
            queryType = "",
            stage = EssayUiState.Stage.IDLE,
            failed = false,
        )
    }

    fun onWordCountChanged(wordCount: String) {
        // 目标字数变了，旧结果不再对得上，一并清掉
        _state.value = _state.value.copy(
            wordCount = wordCount,
            text = "",
            article = null,
            stage = EssayUiState.Stage.IDLE,
            failed = false,
        )
    }

    fun onGradeChanged(gradeId: Int) {
        _state.value = _state.value.copy(gradeId = gradeId)
    }

    /** @param genre null 表示「自动」 */
    fun onGenreChanged(genre: String?) {
        _state.value = _state.value.copy(
            genre = genre,
            // 文体变了旧结果不再对得上
            text = "",
            article = null,
            queryType = "",
            stage = EssayUiState.Stage.IDLE,
            failed = false,
        )
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
        val target = wordCount.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

        // 1. 文体识别（仅中文，失败不致命）
        val detected = if (language == EssayLanguage.CHINESE) {
            withContext(Dispatchers.IO) {
                client.detectQueryType(cuid, title, gradeId)
            } ?: DEFAULT_QUERY_TYPE
        } else {
            // 英语作文没有文体概念
            DEFAULT_QUERY_TYPE
        }
        // 手动选了文体就以它为准，否则用识别结果
        val queryType = snapshot.genre?.takeIf { it.isNotBlank() } ?: detected
        _state.value = _state.value.copy(queryType = queryType)

        // 年级 + 文体都折进 describe，这是实测唯一起作用的写法约束
        val describe = AiWritingRequest.writingRequirements(gradeId, snapshot.genre)

        // 2 + 3. 准备 + 流式生成。
        //
        // 服务端有时会明显少写（要 800 只给 500 出头），而且重试是有效手段，
        // 所以达不到目标就再写一次，保留更长的那篇。上限 MAX_ATTEMPTS 次。
        var bestText = ""
        var bestArticle: AiArticle? = null
        var bestCount = -1

        for (attempt in 1..MAX_ATTEMPTS) {
            _state.value = _state.value.copy(
                stage = if (attempt == 1) {
                    EssayUiState.Stage.WRITING
                } else {
                    EssayUiState.Stage.REWRITING
                },
                text = "",
                article = null,
            )

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
                    describe = describe,
                )
            }

            streamInto(title, wordCount, gradeId, mode, language, prepared, describe)

            // 收尾通知（失败无所谓）
            withContext(Dispatchers.IO) { client.acknowledge(cuid, prepared.sid) }

            val article = _state.value.article
            val count = article?.wordCount ?: _state.value.text.length
            if (count > bestCount) {
                bestCount = count
                bestText = _state.value.text
                bestArticle = article
            }
            if (target <= 0 || bestCount >= target * MIN_RATIO) break
        }

        _state.value = _state.value.copy(
            stage = EssayUiState.Stage.DONE,
            text = bestText,
            article = bestArticle,
        )
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
        describe: String,
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
                        describe = describe,
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
                            // 节流发布：逐字更新会每次都重排整个 TextView + 滚动，
                            // 长作文下 UI 明显卡顿、生成显得特别慢。
                            pendingText += event.text
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastDeltaPublishAt >= STREAM_PUBLISH_INTERVAL_MS) {
                                lastDeltaPublishAt = now
                                _state.value = _state.value.copy(
                                    text = _state.value.text + pendingText,
                                )
                                pendingText = ""
                            }
                        }

                        is AiWritingEvent.Finished -> {
                            // 权威结果：用分段正文覆盖流式拼接的结果（顺带冲掉节流攒着的尾巴）
                            pendingText = ""
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

            // 兜底：流结束但没收到 Finished（异常中断）时，把节流攒下的内容补上
            if (pendingText.isNotEmpty()) {
                _state.value = _state.value.copy(text = _state.value.text + pendingText)
                pendingText = ""
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

        /** 流式发布的节流间隔：逐字刷新会拖垮长作文的 UI。 */
        const val STREAM_PUBLISH_INTERVAL_MS = 120L

        /** 达到目标字数的这个比例就算合格，不再重写。 */
        const val MIN_RATIO = 0.85

        /** 最多生成几次（含首次）。 */
        const val MAX_ATTEMPTS = 2
    }
}
