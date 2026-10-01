package com.heikeji.phonesearch.ui.page

import android.graphics.RectF
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.heikeji.phonesearch.AppContainer
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.image.OriginalImageHandle
import com.heikeji.phonesearch.net.SearchChallengeException
import com.heikeji.phonesearch.net.SessionExpiredException
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.search.SearchRepository
import com.heikeji.phonesearch.search.SearchTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 整页搜题的状态机。
 *
 * 持有原图句柄（ViewModel 销毁时释放临时文件）、整页结果、框选精搜缓存，
 * 并在每个异步回调落地前核对 generation / KDUSS / UID。
 */
class PageViewModel(
    private val repository: SearchRepository,
    private val sessions: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PageUiState())
    val state: StateFlow<PageUiState> = _state.asStateFlow()

    private var handle: OriginalImageHandle? = null
    private var job: Job? = null
    private var generation = 0L
    private var task: SearchTask? = null
    private var challengeLaunched = false

    /** 接管原图句柄；换图时释放上一个。 */
    fun attach(newHandle: OriginalImageHandle) {
        if (handle === newHandle) return
        handle?.close()
        handle = newHandle
        // 换图等于新搜索：清空整页结果、选择位置、框选和精搜缓存
        generation += 1
        task = null
        _state.value = PageUiState()
    }

    val originalHandle: OriginalImageHandle? get() = handle

    // ------------------------------------------------------------------ 整页搜索

    fun start(grade: Int) {
        val current = handle ?: return
        challengeLaunched = false
        task = newTask(
            handle = current,
            requestMode = SearchMode.PAGE,
            sourceMode = SearchMode.PAGE,
            uploadJpeg = current.uploadJpeg,
        )
        run(grade) { repository.searchPage(it, grade) }
    }

    /** 验证完成后按原模式恢复。 */
    fun retryAfterVerification(grade: Int) {
        challengeLaunched = false
        repository.clearChallenge()
        resumeCurrent(grade)
    }

    /** 从登录页回来后继续原任务。 */
    fun resumeAfterLogin(grade: Int) {
        challengeLaunched = false
        resumeCurrent(grade)
    }

    private fun resumeCurrent(grade: Int) {
        val current = task ?: return
        when (current.requestMode) {
            SearchMode.PAGE -> run(grade) { repository.searchPage(it, grade) }
            else -> viewModelScope.launch { runRefine(grade, current) }
        }
    }

    fun markChallengeLaunched() {
        challengeLaunched = true
    }

    fun shouldLaunchChallenge(): Boolean = !challengeLaunched

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(loading = false)
    }

    // ------------------------------------------------------------------ 选择

    fun selectBlock(position: Int) {
        val state = _state.value
        if (position !in state.blocks.indices) return
        _state.value = state.copy(
            selectedBlockPosition = position,
            selectedCandidatePosition = 0,
            message = null,
        )
    }

    fun selectCandidate(position: Int) {
        val state = _state.value
        if (position !in state.candidates.indices) return
        _state.value = state.copy(selectedCandidatePosition = position)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ------------------------------------------------------------------ 框选精搜

    /**
     * 用归一化框选区域对当前题块做精搜。
     *
     * 裁剪**从原始文件区域解码**，不从预览图二次裁剪。
     */
    fun refine(rect: RectF, grade: Int) {
        val current = handle ?: return
        val state = _state.value
        val result = state.result ?: return
        val block = state.selectedBlock ?: return
        if (job?.isActive == true) return

        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            try {
                val cropped = withContext(Dispatchers.IO) { current.cropToJpeg(rect) }
                val refineTask = newTask(
                    handle = current,
                    requestMode = SearchMode.CROP_SINGLE,
                    sourceMode = SearchMode.PAGE,
                    uploadJpeg = cropped,
                    // loc 用的是整页响应图片坐标系，所以沿用整页上传图的尺寸
                    wholeSearchSid = result.sid,
                    serviceBlockIndex = block.serviceIndex,
                    selectedBlockPosition = state.selectedBlockPosition,
                    selectedRectNormalized = floatArrayOf(
                        rect.left, rect.top, rect.right, rect.bottom,
                    ),
                )
                task = refineTask
                runRefine(grade, refineTask)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    message = e.message ?: "框选重搜失败，请重试",
                )
            }
        }
    }

    private suspend fun runRefine(grade: Int, refineTask: SearchTask) {
        try {
            val refined = withContext(Dispatchers.IO) { repository.search(refineTask, grade) }
            if (isStale(refineTask)) return
            val index = refineTask.serviceBlockIndex
            _state.value = _state.value.copy(
                loading = false,
                refined = _state.value.refined + (index to refined.items),
                selectedCandidatePosition = 0,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchChallengeException) {
            if (isStale(refineTask)) return
            _state.value = _state.value.copy(
                loading = false,
                message = "需要完成安全验证后继续",
            )
        } catch (e: SessionExpiredException) {
            _state.value = _state.value.copy(
                loading = false,
                needLogin = true,
                message = e.message ?: "登录已失效，请重新登录",
            )
        } catch (e: Exception) {
            if (isStale(refineTask)) return
            _state.value = _state.value.copy(
                loading = false,
                message = e.message ?: "框选重搜失败，请重试",
            )
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun run(
        grade: Int,
        block: suspend (SearchTask) -> com.heikeji.phonesearch.protocol.model.PageSearchResult,
    ) {
        val current = task ?: return
        if (job?.isActive == true) return

        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            try {
                val result = withContext(Dispatchers.IO) { block(current) }
                if (isStale(current)) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    result = result,
                    selectedBlockPosition = firstDisplayableBlock(result),
                    selectedCandidatePosition = 0,
                    refined = emptyMap(),
                    message = result.positioningWarning.takeIf { it.isNotEmpty() },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchChallengeException) {
                if (isStale(current)) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    message = "需要完成安全验证后继续",
                )
            } catch (e: SessionExpiredException) {
                _state.value = _state.value.copy(
                    loading = false,
                    needLogin = true,
                    message = e.message ?: "登录已失效，请重新登录",
                )
            } catch (e: Exception) {
                if (isStale(current)) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    message = e.message ?: "整页搜题失败，请重试",
                )
            }
        }
    }

    /** 默认选中第一个有可显示答案的题块。 */
    private fun firstDisplayableBlock(
        result: com.heikeji.phonesearch.protocol.model.PageSearchResult,
    ): Int {
        val index = result.blocks.indexOfFirst { it.hasAnswer }
        return if (index >= 0) index else 0
    }

    private fun newTask(
        handle: OriginalImageHandle,
        requestMode: SearchMode,
        sourceMode: SearchMode,
        uploadJpeg: ByteArray,
        wholeSearchSid: String = "",
        serviceBlockIndex: Int = -1,
        selectedBlockPosition: Int = -1,
        selectedRectNormalized: FloatArray? = null,
    ): SearchTask {
        generation += 1
        return SearchTask(
            generation = generation,
            requestMode = requestMode,
            sourceMode = sourceMode,
            originalSearchJpeg = handle.uploadJpeg,
            uploadJpeg = uploadJpeg,
            // loc 一律基于整页响应图片坐标系
            uploadWidth = handle.uploadWidth,
            uploadHeight = handle.uploadHeight,
            kdussSnapshot = sessions.kduss(),
            uidSnapshot = sessions.current()?.uid.orEmpty(),
            wholeSearchSid = wholeSearchSid,
            selectedBlockPosition = selectedBlockPosition,
            serviceBlockIndex = serviceBlockIndex,
            selectedRectNormalized = selectedRectNormalized,
        )
    }

    private fun isStale(current: SearchTask): Boolean =
        !current.stillMatches(generation, sessions.kduss(), sessions.current()?.uid.orEmpty())

    override fun onCleared() {
        handle?.close()
        handle = null
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PageViewModel(container.searchRepository, container.sessions) as T
            }
    }
}
