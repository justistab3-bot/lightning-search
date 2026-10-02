package com.heikeji.phonesearch.protocol.render

/**
 * 把答案 HTML 里的 LaTeX 公式转成纯 HTML + CSS 排版。
 *
 * **刻意不引入 MathJax/KaTeX**：答案 WebView 是禁用 JavaScript 的（安全约束），
 * 而且从网络加载脚本会破坏 CSP。这里用一个小型 LaTeX 子集渲染器覆盖 K12 常见写法：
 * 分式、根式、上下标、希腊字母、常用运算符与函数名。
 *
 * 支持的定界符：`$...$`、`$$...$$`、`\(...\)`、`\[...\]`。
 * 无法识别的命令会原样输出命令名，不会丢内容。
 *
 * 输入必须是**已清洗过的 HTML**：这里只在标签之外的文本段上工作，
 * 生成的标签全部由本类自己产生，因此不会再引入不可信标记。
 */
object LatexRenderer {

    fun renderInHtml(html: String): String {
        if (!html.contains('$') && !html.contains("\\(") && !html.contains("\\[")) return html

        val sb = StringBuilder(html.length + 64)
        var index = 0
        while (index < html.length) {
            val lt = html.indexOf('<', index)
            if (lt == -1) {
                sb.append(renderTextSegment(html.substring(index)))
                break
            }
            if (lt > index) sb.append(renderTextSegment(html.substring(index, lt)))
            val gt = html.indexOf('>', lt)
            if (gt == -1) {
                sb.append(html, lt, html.length)
                break
            }
            sb.append(html, lt, gt + 1)
            index = gt + 1
        }
        return sb.toString()
    }

    /** 渲染一段纯文本（可能含公式）。非公式部分原样返回，公式部分转成 HTML。 */
    private fun renderTextSegment(text: String): String {
        if (text.isEmpty()) return text
        val segments = split(text)
        if (segments.none { it.isMath }) return text
        val sb = StringBuilder(text.length + 32)
        for (segment in segments) {
            if (segment.isMath) {
                val latex = unescape(segment.content)
                // data-tex 保留原始 LaTeX：答案页的 KaTeX 会用它重新渲染一遍；
                // KaTeX 加载失败时，下面这段 HTML 就是兜底排版。
                sb.append("<span class=\"math\" data-tex=\"")
                    .append(escapeAttribute(latex))
                    .append("\">")
                    .append(renderMath(latex))
                    .append("</span>")
            } else {
                sb.append(segment.content)
            }
        }
        return sb.toString()
    }

    private class Segment(val isMath: Boolean, val content: String)

    private fun split(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        var i = 0
        var plainStart = 0
        while (i < text.length) {
            val c = text[i]
            val open: String
            val close: String
            when {
                c == '$' && i + 1 < text.length && text[i + 1] == '$' -> {
                    open = "$$"; close = "$$"
                }
                c == '$' -> {
                    open = "$"; close = "$"
                }
                c == '\\' && i + 1 < text.length && text[i + 1] == '(' -> {
                    open = "\\("; close = "\\)"
                }
                c == '\\' && i + 1 < text.length && text[i + 1] == '[' -> {
                    open = "\\["; close = "\\]"
                }
                else -> {
                    i++
                    continue
                }
            }
            val start = i + open.length
            val end = text.indexOf(close, start)
            if (end == -1) {
                i += open.length
                continue
            }
            if (i > plainStart) result.add(Segment(false, text.substring(plainStart, i)))
            result.add(Segment(true, text.substring(start, end)))
            i = end + close.length
            plainStart = i
        }
        if (plainStart < text.length) result.add(Segment(false, text.substring(plainStart)))
        return result
    }

    // ------------------------------------------------------------------ LaTeX 解析

    fun renderMath(latex: String): String =
        MathParser(latex.replace('\uFF3C', '\\')).parseAll()

    private class MathParser(private val src: String) {
        private var pos = 0

        fun parseAll(): String = parseSequence(stopAtBrace = false)

