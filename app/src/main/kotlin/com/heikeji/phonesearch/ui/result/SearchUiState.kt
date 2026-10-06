package com.heikeji.phonesearch.ui.result

import com.heikeji.phonesearch.protocol.search.model.SearchResult

/** 结果页的搜题状态机。 */
sealed interface SearchUiState {
    data object Idle : SearchUiState

    data object Loading : SearchUiState

    data class Success(val result: SearchResult) : SearchUiState

    /** 服务端要求官方安全验证。 */
    data class Challenge(val validatedInfo: String, val sid: String) : SearchUiState

    /** 登录态失效，需要重新登录（保留挑战，登录后继续原题）。 */
    data class NeedLogin(val message: String) : SearchUiState

    data class Failure(val message: String) : SearchUiState
}
