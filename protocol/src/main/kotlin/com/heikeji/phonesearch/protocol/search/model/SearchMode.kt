package com.heikeji.phonesearch.protocol.search.model

/**
 * 搜索请求模式，对应原 `O0.l.f686a`。
 *
 * | 值 | 模式 | 上传内容 | 端点 |
 * |---:|---|---|---|
 * | 1 | 普通单题 | 预处理后的整张图 | singlesearch |
 * | 2 | 整页搜题 | 预处理后的整张图 | pagesearch |
 * | 3 | 框选重搜 | 从原图局部解码的新 JPEG | singlesearch |
 */
enum class SearchMode(val wireValue: Int) {
    SINGLE(1),
    PAGE(2),
    CROP_SINGLE(3),
    ;

    /** 是否上传整张原图（决定用哪套图片预处理参数）。 */
    val uploadsWholeImage: Boolean get() = this != CROP_SINGLE

    companion object {
        fun fromWire(value: Int): SearchMode? = entries.firstOrNull { it.wireValue == value }
    }
}
