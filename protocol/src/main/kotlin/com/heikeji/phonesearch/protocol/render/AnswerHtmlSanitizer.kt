package com.heikeji.phonesearch.protocol.render

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
