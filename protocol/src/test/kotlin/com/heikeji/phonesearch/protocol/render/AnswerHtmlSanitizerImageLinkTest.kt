package com.heikeji.phonesearch.protocol.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片点击放大依赖「把 `<img>` 包进 `<a href>`」这一步——答案页禁用 JavaScript，
 * 点击事件只能靠锚点走 `shouldOverrideUrlLoading`。这里锁住包裹结果。
 */
class AnswerHtmlSanitizerImageLinkTest {

    @Test
    fun `wraps a bare image in an anchor`() {
        val html = AnswerHtmlSanitizer.wrapImagesWithLinks(
            "<p><img src=\"https://img.zuoyebang.cc/a.jpg\" alt=\"题目图片\"></p>",
        )
        assertTrue(html, html.contains("<a href=\"https://img.zuoyebang.cc/a.jpg\" class=\"zoom\">"))
        assertTrue(html, html.contains("<img src=\"https://img.zuoyebang.cc/a.jpg\""))
        // 锚点必须包住 img，而不是并列
        assertTrue(html, Regex("<a [^>]*>\\s*<img").containsMatchIn(html))
    }

    @Test
    fun `does not double wrap an image already inside an anchor`() {
        val source = "<a href=\"https://img.zuoyebang.cc/a.jpg\">" +
            "<img src=\"https://img.zuoyebang.cc/a.jpg\"></a>"
        val html = AnswerHtmlSanitizer.wrapImagesWithLinks(source)
        assertEquals(1, Regex("<a ").findAll(html).count())
    }

    @Test
    fun `ignores non https images`() {
        val html = AnswerHtmlSanitizer.wrapImagesWithLinks("<img src=\"http://evil.test/a.jpg\">")
        assertFalse(html, html.contains("<a "))
    }

    @Test
    fun `leaves html without images untouched`() {
        val text = "<p>这道题考查牛顿第二定律</p>"
        assertEquals(text, AnswerHtmlSanitizer.wrapImagesWithLinks(text))
    }

    @Test
    fun `sanitizer keeps https anchors but drops other protocols`() {
        val kept = AnswerHtmlSanitizer.clean("<a href=\"https://a.test/x\">看这里</a>")
        assertTrue(kept, kept.contains("href=\"https://a.test/x\""))

        val dropped = AnswerHtmlSanitizer.clean("<a href=\"javascript:alert(1)\">点我</a>")
        assertFalse(dropped, dropped.contains("javascript:"))
    }

    @Test
    fun `renderer output contains a clickable image anchor`() {
        val item = com.heikeji.phonesearch.protocol.model.AnswerItem(
            index = 1,
            title = "结果 1",
            subject = "物理",
            answerHtml = "",
            analysisHtml = "",
            questionHtml = "",
            answerImages = listOf("https://img.zuoyebang.cc/answer.png"),
            questionImages = emptyList(),
            rawHtml = null,
        )
        val page = AnswerPageRenderer.render(item, index = 1, total = 1)
        assertTrue(page, page.contains("class=\"zoom\""))
        assertTrue(page, page.contains("https://img.zuoyebang.cc/answer.png"))
    }
}
