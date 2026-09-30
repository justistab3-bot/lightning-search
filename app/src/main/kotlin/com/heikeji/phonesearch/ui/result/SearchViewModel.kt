package com.heikeji.phonesearch.ui.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.heikeji.phonesearch.AppContainer
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.net.SearchChallengeException
import com.heikeji.phonesearch.net.SessionExpiredException
import com.heikeji.phonesearch.protocol.model.SearchResult
import com.heikeji.phonesearch.search.SearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜题流程的状态机：发起请求、处理验证挑战、处理登录失效、可取消。
 *
 * 网络与解码都在 IO 线程；Activity 只负责把状态映射到界面。
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

    fun start(jpeg: ByteArray, grade: Int) {
        challengeLaunched = false
        launch { repository.search(jpeg, grade, sessions.kduss()) }
    }

    /** 验证完成后用同一张图重试。 */
    fun retryAfterVerification(jpeg: ByteArray, grade: Int) {
        challengeLaunched = false
        launch { repository.retryAfterVerification(jpeg, grade, sessions.kduss()) }
    }

    /** 从登录页回来后：有未完成的挑战就继续原题，否则重新搜。 */
    fun resumeAfterLogin(jpeg: ByteArray, grade: Int) {
        challengeLaunched = false
        if (repository.hasPendingChallenge()) {
            launch { repository.retryAfterVerification(jpeg, grade, sessions.kduss()) }
        } else {
            launch { repository.search(jpeg, grade, sessions.kduss()) }
        }
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

    private fun launch(block: suspend () -> SearchResult) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = SearchUiState.Loading
            try {
                val result = withContext(Dispatchers.IO) { block() }
                _state.value = SearchUiState.Success(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchChallengeException) {
                _state.value = SearchUiState.Challenge(e.validatedInfo, e.sid)
            } catch (e: SessionExpiredException) {
                _state.value = SearchUiState.NeedLogin(e.message ?: "登录已失效，请重新登录")
            } catch (e: Exception) {
                _state.value = SearchUiState.Failure(e.message ?: "搜题失败，请重试")
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(container.searchRepository, container.sessions) as T
            }
    }
}
