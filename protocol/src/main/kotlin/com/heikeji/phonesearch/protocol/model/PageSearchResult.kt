package com.heikeji.phonesearch.protocol.model

import com.heikeji.phonesearch.protocol.book.model.RelatedBookInfo

/**
 * 整页搜题结果，对应原 `P0.f`。
 *
 * 注意：原反编译构造器接收了 subject 却没有保存（见交接文档 §13.1），这里正常保存。
 */
data class PageSearchResult(
    val sid: String,
    val subject: String,
    val pictureWidth: Int,
    val pictureHeight: Int,
    /** 题框定位是否可用；false 时答案照常显示，只是没有可点击的定位框。 */
    val positioningAvailable: Boolean,
    /** 定位不可用的原因；可用时为空。 */
    val positioningWarning: String,
    val blocks: List<PageQuestionBlock>,
    /**
     * 教材信息，用于「查看整本答案」。
     * 服务端没给（或这页不是教辅）时为 null，入口按钮不显示。
     */
    val relatedBook: RelatedBookInfo? = null,
) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

/**
 * 一个整页题块，对应原 `P0.d`。
 *
 * @param serviceIndex `pageExtraInfo.index` 必须用这个值，**不是**客户端列表位置。
 *   服务端数组可能稀疏或重排，两者不可合并。
 */
data class PageQuestionBlock(
    val serviceIndex: Int,
    val candidates: List<AnswerItem>,
    val location: QuestionQuad?,
    val angle: Int,
    val warning: String,
) {
    val hasAnswer: Boolean get() = candidates.isNotEmpty()
}

/** 整页解析过程中可能出现的提示文案（对齐原实现）。 */
object PageWarnings {
    const val SIZE_UNKNOWN = "图片尺寸未确认，定位不可用"
    const val SIZE_MISMATCH = "返回图片尺寸与上传图片不同，定位不可用"
    const val CROPPED_OR_ROTATED = "服务返回的图片已裁剪或旋转，定位不可用"
    const val NO_LOCATION = "未返回题框位置"
    const val LOCATION_INVALID = "题框位置不可用"
    const val ANGLE_INVALID = "题框角度不可用"
    const val NO_ANSWER = "该题未返回可显示的答案"
}
