package com.heikeji.phonesearch.protocol.model

/**
 * 单条答案结果，对应 answers.mainPageInfo 的一个元素。
 *
 * 字段对应原 P0.h：
 *  - title          -> "结果 N"（本应用改为「第 N 条」由 UI 决定）
 *  - questionHtml   -> question.content
 *  - questionImages -> question.picList 解析出的 https 图片地址
 *  - answerHtml     -> answer[0].content
 *  - answerImages   -> answer[0].picList
 *  - analysisHtml   -> subjectAnalysis 或 analysis.subjectAnalysis.content
 *  - subject        -> courseName（缺失时回退到 searchInfo.subjectName）
 *  - rawHtml        -> 整个结果是 HTML 时的原始内容，非 null 时其他字段为空
 */
data class AnswerItem(
    val index: Int,
    val title: String,
    val questionHtml: String,
    val questionImages: List<String>,
    val answerHtml: String,
    val answerImages: List<String>,
    val analysisHtml: String,
    val subject: String,
    val rawHtml: String?,
    /** 该题的加密题目编号（answers.tids[i]），AI 解题用（官方叫 etid）。 */
    val tid: String = "",
) {
    val hasQuestion: Boolean get() = questionHtml.isNotBlank() || questionImages.isNotEmpty()
    val hasAnswer: Boolean get() = answerHtml.isNotBlank() || answerImages.isNotEmpty()
    val hasAnalysis: Boolean get() = analysisHtml.isNotBlank()

    /** 是否完全没有可展示内容。 */
    val isEmpty: Boolean
        get() = !hasQuestion && !hasAnswer && !hasAnalysis && rawHtml.isNullOrBlank()
}
