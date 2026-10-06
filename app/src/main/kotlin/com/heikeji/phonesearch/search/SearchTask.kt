package com.heikeji.phonesearch.search

import com.heikeji.phonesearch.protocol.search.model.SearchMode

/**
 * 一次搜索的不可变上下文，对应原 `O0.l`。
 *
 * 验证挑战、重新登录和异步回调都必须携带完整任务，否则整页/框选请求会被错误降级成普通单题。
 *
 * 注意 [selectedBlockPosition] 与 [serviceBlockIndex] 是**两个**字段：
 * 前者是客户端列表位置，后者是 `pageExtraInfo.index` 要用的服务端下标。
 * 服务端数组可能稀疏或重排，两者不能合并。
 */
data class SearchTask(
    /** 每次新搜索递增；回调落地前用它确认结果没有过期。 */
    val generation: Long,
    /** 本次实际请求模式。 */
    val requestMode: SearchMode,
    /** 用户选择的模式（单题 / 整页），验证后恢复时要用它。 */
    val sourceMode: SearchMode,
    /** 选择图片后生成的整图 JPEG（SINGLE / PAGE 上传的就是它）。 */
    val originalSearchJpeg: ByteArray,
    /** 本次真正上传的字节（整图或裁剪图）。 */
    val uploadJpeg: ByteArray,
    /** 上传 JPEG 的像素尺寸，用于整页定位可用性判定。 */
    val uploadWidth: Int,
    val uploadHeight: Int,
    val kdussSnapshot: String,
    val uidSnapshot: String,
    /** 整页响应 sid；框选重搜时用于构造 pageExtraInfo。 */
    val wholeSearchSid: String = "",
    val selectedBlockPosition: Int = -1,
    val serviceBlockIndex: Int = -1,
    /** 8 个整页响应坐标。 */
    val selectedQuad: IntArray? = null,
    /**
     * 用户当前框选的归一化区域 `[left, top, right, bottom]`（基于所见图片，`[0,1]`）。
     * 构造 `pageExtraInfo.loc` 时映射到整页响应图片坐标系。
     */
    val selectedRectNormalized: FloatArray? = null,
) {
    /** 框选是否携带了有效的整页关联信息。 */
    val hasPageLink: Boolean
        get() = wholeSearchSid.isNotEmpty() && serviceBlockIndex >= 0

    /** 回调落地前核对：任务对象、图片、KDUSS、UID 都未变化。 */
    fun stillMatches(currentGeneration: Long, kduss: String, uid: String): Boolean =
        generation == currentGeneration &&
            kdussSnapshot == kduss &&
            uidSnapshot == uid

    override fun toString(): String =
        "SearchTask(gen=$generation, mode=$requestMode/$sourceMode, " +
            "upload=${uploadJpeg.size}B ${uploadWidth}x$uploadHeight, " +
            "sid=${wholeSearchSid.take(8)}, block=$serviceBlockIndex)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SearchTask) return false
        return generation == other.generation &&
            requestMode == other.requestMode &&
            sourceMode == other.sourceMode &&
            uploadJpeg.contentEquals(other.uploadJpeg) &&
            kdussSnapshot == other.kdussSnapshot &&
            uidSnapshot == other.uidSnapshot &&
            wholeSearchSid == other.wholeSearchSid &&
            serviceBlockIndex == other.serviceBlockIndex
    }

    override fun hashCode(): Int = generation.hashCode() * 31 + requestMode.hashCode()
}
