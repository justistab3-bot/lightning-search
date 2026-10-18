package com.heikeji.phonesearch.protocol.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨标签公式合并的回归测试。
 *
 * 服务端 HTML 有时会把一条 LaTeX 公式用 `<br>` / `<span>` 等行内标签切断，
 * 之前 LatexRenderer 只在单个文本节点内工作，切断的公式会原样露出来
 * （线上反馈的「公式没有正确解析」）。清洗阶段的 DOM 合并负责把它们拼回去。
 */
class AnswerHtmlSanitizerTest {

    /** 完整管线：清洗（含合并）→ 公式排版。 */
    private fun pipeline(html: String): String =
        LatexRenderer.renderInHtml(AnswerHtmlSanitizer.clean(html))

    @Test
    fun `formula split by br is merged back`() {
        val html = pipeline("<p>\\[ x^2 +<br> 2x + 1 \\]</p>")
        assertTrue(html, html.contains("class=\"math\""))
        assertFalse("公式应该被拼成一个节点，不再残留 <br>", html.contains("<br>"))
    }

    @Test
    fun `formula split by inline spans is merged back`() {
        val html = pipeline("<p>\\( a + <span>b \\times</span> c \\)</p>")
        assertTrue(html, html.contains("class=\"math\""))
        assertFalse(html, html.contains("<span>b"))
    }

    @Test
    fun `text before the formula survives the merge`() {
        val html = pipeline("<p><b>已知</b> \\( x + <span>1 \\)</span> 成立</p>")
        assertTrue("公式前的粗体要保留", html.contains("<b>已知</b>"))
        assertTrue(html, html.contains("class=\"math\""))
        assertTrue("公式后的文字要保留", html.contains("成立"))
    }

    @Test
    fun `formula inside one node is untouched`() {
        val html = pipeline("<p>速度为 \\( v_0 \\) 米每秒</p>")
        assertTrue(html, html.contains("v<sub>0</sub>"))
        assertTrue(html, html.contains("速度为"))
    }

    @Test
    fun `unclosed delimiters are left alone`() {
        val html = AnswerHtmlSanitizer.clean("<p>价格 \\( 100 元<br>继续</p>")
        // 没有配对的 \\( ：不能把后续内容吞进公式，<br> 应当保留
        assertTrue(html, html.contains("<br>"))
    }

    @Test
    fun `code blocks are not merged`() {
        val html = AnswerHtmlSanitizer.clean("<pre>\\[ a +<br>b \\]</pre>")
        assertTrue("代码块里的内容不应被公式合并搬动", html.contains("<br>"))
    }

    @Test
    fun `dollar math across br is merged`() {
        val html = pipeline("<p>\$a^2 +<br>b^2\$</p>")
        assertTrue(html, html.contains("class=\"math\""))
    }

    @Test
    fun `plain html without math is unchanged`() {
        val html = AnswerHtmlSanitizer.clean("<p>今天天气不错，<br>出来玩吧</p>")
        assertTrue(html, html.contains("今天天气不错"))
        assertTrue(html, html.contains("<br>"))
    }
}
