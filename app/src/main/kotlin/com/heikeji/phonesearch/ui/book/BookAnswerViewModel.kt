package com.heikeji.phonesearch.ui.book

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.protocol.book.BookSearchParser
import com.heikeji.phonesearch.protocol.book.BookSearchRequest
import com.heikeji.phonesearch.protocol.book.model.BookAnswerPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「查看整本答案」页的状态。 */
data class BookUiState(
    val loading: Boolean = false,
    val failed: Boolean = false,
    /** 失败原因，直接展示给用户，便于定位（不再吞掉真实错误）。 */
    val error: String = "",
    val title: String = "",
    val subtitle: String = "",
    val pages: List<BookAnswerPage> = emptyList(),
    val index: Int = 0,
) {
    val total: Int get() = pages.size
    val current: BookAnswerPage? get() = pages.getOrNull(index)
}

/**
 * 教辅答案书。
 *
 * 走 `/search/submit/booksearch`，参数加密方式与登录接口一致（见 `ApiClient.searchBook`）。
 */
class BookAnswerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    private val _state = MutableStateFlow(BookUiState())
    val state: StateFlow<BookUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** @param grade 用户年级；没有就传 0，服务端按未知处理 */
    fun load(bookId: String, grade: Int) {
        if (bookId.isEmpty()) {
            _state.value = BookUiState(failed = true, error = "没有拿到教材 id")
            return
        }
        job?.cancel()
        _state.value = BookUiState(loading = true)

        job = viewModelScope.launch {
            try {
                val resolution = resolutionOf()
                val json = withContext(Dispatchers.IO) {
                    container.apiClient.searchBook(bookId, grade, resolution)
                }
                val key = withContext(Dispatchers.IO) { container.protocol.responseKey() }
                val result = withContext(Dispatchers.Default) {
                    BookSearchParser.parseJson(json, key)
                }

                _state.value = if (result.pages.isEmpty()) {
                    BookUiState(
                        failed = true,
                        error = "接口通了，但这本书没有答案页（bookId=$bookId）",
                    )
                } else {
                    BookUiState(
                        loading = false,
                        title = result.name.ifEmpty { "教材答案" },
                        subtitle = subtitleOf(result.subject, result.grade, result.version, result.term),
                        pages = result.pages,
                        index = 0,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = BookUiState(
                    failed = true,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun onPageChanged(index: Int) {
        val total = _state.value.total
        if (total == 0) return
        _state.value = _state.value.copy(index = index.coerceIn(0, total - 1))
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }

    /** 原生传的是 `屏宽*屏高`。 */
    private fun resolutionOf(): String {
        val metrics = getApplication<Application>().resources.displayMetrics
        return BookSearchRequest.resolution(metrics.widthPixels, metrics.heightPixels)
    }

    private fun subtitleOf(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }.joinToString(" · ")
}
