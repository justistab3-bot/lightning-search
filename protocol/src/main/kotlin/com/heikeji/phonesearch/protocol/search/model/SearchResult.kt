package com.heikeji.phonesearch.protocol.search.model

/**
 * 一次搜题的结果集合，对应原 P0.i。
 *
 * 结果页的页数 == items.size（每个 mainPageInfo 元素一页）。
 */
data class SearchResult(
    val items: List<AnswerItem>,
    val sid: String = "",
    val subject: String = "",
    /** 科目 ID（searchInfo.subjectId），AI 解题用。 */
    val subjectId: Int = 0,
    /** 图片 pid（picture.pid），AI 解题用。 */
    val pid: String = "",
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val pageCount: Int get() = items.size
}
