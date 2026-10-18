package com.heikeji.phonesearch.ui.result

import com.heikeji.phonesearch.protocol.search.model.SearchResult

/** 结果页的搜题状态机。 */
sealed interface SearchUiState {
    data object Idle : SearchUiState

    data object Loading : SearchUiState

    data class Success(val result: SearchResult) : SearchUiState

    /** 服务端要求官方安全验证。 */
    data class Challenge(val validatedInfo: String, val sid: String) : SearchUiState

    /** 搜题服务要求登录或会话失效；由用户点击按钮登录后继续原题。 */
    data class NeedLogin(val message: String) : SearchUiState

    data class Failure(val message: String) : SearchUiState
}
