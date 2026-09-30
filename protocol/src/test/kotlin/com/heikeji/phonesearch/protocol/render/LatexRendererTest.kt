package com.heikeji.phonesearch.protocol.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 渲染器的最小回归测试。
 *
 * 这部分逻辑没有 JS 兜底（答案页禁用 JavaScript），一旦转换错了用户在页面上直接看到乱码，
 * 所以固定几个真实写法做断言。
 */
class LatexRendererTest {

    /** 包一层行内公式定界符，避免 Kotlin 字符串模板吃掉 `$`。 */
    private fun m(body: String): String = "\$" + body + "\$"

    @Test
    fun `renders dfrac as a fraction`() {
        val html = LatexRenderer.renderInHtml("求磁感应强度 \\(B=\\dfrac{F}{IL}\\) 的大小")
        assertTrue(html, html.contains("class=\"math\""))
        assertTrue(html, html.contains("class=\"frac\""))
        assertTrue(html, html.contains("<span class=\"num\">F</span>"))
        assertTrue(html, html.contains("<span class=\"den\">IL</span>"))
        assertTrue(html, html.contains("求磁感应强度"))
        assertTrue(html, html.contains("的大小"))
    }

    @Test
    fun `renders superscript subscript and sqrt`() {
        assertEquals(
            "<span class=\"math\">x<sup>2</sup></span>",
            LatexRenderer.renderInHtml(m("x^2")),
        )
        assertEquals(
            "<span class=\"math\">a<sub>1</sub></span>",
            LatexRenderer.renderInHtml(m("a_1")),
        )
        val sqrt = LatexRenderer.renderInHtml(m("\\sqrt{2}"))
        assertTrue(sqrt, sqrt.contains("class=\"sqrt\""))
        assertTrue(sqrt, sqrt.contains("<span class=\"rad\">2</span>"))
    }

    @Test
    fun `renders greek letters and operators`() {
        val html = LatexRenderer.renderInHtml(m("\\alpha+\\beta\\times\\pi\\leq\\infty"))
        assertTrue(html, html.contains("α"))
        assertTrue(html, html.contains("β"))
        assertTrue(html, html.contains("×"))
        assertTrue(html, html.contains("π"))
        assertTrue(html, html.contains("≤"))
        assertTrue(html, html.contains("∞"))
    }

    @Test
    fun `renders functions and text groups`() {
        val html = LatexRenderer.renderInHtml(
            m("\\sin\\theta=\\dfrac{\\text{对边}}{\\text{斜边}}"),
        )
        assertTrue(html, html.contains("sin"))
        assertTrue(html, html.contains("θ"))
        assertTrue(html, html.contains("对边"))
        assertTrue(html, html.contains("斜边"))
    }

    @Test
    fun `leaves plain text untouched`() {
        val text = "<p>这道题考查牛顿第二定律</p>"
        assertEquals(text, LatexRenderer.renderInHtml(text))
    }

    @Test
    fun `does not touch html tags and keeps them intact`() {
        val html = LatexRenderer.renderInHtml("<p>速度为 " + m("v_0") + " 米每秒</p>")
        assertTrue(html, html.startsWith("<p>"))
        assertTrue(html, html.endsWith("</p>"))
        assertTrue(html, html.contains("v<sub>0</sub>"))
    }

    @Test
    fun `normalises fullwidth backslash`() {
        val html = LatexRenderer.renderInHtml(m("\uFF3Cdfrac{a}{b}"))
        assertTrue(html, html.contains("class=\"frac\""))
        assertTrue(html, html.contains("<span class=\"num\">a</span>"))
    }

    @Test
    fun `unknown commands keep their name instead of vanishing`() {
        val html = LatexRenderer.renderInHtml(m("\\weirdcmd{x}"))
        assertTrue(html, html.contains("weirdcmd"))
        assertFalse(html, html.contains("\\weirdcmd"))
    }

    @Test
    fun `unclosed delimiter is left alone`() {
        val text = "价格为 " + "\$" + "100 元"
        assertEquals(text, LatexRenderer.renderInHtml(text))
    }

    @Test
    fun `produces balanced span tags`() {
        val html = LatexRenderer.renderInHtml(
            m("\\dfrac{\\sqrt{a^2+b^2}}{\\text{斜边}}\\times\\alpha_1"),
        )
        val open = Regex("<span").findAll(html).count()
        val close = Regex("</span>").findAll(html).count()
        assertEquals(html, open, close)
    }

    @Test
    fun `renders nested fractions inside roots`() {
        val html = LatexRenderer.renderInHtml(m("\\sqrt{\\dfrac{a}{b}}"))
        assertTrue(html, html.contains("class=\"sqrt\""))
        assertTrue(html, html.contains("class=\"frac\""))
    }

    @Test
    fun `supports display math delimiters`() {
        val html = LatexRenderer.renderInHtml("\\[E=mc^2\\]")
        assertTrue(html, html.contains("class=\"math\""))
        assertTrue(html, html.contains("m<sup>2</sup>") || html.contains("c<sup>2</sup>"))
    }
}