        private fun parseSequence(stopAtBrace: Boolean): String {
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos]
                if (stopAtBrace && c == '}') {
                    pos++
                    return sb.toString()
                }
                when (c) {
                    '\\' -> sb.append(parseCommand())
                    '{' -> {
                        pos++
                        sb.append(parseSequence(stopAtBrace = true))
                    }
                    '}' -> pos++
                    '^' -> {
                        pos++
                        sb.append("<sup>").append(parseAtom()).append("</sup>")
                    }
                    '_' -> {
                        pos++
                        sb.append("<sub>").append(parseAtom()).append("</sub>")
                    }
                    '&' -> {
                        pos++
                        sb.append(' ')
                    }
                    '~' -> {
                        pos++
                        sb.append("&nbsp;")
                    }
                    '\n', '\r' -> pos++
                    else -> {
                        pos++
                        sb.append(escapeChar(c))
                    }
                }
            }
            return sb.toString()
        }

        /** 取一个「原子」：`{...}` 分组、一条命令，或单个字符。 */
        private fun parseAtom(): String {
            if (pos >= src.length) return ""
            return when (val c = src[pos]) {
                '{' -> {
                    pos++
                    parseSequence(stopAtBrace = true)
                }
                '\\' -> parseCommand()
                else -> {
                    pos++
                    escapeChar(c)
                }
            }
        }

        private fun parseCommand(): String {
            pos++ // 跳过反斜杠
            if (pos >= src.length) return ""
            val start = pos
            if (src[pos].isLetter()) {
                while (pos < src.length && src[pos].isLetter()) pos++
            } else {
                pos++
            }
            return dispatch(src.substring(start, pos))
        }

        private fun dispatch(name: String): String = when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val numerator = parseAtom()
                val denominator = parseAtom()
                "<span class=\"frac\"><span class=\"num\">$numerator</span>" +
                    "<span class=\"den\">$denominator</span></span>"
            }

            "sqrt" -> {
                val index = readOptionalBracket()
                val radicand = parseAtom()
                if (index.isEmpty()) {
                    "<span class=\"sqrt\">√<span class=\"rad\">$radicand</span></span>"
                } else {
                    "<span class=\"sqrt\"><sup class=\"idx\">$index</sup>√" +
                        "<span class=\"rad\">$radicand</span></span>"
                }
            }

            "text", "textrm", "mathrm", "mbox", "operatorname" ->
                escape(readRawGroup())

            "left", "right", "big", "Big", "bigg", "Bigg", "bigl", "bigr" -> {
                if (pos >= src.length) {
                    ""
                } else if (src[pos] == '\\') {
                    parseCommand()
                } else {
                    val delimiter = src[pos]
                    pos++
                    if (delimiter == '.') "" else escapeChar(delimiter)
                }
            }

            "begin", "end" -> {
                readRawGroup()
                ""
            }

            "\\" -> "<br>"
            "," -> "<span class=\"sp\"></span>"
            ":" -> "<span class=\"sp\"></span>"
            ";" -> "<span class=\"sp wide\"></span>"
            "!" -> ""
            "quad" -> "<span class=\"sp wide\"></span>"
            "qquad" -> "<span class=\"sp wider\"></span>"
            " " -> " "
            else -> SYMBOLS[name] ?: escape(name)
        }

        /** 读 `{...}` 原始内容（不解析，用于 \text 与 \begin）。 */
        private fun readRawGroup(): String {
            if (pos >= src.length || src[pos] != '{') return ""
            pos++
            val start = pos
            var depth = 1
            while (pos < src.length) {
                when (src[pos]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val raw = src.substring(start, pos)
                            pos++
                            return raw
                        }
                    }
                }
                pos++
            }
            return src.substring(start)
        }

        /** 读 `[...]`（根式的次数），内部按 LaTeX 渲染。 */
        private fun readOptionalBracket(): String {
            if (pos >= src.length || src[pos] != '[') return ""
            val end = src.indexOf(']', pos)
            if (end == -1) return ""
            val raw = src.substring(pos + 1, end)
            pos = end + 1
            return MathParser(raw).parseAll()
        }
    }

    private fun escapeChar(c: Char): String = when (c) {
        '<' -> "&lt;"
        '>' -> "&gt;"
        '&' -> "&amp;"
        else -> c.toString()
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) append(escapeChar(c))
    }

    /** HTML 属性值转义（比文本转义多一个双引号）。 */
    private fun escapeAttribute(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }

    /** 文本段里已经过 HTML 转义，公式内容要先还原再解析。 */
    private fun unescape(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    /** LaTeX 命令 -> Unicode。只列 K12 常见项。 */
    private val SYMBOLS: Map<String, String> = buildMap {
        // 希腊字母
        put("alpha", "α"); put("beta", "β"); put("gamma", "γ"); put("delta", "δ")
        put("epsilon", "ε"); put("varepsilon", "ε"); put("zeta", "ζ"); put("eta", "η")
        put("theta", "θ"); put("vartheta", "ϑ"); put("iota", "ι"); put("kappa", "κ")
        put("lambda", "λ"); put("mu", "μ"); put("nu", "ν"); put("xi", "ξ")
        put("pi", "π"); put("varpi", "ϖ"); put("rho", "ρ"); put("sigma", "σ")
        put("tau", "τ"); put("upsilon", "υ"); put("phi", "φ"); put("varphi", "φ")
        put("chi", "χ"); put("psi", "ψ"); put("omega", "ω")
        put("Gamma", "Γ"); put("Delta", "Δ"); put("Theta", "Θ"); put("Lambda", "Λ")
        put("Xi", "Ξ"); put("Pi", "Π"); put("Sigma", "Σ"); put("Phi", "Φ")
        put("Psi", "Ψ"); put("Omega", "Ω")

        // 运算符与关系
        put("times", "×"); put("div", "÷"); put("pm", "±"); put("mp", "∓")
        put("cdot", "·"); put("ast", "∗"); put("star", "⋆"); put("circ", "∘")
        put("bullet", "•"); put("oplus", "⊕"); put("otimes", "⊗")
        put("leq", "≤"); put("le", "≤"); put("geq", "≥"); put("ge", "≥")
        put("neq", "≠"); put("ne", "≠"); put("equiv", "≡"); put("approx", "≈")
        put("sim", "∼"); put("propto", "∝"); put("ll", "≪"); put("gg", "≫")
        put("infty", "∞"); put("partial", "∂"); put("nabla", "∇")
        put("sum", "∑"); put("prod", "∏"); put("int", "∫"); put("oint", "∮")
        put("lim", "lim"); put("log", "log"); put("ln", "ln"); put("lg", "lg")
        put("sin", "sin"); put("cos", "cos"); put("tan", "tan"); put("cot", "cot")
        put("sec", "sec"); put("csc", "csc"); put("arcsin", "arcsin")
        put("arccos", "arccos"); put("arctan", "arctan"); put("max", "max")
        put("min", "min"); put("exp", "exp"); put("deg", "°")

        // 箭头
        put("to", "→"); put("rightarrow", "→"); put("leftarrow", "←")
        put("leftrightarrow", "↔"); put("Rightarrow", "⇒"); put("Leftarrow", "⇐")
        put("Leftrightarrow", "⇔"); put("uparrow", "↑"); put("downarrow", "↓")
        put("longrightarrow", "⟶"); put("longleftarrow", "⟵")

        // 几何与集合
        put("angle", "∠"); put("perp", "⊥"); put("parallel", "∥")
        put("triangle", "△"); put("square", "□"); put("cong", "≅")
        put("in", "∈"); put("notin", "∉"); put("subset", "⊂"); put("supset", "⊃")
        put("subseteq", "⊆"); put("supseteq", "⊇"); put("cup", "∪"); put("cap", "∩")
        put("emptyset", "∅"); put("varnothing", "∅"); put("forall", "∀")
        put("exists", "∃"); put("neg", "¬"); put("land", "∧"); put("lor", "∨")
        put("because", "∵"); put("therefore", "∴"); put("degree", "°")

        // 其他
        put("dots", "…"); put("ldots", "…"); put("cdots", "⋯"); put("vdots", "⋮")
        put("prime", "′"); put("hbar", "ℏ"); put("ell", "ℓ")
        put("vec", ""); put("overline", ""); put("bar", ""); put("hat", "")
        put("mathrm", "")
    }
}
