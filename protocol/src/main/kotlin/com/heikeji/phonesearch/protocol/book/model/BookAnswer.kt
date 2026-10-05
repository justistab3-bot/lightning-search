package com.heikeji.phonesearch.protocol.book.model

/**
 * 教辅答案书（「查看整本答案」）。
 *
 * 对应原生 `SearchBookSearch`，字段只保留本应用会用到的部分。
 */
data class BookSearchResult(
    val bookId: String,
    val name: String,
    val subject: String,
    val grade: String,
    val term: String,
    val version: String,
    val cover: String,
    /** 服务端标记是否有答案。 */
    val hasAnswer: Boolean,
    /** 答案页，按顺序。 */
    val pages: List<BookAnswerPage>,
) {
    val isEmpty: Boolean get() = pages.isEmpty()
}

/**
 * 一页答案。
 *
 * @param origin 原图 URL
 * @param thumbnail 缩略图 URL；缺失时退回 [origin]
 * @param width / [height] 原图像素尺寸，0 表示未知
 * @param isHd 服务端标记的高清图
 */
data class BookAnswerPage(
    val origin: String,
    val thumbnail: String,
    val width: Int,
    val height: Int,
    val isHd: Boolean,
) {
    /** 列表用的小图。 */
    val previewUrl: String get() = thumbnail.ifEmpty { origin }

    /** 点开看的大图。 */
    val fullUrl: String get() = origin.ifEmpty { thumbnail }
}

/** 题目关联的教材信息，用来发起「查看整本答案」。 */
data class RelatedBookInfo(
    /** 教材 id，`pagebookinfo` / `booksearch` 的入参。 */
    val bookId: String,
    /** 当前页 id。 */
    val pageId: String = "",
    /** 书名，界面直接显示。 */
    val bookName: String = "",
    /** 题目 id，`pagebookinfo` 的 `fromEtid` 用它。 */
    val tid: String = "",
) {
    val isUsable: Boolean get() = bookId.isNotEmpty() || pageId.isNotEmpty()
}
