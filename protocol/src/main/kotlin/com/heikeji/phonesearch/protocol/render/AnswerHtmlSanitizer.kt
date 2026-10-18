package com.heikeji.phonesearch.protocol.render

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

/**
 * 答案 HTML 白名单清洗（原 Q0.a）。
 *
 * 白名单与原实现一致：
 * - 标签：p br div span b strong i em u s sup sub ol ul li table thead tbody tr td th blockquote pre code img
 * - 属性：img[src,alt]、td[colspan,rowspan]
 * - `img[src]` 只允许 `https`
 *
 * 清洗前先把 `<img src>` 的 `//host/...` 补成 `https://host/...`、
 * `http://host/...` 升级成 `https://host/...`（原实现同一位置的行为）。
 *
 * 另外会做一步**公式合并**：服务端的 HTML 有时会把一条 LaTeX 公式用 `<br>`
 * 或 `<span>` 等行内标签拦腰切断（比如 `\[ x \]` 中间插了个换行标签）。
 * 后续的 [LatexRenderer] 只在单个文本节点内工作，切断的公式就会原样漏出来
 * （用户看到的就是"公式没有正确解析"）。这里在清洗前的 DOM 阶段，
 * 把被行内标签切断的公式拼回一个文本节点，公式恢复完整后交给正常链路。
 *
 * 与原文的差异：原实现另外把每个 `<img>` 包进 `<a href>` 以便点开大图；
 * 手机版改用 WebView 自带的缩放，不包 `<a>`，避免点击触发导航。
 */
object AnswerHtmlSanitizer {

    private val SAFELIST: Safelist = Safelist()
        .addTags(
            "p", "br", "div", "span", "b", "strong", "i", "em", "u", "s",
            "sup", "sub", "ol", "ul", "li", "table", "thead", "tbody", "tr", "td", "th",
            "blockquote", "pre", "code", "img", "a",
        )
        .addAttributes("img", "src", "alt")
        .addAttributes("a", "href")
        .addAttributes("td", "colspan", "rowspan")
        .addProtocols("img", "src", "https")
        .addProtocols("a", "href", "https")

    fun clean(html: String?): String {
        if (html.isNullOrEmpty()) return ""

        val document = Jsoup.parse(html)
        normalizeImageSources(document)
        mergeSplitFormulas(document)

        val body = document.body().html()
        val cleaned = Jsoup.clean(body, "", SAFELIST, Document.OutputSettings().prettyPrint(false))
        return Jsoup.parse(cleaned).body().html()
    }

    private fun normalizeImageSources(document: Document) {
        for (img in document.select("img")) {
            val src = img.attr("src")
            when {
                src.startsWith("//") -> img.attr("src", "https:$src")
                src.startsWith("http://") -> img.attr("src", "https://" + src.removePrefix("http://"))
            }
        }
    }

    // ------------------------------------------------------------------ 跨标签公式合并

    /** 可以拼进公式文本的行内标签；其余标签（p/div/table/img…）视为公式边界。 */
    private val JOINABLE_TAGS =
        setOf("span", "b", "strong", "i", "em", "u", "s", "sup", "sub", "a", "br", "small", "big")

    /** 公式文本不该在代码块里被搬动（示例代码里的 `$` 不是公式）。 */
    private val NO_MERGE_TAGS = setOf("pre", "code")

    private fun mergeSplitFormulas(root: Node) {
        if (root is Element) {
            if (root.tagName() in NO_MERGE_TAGS) return
            var merged: Boolean
            do {
                merged = tryMergeOnce(root)
            } while (merged)
        }
        for (child in ArrayList(root.childNodes())) {
            mergeSplitFormulas(child)
        }
    }

    /** 节点文本是否可以参与公式拼接。 */
    private fun isJoinable(node: Node): Boolean = when (node) {
        is TextNode -> true
        is Element -> node.tagName() in JOINABLE_TAGS
        else -> false
    }

    /** 节点贡献给拼接文本的内容：文本节点原样、`<br>` 当换行、行内元素取全部文字。 */
    private fun joinText(node: Node): String = when (node) {
        is TextNode -> node.wholeText
        is Element -> if (node.tagName() == "br") "\n" else node.text()
        else -> ""
    }

