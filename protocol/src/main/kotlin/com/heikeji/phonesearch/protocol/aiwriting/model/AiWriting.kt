package com.heikeji.phonesearch.protocol.aiwriting.model

/**
 * AI 作文的写作模式，对应 H5 的 `pageType`。
 *
 * 端点映射来自页面 JS 里的那张表：
 * ```
 * aiwrite_quick        -> preInstantWriting / instantWriting
 * aiwrite_thinking     -> writingThought/preInstantWriting / writingThought/instantWriting
 * aiwrite_thinking_map -> 同 aiwrite_thinking
 * aiwrite_para         -> writingParagraph/preInstantWriting / writingParagraph/instantWriting
 * english              -> preEngInstantWriting / engInstantWriting
 * ```
 */
enum class WritingMode(val pageType: String, val label: String) {
    /** 快速写作：直接出全文。 */
    QUICK("aiwrite_quick", "快速写作"),

    /** 思路写作：先出提纲，再按提纲成文。 */
    THINKING("aiwrite_thinking", "先列提纲"),

    /** 思路导图：同上，前端用导图呈现。 */
    THINKING_MAP("aiwrite_thinking_map", "思维导图"),

    /** 分段写作。 */
    PARAGRAPH("aiwrite_para", "分段写作"),

    /** 英语作文。 */
    ENGLISH("english", "英语作文"),
    ;

    /** 是否需要先经过一次提纲生成。 */
    val needsThought: Boolean
        get() = this == THINKING || this == THINKING_MAP || this == PARAGRAPH
}

/** 文章的一个自然段。 */
data class AiParagraph(
    val id: String,
    val content: String,
    val paraIndex: Int,
)

/** 一次生成的完整文章。 */
data class AiArticle(
    val title: String,
    val queryType: String,
    val articleType: String,
    val wordCount: Int,
    val sid: String,
    val sessionId: String,
    val paragraphs: List<AiParagraph>,
    /**
     * 正文首段如果是 markdown 标题（`# xxx`），抽出来放这里，并从 [paragraphs] 里移除。
     *
     * 服务端有时会把标题当成正文第一段返回，直接显示会出现字面的 `#`。
     */
    val heading: String = "",
) {
    val text: String get() = paragraphs.joinToString("\n") { it.content }

    val isEmpty: Boolean get() = paragraphs.none { it.content.isNotBlank() }

    /** 正文之外要单独显示的标题：优先用抽出来的 markdown 标题。 */
    val displayTitle: String get() = heading.ifEmpty { title }
}

/** 提纲节点（思路写作的产物）。 */
data class AiThoughtNode(
    val id: String,
    val title: String,
    val content: String,
    val children: List<AiThoughtNode> = emptyList(),
)

/** 提纲。 */
data class AiThought(
    val sid: String,
    val title: String,
    val queryType: String,
    val nodes: List<AiThoughtNode>,
)

/**
 * 流式过程中从 SSE 里解析出来的东西。
 */
sealed interface AiWritingEvent {

    /** 增量文本（打字机效果用）。 */
    data class Delta(val text: String) : AiWritingEvent

    /** 结束：带完整分段文章。 */
    data class Finished(val article: AiArticle) : AiWritingEvent

    /** 服务端主动关闭。 */
    data object Closed : AiWritingEvent

    /** 无法识别的事件（保留原始 data 便于排查）。 */
    data class Unknown(val event: String, val data: String) : AiWritingEvent
}
