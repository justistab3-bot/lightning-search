package com.heikeji.phonesearch.protocol.book

import com.heikeji.phonesearch.protocol.ProtocolProfile

/**
 * 「查看整本答案」的请求构造。
 *
 * 对应原生 `SearchBookSearch.Input`（`/search/submit/booksearch`）。
 * 参数全部走加密 `data`，由 `ApiClient.postEncrypted` 负责 RC4 + Base64。
 */
object BookSearchRequest {

    const val PATH_BOOK_SEARCH = "/search/submit/booksearch"

    /**
     * @param bookId 教材 id（来自搜题结果的 relatedBook）
     * @param ticket 验证码票据；正常流程为空
     * @param randStr 验证码随机串；正常流程为空
     * @param isHitDayup 配置开关，默认 0
     * @param grade 年级
     * @param resolution `屏宽*屏高`，如 `1080*2340`
     */
    fun params(
        bookId: String,
        grade: Int,
        resolution: String,
        ticket: String = "",
        randStr: String = "",
        isHitDayup: Int = 0,
    ): LinkedHashMap<String, String?> {
        val params = LinkedHashMap<String, String?>()
        params["bookId"] = bookId
        params["ticket"] = ticket
        params["randStr"] = randStr
        // 原生硬编码为 0（root / 模拟器检测标记）
        params["isXposed"] = "0"
        params["isEmulator"] = "0"
        params["isHitDayup"] = isHitDayup.toString()
        params["grade"] = grade.toString()
        params["resolution"] = resolution
        return params
    }

    /** `1080*2340` 形式。 */
    fun resolution(widthPx: Int, heightPx: Int): String = "$widthPx*$heightPx"

    /** 教材信息接口（H5 点击按钮时先调它拿答案书元信息）。 */
    const val PATH_PAGE_BOOK_INFO = "/kdgrowth/search/pagebookinfo"

    fun pageBookInfoParams(bookId: String, pageId: String): LinkedHashMap<String, String?> {
        val params = LinkedHashMap<String, String?>()
        params["bookId"] = bookId
        params["pageId"] = pageId
        params["esource"] = ""
        params["queryType"] = "5"
        params["channel"] = ProtocolProfile.CHANNEL
        return params
    }
}
