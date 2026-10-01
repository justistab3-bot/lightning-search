package com.heikeji.phonesearch.ui.page

import com.heikeji.phonesearch.protocol.model.AnswerItem
import com.heikeji.phonesearch.protocol.model.PageQuestionBlock
import com.heikeji.phonesearch.protocol.model.PageSearchResult

/**
 * 整页结果页状态。
 *
 * 两级选择：整页中的题块 -> 一个题块内的候选答案。
 */
data class PageUiState(
    val loading: Boolean = false,
    /** 整页响应。 */
    val result: PageSearchResult? = null,
    /** 客户端列表位置。 */
    val selectedBlockPosition: Int = 0,
    val selectedCandidatePosition: Int = 0,
    /** 按 serviceIndex 缓存的框选精搜结果。 */
    val refined: Map<Int, List<AnswerItem>> = emptyMap(),
    /** 一次性提示（失败原因、定位不可用等）。 */
    val message: String? = null,
    val needLogin: Boolean = false,
) {
    val blocks: List<PageQuestionBlock> get() = result?.blocks.orEmpty()

    val selectedBlock: PageQuestionBlock?
        get() = blocks.getOrNull(selectedBlockPosition)

    /**
     * 当前题块的候选：优先用框选精搜缓存，否则用整页响应自带的候选。
     * 对齐原实现「先按 serviceIndex 查精搜缓存」的顺序。
     */
    val candidates: List<AnswerItem>
        get() {
            val block = selectedBlock ?: return emptyList()
            return refined[block.serviceIndex] ?: block.candidates
        }

    val selectedCandidate: AnswerItem?
        get() = candidates.getOrNull(selectedCandidatePosition)

    val isRefined: Boolean
        get() = selectedBlock?.let { refined.containsKey(it.serviceIndex) } == true
}