    /**
     * 在 [parent] 的直接子节点里找一条**跨节点**的完整公式，找到就拼回单个文本节点。
     * 返回是否发生了合并（调用方循环调用直到没有为止）。
     */
    private fun tryMergeOnce(parent: Element): Boolean {
        val children = parent.childNodes().toList()
        if (children.size < 2) return false

        var i = 0
        while (i < children.size) {
            if (!isJoinable(children[i])) {
                i++
                continue
            }
            val runStart = i
            while (i < children.size && isJoinable(children[i])) i++
            val run = children.subList(runStart, i)
            if (run.size >= 2 && tryMergeRun(parent, run)) return true
        }
        return false
    }

    private class NodeSpan(val node: Node, val start: Int, val end: Int)

    private fun tryMergeRun(parent: Element, run: List<Node>): Boolean {
        val sb = StringBuilder()
        val spans = ArrayList<NodeSpan>(run.size)
        for (node in run) {
            val text = joinText(node)
            spans.add(NodeSpan(node, sb.length, sb.length + text.length))
            sb.append(text)
        }
        val text = sb.toString()

        var idx = 0
        while (idx < text.length) {
            val open = when {
                text.startsWith("$$", idx) -> "$$"
                text[idx] == '$' -> "$"
                text.startsWith("\\(", idx) -> "\\("
                text.startsWith("\\[", idx) -> "\\["
                else -> {
                    idx++
                    continue
                }
            }
            val close = when (open) {
                "\\(" -> "\\)"
                "\\[" -> "\\]"
                else -> open
            }
            val closeStart = text.indexOf(close, idx + open.length)
            if (closeStart == -1) {
                // 定界符不完整：跟 LatexRenderer 一样当作普通文本，跳过这个定界符
                idx += open.length
                continue
            }
            val formulaEnd = closeStart + close.length

            val firstIdx = spans.indexOfFirst { it.end > idx }
            val lastIdx = spans.indexOfLast { it.start < formulaEnd }
            if (firstIdx < 0 || lastIdx < 0 || firstIdx == lastIdx) {
                idx = formulaEnd
                continue
            }

            // 公式跨节点：先把两端没对齐的节点处理掉（拆文本 / 展平行内元素）
            val first = spans[firstIdx]
            if (first.start < idx) {
                when (val node = first.node) {
                    is TextNode -> node.splitText(idx - first.start)
                    is Element -> replaceNode(parent, node, TextNode(node.text()))
                }
                return true
            }
            val last = spans[lastIdx]
            if (last.end > formulaEnd) {
                when (val node = last.node) {
                    is TextNode -> node.splitText(formulaEnd - last.start)
                    is Element -> replaceNode(parent, node, TextNode(node.text()))
                }
                return true
            }

            // 两端已对齐：把覆盖到的节点整体替换成一条公式文本
            val formula = text.substring(idx, formulaEnd)
            val anchor = run[firstIdx]
            val insertAt = parent.childNodes().indexOf(anchor)
            for (j in firstIdx..lastIdx) {
                run[j].remove()
            }
            parent.insertChildren(insertAt.coerceAtMost(parent.childNodes().size), TextNode(formula))
            return true
        }
        return false
    }

    private fun replaceNode(parent: Element, old: Node, replacement: Node) {
        val index = parent.childNodes().indexOf(old)
        old.remove()
        parent.insertChildren(index.coerceAtMost(parent.childNodes().size), replacement)
    }

    /** HTML 转义，用于把纯文本塞进 HTML。 */
    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * 把裸 `<img>` 包进 `<a href="src" class="zoom">`。
     *
     * 这是**不用 JavaScript** 实现「点图片看大图」的办法：点击锚点会走
     * `WebViewClient.shouldOverrideUrlLoading`，原生侧拦截后打开自己的图片查看器。
     * （原 APK 的 Q0.a.c 也是这么做的。）
     */
    fun wrapImagesWithLinks(html: String): String {
        if (!html.contains("<img")) return html
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false)
        for (img in document.select("img")) {
            if (img.parent()?.tagName() == "a") continue
            val src = img.attr("src")
            if (!src.startsWith("https://")) continue
            img.wrap("<a href=\"" + escape(src) + "\" class=\"zoom\"></a>")
        }
        return document.body().html()
    }
}
