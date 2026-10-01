package com.heikeji.phonesearch.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.heikeji.phonesearch.AppContainer
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.net.SearchChallengeException
import com.heikeji.phonesearch.net.SessionExpiredException
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.protocol.model.SearchResult
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
 * 单题搜题的状态机：发起请求、处理验证挑战、处理登录失效、可取消。
 *
 * 1.1.1 起内部持有完整 [SearchTask]，验证与重登后按原任务恢复，
 * 并在回调落地前核对 generation / KDUSS / UID。
 */
class SearchViewModel(
    private val repository: SearchRepository,
    private val sessions: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 防止旋转后重复拉起验证页（状态存在 ViewModel 里，能跨配置变更保留）。 */
    private var challengeLaunched = false

    private var generation = 0L
    private var task: SearchTask? = null

    fun start(
        jpeg: ByteArray,
        grade: Int,
        uploadWidth: Int = 0,
        uploadHeight: Int = 0,
    ) {
        challengeLaunched = false
        task = buildTask(
            requestMode = SearchMode.SINGLE,
            sourceMode = SearchMode.SINGLE,
            originalJpeg = jpeg,
            uploadJpeg = jpeg,
            uploadWidth = uploadWidth,
            uploadHeight = uploadHeight,
        )
        run(grade)
    }

    /** 验证完成后重试。 */
    fun retryAfterVerification(grade: Int) {
        challengeLaunched = false
        repository.clearChallenge()
        run(grade)
    }

    /** 从登录页回来后继续原任务。 */
    fun resumeAfterLogin(grade: Int) {
        challengeLaunched = false
        run(grade)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = SearchUiState.Idle
    }

    fun markChallengeLaunched() {
        challengeLaunched = true
    }

    fun shouldLaunchChallenge(): Boolean = !challengeLaunched

    private fun buildTask(
        requestMode: SearchMode,
        sourceMode: SearchMode,
        originalJpeg: ByteArray,
        uploadJpeg: ByteArray,
        uploadWidth: Int,
        uploadHeight: Int,
    ): SearchTask {
        generation += 1
        return SearchTask(
            generation = generation,
            requestMode = requestMode,
            sourceMode = sourceMode,
            originalSearchJpeg = originalJpeg,
            uploadJpeg = uploadJpeg,
            uploadWidth = uploadWidth,
            uploadHeight = uploadHeight,
            kdussSnapshot = sessions.kduss(),
            uidSnapshot = sessions.current()?.uid.orEmpty(),
        )
    }

    private fun run(grade: Int) {
        val current = task ?: return
        if (job?.isActive == true) return

        job = viewModelScope.launch {
            _state.value = SearchUiState.Loading
            try {
                val result = withContext(Dispatchers.IO) { repository.search(current, grade) }
                if (isStale(current)) return@launch
                _state.value = SearchUiState.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchChallengeException) {
                if (isStale(current)) return@launch
                _state.value = SearchUiState.Challenge(e.validatedInfo, e.sid)
            } catch (e: SessionExpiredException) {
                _state.value = SearchUiState.NeedLogin(e.message ?: "登录已失效，请重新登录")
            } catch (e: Exception) {
                if (isStale(current)) return@launch
                _state.value = SearchUiState.Failure(e.message ?: "搜题失败，请重试")
            }
        }
    }

    /** 回调落地前核对：任务未过期，且 KDUSS / UID 与快照一致。 */
    private fun isStale(current: SearchTask): Boolean =
        !current.stillMatches(generation, sessions.kduss(), sessions.current()?.uid.orEmpty())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(container.searchRepository, container.sessions) as T
            }
    }
}
