package com.heikeji.phonesearch.protocol.render

/**
 * 把答案 HTML 里的 LaTeX 公式转成纯 HTML + CSS 排版。
 *
 * 这是 KaTeX 不可用时的**兜底渲染器**：答案页 WebView 平时会用本地 KaTeX
 * 按 `data-tex` 重新渲染一遍（见 app 的 `assets/math/render.js`），
 * KaTeX 加载失败或整条公式报错时才落到这里产出的 HTML 排版。
 * 所以这里覆盖得越全，「公式没被正确解析」的观感越少。
 *
 * 支持的定界符：`$...$`、`$$...$$`、`\(...\)`、`\[...\]`（后两者与 `$$` 会带
 * `data-display="1"`，KaTeX 与 CSS 据此用独立公式版式）。
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
                sb.append("<span class=\"math\"")
                if (segment.display) sb.append(" data-display=\"1\"")
                sb.append(" data-tex=\"")
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

    private class Segment(val isMath: Boolean, val content: String, val display: Boolean = false)

    private fun split(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        var i = 0
        var plainStart = 0
        while (i < text.length) {
            val c = text[i]
            val open: String
            val close: String
            val display: Boolean
            when {
                c == '$' && i + 1 < text.length && text[i + 1] == '$' -> {
                    open = "$$"; close = "$$"; display = true
                }
                c == '$' -> {
                    open = "$"; close = "$"; display = false
                }
                c == '\\' && i + 1 < text.length && text[i + 1] == '(' -> {
                    open = "\\("; close = "\\)"; display = false
                }
                c == '\\' && i + 1 < text.length && text[i + 1] == '[' -> {
                    open = "\\["; close = "\\]"; display = true
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
            result.add(Segment(true, text.substring(start, end), display))
            i = end + close.length
            plainStart = i
        }
        if (plainStart < text.length) result.add(Segment(false, text.substring(plainStart)))
        return result
    }

    // ------------------------------------------------------------------ LaTeX 解析

    fun renderMath(latex: String): String =
        MathParser(latex.replace('\uFF3C', '\\')).parseAll()

    private class MathParser(
        private val src: String,
        /** 环境行内（cases/matrix）：`&` 渲染成列间距而不是空格。 */
        private val inEnvCells: Boolean = false,
    ) {
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
                        sb.append(if (inEnvCells) "<span class=\"env-cell-gap\"></span>" else " ")
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

        /** 取一个完整分组 `{...}` 并按公式渲染；没有分组时按单个原子处理。 */
        private fun parseGroupOrAtom(): String {
            if (pos < src.length && src[pos] == '{') {
                pos++
                return parseSequence(stopAtBrace = true)
            }
            return parseAtom()
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

            // 加重音符号的命令：参数渲染后用 CSS 在头顶画符号
            // （兜底排版不用 Unicode 组合符，廉价屏的字体栈经常拼不对）
            "vec" -> accented(parseGroupOrAtom(), "vec")
            "overrightarrow" -> accented(parseGroupOrAtom(), "harpoon")
            "overleftarrow" -> accented(parseGroupOrAtom(), "harpoon-l")
            "hat" -> accented(parseGroupOrAtom(), "hat")
            "widehat" -> accented(parseGroupOrAtom(), "hat")
            "tilde" -> accented(parseGroupOrAtom(), "tilde")
            "widetilde" -> accented(parseGroupOrAtom(), "tilde")
            "overline", "bar" -> wrapped(parseGroupOrAtom(), "ovl")
            "underline" -> wrapped(parseGroupOrAtom(), "und")

            // 字体命令：内容照常渲染，只包一层粗体/原样输出
            "mathbf", "boldsymbol", "bm" -> {
                if (pos >= src.length || src[pos] != '{') {
                    ""
                } else {
                    pos++
                    "<b>" + parseSequence(stopAtBrace = true) + "</b>"
                }
            }
            "mathit", "mathsf", "mathtt", "textsf" -> {
                if (pos >= src.length || src[pos] != '{') "" else {
                    pos++
                    parseSequence(stopAtBrace = true)
                }
            }
            "mathbb" -> blackboard(readRawGroup())

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

            "begin" -> parseEnvironment(readRawGroup())
            "end" -> {
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

        private fun accented(body: String, kind: String): String =
            "<span class=\"acc acc-$kind\"><span class=\"acc-body\">$body</span></span>"

        private fun wrapped(body: String, kind: String): String =
            "<span class=\"$kind\">$body</span>"

        /** `\mathbb{RNZQC}` → ℝ ℕ ℤ ℚ ℂ（其余字母原样）。 */
        private fun blackboard(raw: String): String = buildString(raw.length) {
            for (c in raw) {
                append(
                    when (c) {
                        'R' -> "ℝ"; 'N' -> "ℕ"; 'Z' -> "ℤ"; 'Q' -> "ℚ"; 'C' -> "ℂ"
                        'P' -> "ℙ"; 'H' -> "ℍ"; 'F' -> "𝔽"
                        else -> escapeChar(c)
                    },
                )
            }
        }

        /**
         * `\begin{env}...\end{env}`：按行拆 `\\`、按列拆 `&`，
         * cases 画左侧大括号，矩阵族画配对定界符，align 族只堆行。
         */
        private fun parseEnvironment(env: String): String {
            val name = env.trim()
            if (name.isEmpty()) return ""
            val endTag = "\\end{$name}"
            val endIdx = src.indexOf(endTag, pos)
            if (endIdx == -1) return "" // 没有配对的 \end：内容由外层循环照常渲染
            val body = src.substring(pos, endIdx)
            pos = endIdx + endTag.length

            val rows = body.split("\\\\")
                .map { row -> MathParser(row, inEnvCells = true).parseAll().trim() }
                .filter { it.isNotEmpty() }
                .joinToString("") { "<span class=\"env-row\">$it</span>" }

            return when {
                name == "cases" ->
                    "<span class=\"env\"><span class=\"env-delim\">{</span>" +
                        "<span class=\"env-rows\">$rows</span></span>"
                name.startsWith("p") && name.endsWith("matrix") ->
                    delimitedEnv(rows, "(", ")")
                name.startsWith("b") && name.endsWith("matrix") ->
                    delimitedEnv(rows, "[", "]")
                name.startsWith("v") && name.endsWith("matrix") ->
                    delimitedEnv(rows, "|", "|")
                name.startsWith("B") && name.endsWith("matrix") ->
                    delimitedEnv(rows, "{", "}")
                name.startsWith("V") && name.endsWith("matrix") ->
                    delimitedEnv(rows, "‖", "‖")
                name == "matrix" || name == "array" ->
                    "<span class=\"env\"><span class=\"env-rows\">$rows</span></span>"
                name.startsWith("align") || name.startsWith("align*") ||
                    name.startsWith("gather") || name.startsWith("gather*") ||
                    name.startsWith("eqnarray") ->
                    "<span class=\"env env-block\"><span class=\"env-rows\">$rows</span></span>"
                else -> rows // 未识别的环境：至少把行结构保留下来
            }
        }

        private fun delimitedEnv(rows: String, open: String, close: String): String =
            "<span class=\"env\">" +
                "<span class=\"env-delim\">$open</span>" +
                "<span class=\"env-rows\">$rows</span>" +
                "<span class=\"env-delim\">$close</span></span>"

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
        put("pi", "π"); put("varpi", "ϖ"); put("rho", "ρ"); put("varrho", "ϱ")
        put("sigma", "σ"); put("varsigma", "ς"); put("tau", "τ"); put("upsilon", "υ")
        put("phi", "φ"); put("varphi", "φ"); put("chi", "χ"); put("psi", "ψ")
        put("omega", "ω")
        put("Gamma", "Γ"); put("Delta", "Δ"); put("Theta", "Θ"); put("Lambda", "Λ")
        put("Xi", "Ξ"); put("Pi", "Π"); put("Sigma", "Σ"); put("Upsilon", "Υ")
        put("Phi", "Φ"); put("Psi", "Ψ"); put("Omega", "Ω")

        // 运算符与关系
        put("times", "×"); put("div", "÷"); put("pm", "±"); put("mp", "∓")
        put("cdot", "·"); put("ast", "∗"); put("star", "⋆"); put("circ", "∘")
        put("bullet", "•"); put("oplus", "⊕"); put("ominus", "⊖"); put("otimes", "⊗")
        put("odot", "⊙"); put("oslash", "⊘")
        put("leq", "≤"); put("le", "≤"); put("leqslant", "≤"); put("leqq", "≦")
        put("geq", "≥"); put("ge", "≥"); put("geqslant", "≥"); put("geqq", "≧")
        put("neq", "≠"); put("ne", "≠"); put("equiv", "≡"); put("approx", "≈")
        put("sim", "∼"); put("simeq", "≃"); put("cong", "≅"); put("asymp", "≍")
        put("propto", "∝"); put("ll", "≪"); put("gg", "≫"); put("doteq", "≐")
        put("infty", "∞"); put("partial", "∂"); put("nabla", "∇")
        put("sum", "∑"); put("prod", "∏"); put("coprod", "∐")
        put("int", "∫"); put("iint", "∬"); put("iiint", "∭"); put("oint", "∮")
        put("lim", "lim"); put("log", "log"); put("ln", "ln"); put("lg", "lg")
        put("sin", "sin"); put("cos", "cos"); put("tan", "tan"); put("cot", "cot")
        put("sec", "sec"); put("csc", "csc"); put("arcsin", "arcsin")
        put("arccos", "arccos"); put("arctan", "arctan"); put("max", "max")
        put("min", "min"); put("exp", "exp")
        put("deg", "°"); put("bmod", " mod "); put("pmod", " mod ")

        // 箭头
        put("to", "→"); put("rightarrow", "→"); put("leftarrow", "←")
        put("leftrightarrow", "↔"); put("Rightarrow", "⇒"); put("Leftarrow", "⇐")
        put("Leftrightarrow", "⇔"); put("uparrow", "↑"); put("downarrow", "↓")
        put("updownarrow", "↕"); put("Uparrow", "⇑"); put("Downarrow", "⇓")
        put("longrightarrow", "⟶"); put("longleftarrow", "⟵")
        put("Longrightarrow", "⟹")
        put("mapsto", "↦"); put("nearrow", "↗"); put("searrow", "↘")
        put("swarrow", "↙"); put("nwarrow", "↖")

        // 几何与集合
        put("angle", "∠"); put("measuredangle", "∡"); put("perp", "⊥")
        put("parallel", "∥"); put("nparallel", "∦"); put("mid", "∣"); put("nmid", "∤")
        put("triangle", "△"); put("square", "□"); put("blacksquare", "■")
        put("in", "∈"); put("notin", "∉"); put("ni", "∋")
        put("subset", "⊂"); put("supset", "⊃"); put("subseteq", "⊆")
        put("supseteq", "⊇"); put("subsetneq", "⊊"); put("supsetneq", "⊋")
        put("cup", "∪"); put("cap", "∩"); put("bigcup", "⋃"); put("bigcap", "⋂")
        put("setminus", "∖"); put("emptyset", "∅"); put("varnothing", "∅")
        put("forall", "∀"); put("exists", "∃"); put("nexists", "∄")
        put("neg", "¬"); put("land", "∧"); put("lor", "∨")
        put("because", "∵"); put("therefore", "∴")
        put("langle", "⟨"); put("rangle", "⟩"); put("lbrace", "{"); put("rbrace", "}")

        // 其他
        put("dots", "…"); put("ldots", "…"); put("cdots", "⋯"); put("vdots", "⋮")
        put("ddots", "⋱"); put("dotsb", "⋯"); put("dotsc", "…")
        put("prime", "′"); put("hbar", "ℏ"); put("ell", "ℓ"); put("Re", "ℜ")
        put("Im", "ℑ"); put("aleph", "ℵ"); put("wp", "℘")
        put("cdotp", "·"); put("colon", ":")
        put("vec", ""); put("overline", ""); put("bar", ""); put("hat", "")
        put("mathrm", "")
    }
}
