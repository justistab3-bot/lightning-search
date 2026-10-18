package com.heikeji.phonesearch.protocol.render

import com.heikeji.phonesearch.protocol.search.model.AnswerItem

/**
 * 把一条 [AnswerItem] 渲染成答案页 WebView 的完整 HTML。
 *
 * 视觉语言（v1.36.0「墨蓝学院」）：冷雾蓝灰底、纯白卡片、靛蓝强调色、
 * 无衬线粗标题、冷描边 + 浅投影，与原生侧 colors.xml/themes.xml 同一套 token。
 *
 * 明暗两套配色由调用方通过 [darkTheme] 显式指定，不依赖 `prefers-color-scheme`——
 * WebView 是否跟随应用夜间模式在各版本上并不一致，显式传参更可靠。
 * 学科徽标颜色同理由调用方传入（`:protocol` 不依赖 Android 资源）。
 *
 * 公式：先清洗 HTML（含跨标签公式合并），再用 [LatexRenderer] 把文本节点里的
 * LaTeX 转成 HTML 兜底排版；页面上的本地 KaTeX 会再按 `data-tex` 重渲染一遍。
 *
 * 安全约束：
 * - CSP 限制脚本/样式/字体只允许本地 appassets，图片只允许 https
 * - 图片只允许 https（由 [AnswerHtmlSanitizer] 保证）
 * - 不注册任何 JS Bridge，禁止导航离开本地页
 */
object AnswerPageRenderer {

    /**
     * 页面 origin。KaTeX 的本地资源由 `WebViewAssetLoader` 挂在这个域名下提供，
     * 这样既不用开 `allowFileAccess`，也让 CSP 有一个稳定的来源可以写。
     */
    const val ASSET_BASE_URL = "https://appassets.androidplatform.net/"

    private const val KATEX_CSS = ASSET_BASE_URL + "assets/math/katex.min.css"
    private const val KATEX_JS = ASSET_BASE_URL + "assets/math/katex.min.js"
    private const val RENDER_JS = ASSET_BASE_URL + "assets/math/render.js"

    /**
     * 只允许本地 appassets 的脚本与样式，其余全部禁止。
     *
     * 答案页会开启 JavaScript 来跑本地 KaTeX，但脚本来源被 CSP 锁死在本机资源上：
     * 答案正文里的脚本与事件处理器进不来（清洗阶段已剔除，CSP 再兜一层）。
     */
    private const val CSP = "default-src 'none'; " +
        "script-src $ASSET_BASE_URL; " +
        "style-src 'unsafe-inline' $ASSET_BASE_URL; " +
        "font-src $ASSET_BASE_URL; " +
        "img-src https: data:; " +
        "connect-src 'none'; object-src 'none'; frame-src 'none'; " +
        "media-src 'none'; base-uri 'none'; form-action 'none'"

    /** 最佳匹配星标。 */
    private const val STAR_PATH =
        "M12 17.27 L18.18 21 L16.54 13.97 L22 9.24 L14.81 8.63 L12 2 L9.19 8.63 " +
            "L2 9.24 L7.46 13.97 L5.82 21 Z"

    fun render(
        item: AnswerItem,
        index: Int,
        total: Int,
        subjectColorHex: String? = null,
        darkTheme: Boolean = false,
    ): String {
        val body = buildString {
            if (item.subject.isNotBlank() || index == 1) {
                append(headerChips(item.subject, subjectColorHex, isBestMatch = index == 1, darkTheme))
            }
            if (item.rawHtml != null) {
                append(section("结果", item.rawHtml, emptyList()))
            } else {
                if (item.hasAnswer) append(section("答案", item.answerHtml, item.answerImages))
                if (item.hasAnalysis) append(section("解析", item.analysisHtml, emptyList()))
                if (item.hasQuestion) {
                    append(section("匹配到的题目", item.questionHtml, item.questionImages))
                }
                if (!item.hasAnswer && !item.hasAnalysis && !item.hasQuestion) {
                    append("<section><p class=\"empty\">未匹配到可显示的内容</p></section>")
                }
            }
        }
        return document(body, darkTheme)
    }

    /** 加载中/失败等纯文本提示页。 */
    fun renderMessage(message: String, darkTheme: Boolean = false): String =
        document(
            "<section><p class=\"empty\">${AnswerHtmlSanitizer.escape(message)}</p></section>",
            darkTheme,
        )

    private fun headerChips(
        subject: String,
        colorHex: String?,
        isBestMatch: Boolean,
        darkTheme: Boolean,
    ): String {
        val color = colorHex ?: "#6E7896"
        val sb = StringBuilder("<div class=\"chip-row\">")
        if (subject.isNotBlank()) {
            sb.append("<span class=\"subject-chip\">")
            sb.append("<span class=\"subject-badge\" style=\"background:").append(color).append("\">")
            sb.append("<svg viewBox=\"0 0 24 24\" width=\"15\" height=\"15\" fill=\"none\" ")
                .append("aria-hidden=\"true\" stroke=\"")
                .append(if (darkTheme) "#171B2B" else "#FFFFFF")
                .append("\" stroke-width=\"2\" stroke-linecap=\"round\" ")
                .append("stroke-linejoin=\"round\"><path d=\"")
                .append(SubjectIcons.pathData(subject))
                .append("\"/></svg>")
            sb.append("</span><span class=\"subject-name\">")
                .append(AnswerHtmlSanitizer.escape(subject))
                .append("</span></span>")
        }
        if (isBestMatch) {
            sb.append("<span class=\"best-chip\">")
            sb.append("<svg viewBox=\"0 0 24 24\" width=\"13\" height=\"13\" fill=\"#FFB020\">")
                .append("<path d=\"").append(STAR_PATH).append("\"/></svg>")
                .append("最佳匹配</span>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun section(title: String, rawHtml: String, images: List<String>): String {
        // 先清洗（剔除不可信标记）→ 公式排版 → 给图片包上可点击的锚点。
        val content = AnswerHtmlSanitizer.wrapImagesWithLinks(
            LatexRenderer.renderInHtml(AnswerHtmlSanitizer.clean(rawHtml)),
        )
        val safeImages = images.filter { it.startsWith("https://") }
        val sb = StringBuilder()
        sb.append("<section><h2>").append(AnswerHtmlSanitizer.escape(title)).append("</h2>")
        if (content.isNotEmpty()) sb.append(content)
        for (src in safeImages) {
            val escaped = AnswerHtmlSanitizer.escape(src)
            sb.append("<a class=\"zoom\" href=\"").append(escaped).append("\">")
                .append("<img src=\"").append(escaped)
                .append("\" alt=\"").append(AnswerHtmlSanitizer.escape(title))
                .append("图片，点击看大图\" loading=\"lazy\"></a>")
        }
        if (content.isEmpty() && safeImages.isEmpty()) {
            sb.append("<p class=\"empty\">暂未提供")
                .append(AnswerHtmlSanitizer.escape(title))
                .append("内容</p>")
        }
        sb.append("</section>")
        return sb.toString()
    }

    private fun document(body: String, dark: Boolean): String = """
        <!DOCTYPE html>
        <html lang="zh-CN">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
        <meta http-equiv="Content-Security-Policy" content="$CSP">
        <link rel="stylesheet" href="$KATEX_CSS">
        <style>${palette(dark)}</style>
        <script src="$KATEX_JS" defer></script>
        <script src="$RENDER_JS" defer></script>
        </head>
        <body>
        $body
        </body>
        </html>
    """.trimIndent()

    private const val SANS =
        "-apple-system, 'Noto Sans CJK SC', 'Source Han Sans SC', 'Microsoft YaHei', sans-serif"
    private const val MATH_FONT =
        "Georgia, 'Cambria Math', 'Latin Modern Math', 'Noto Serif CJK SC', serif"

    private data class Palette(
        val bg: String,
        val text: String,
        val card: String,
        val border: String,
        val heading: String,
        val imageBg: String,
        val tableBorder: String,
        val tableHeadBg: String,
        val quoteBorder: String,
        val quoteText: String,
        val muted: String,
        val accent: String,
        val accentSoft: String,
    )

    /** 「墨蓝学院」亮色：冷雾底 + 纯白卡 + 靛蓝强调（与原生 colors.xml 同值）。 */
    private val LIGHT = Palette(
        bg = "#EEF1F8",
        text = "#3A4258",
        card = "#FFFFFF",
        border = "#E6EAF6",
        heading = "#171B2B",
        imageBg = "#EEF1F8",
        tableBorder = "#D8DEF0",
        tableHeadBg = "#F4F7FD",
        quoteBorder = "#C3CCE8",
        quoteText = "#4E5B85",
        muted = "#6E7896",
        accent = "#3B4DB8",
        accentSoft = "#E4E9FC",
    )

    /** 「墨蓝学院」暗色：墨蓝夜色表面（与 values-night/colors.xml 同值）。 */
    private val DARK = Palette(
        bg = "#0F1220",
        text = "#D3D8EA",
        card = "#1A1F33",
        border = "#2A304F",
        heading = "#F2F4FC",
        imageBg = "#222842",
        tableBorder = "#333B5E",
        tableHeadBg = "#222842",
        quoteBorder = "#3D4A75",
        quoteText = "#9AA5CC",
        muted = "#8791B2",
        accent = "#9DB0FF",
        accentSoft = "#2A3160",
    )

    private fun palette(dark: Boolean): String {
        val p = if (dark) DARK else LIGHT
        return """
            :root { color-scheme: ${if (dark) "dark" else "light"}; }
            * { -webkit-tap-highlight-color: transparent; }
            body {
              margin: 0;
              padding: 16px 16px 32px;
              background: ${p.bg};
              color: ${p.text};
              font: 16px/1.7 $SANS;
              word-break: break-word;
              -webkit-text-size-adjust: 100%;
            }

            /* ---------- 顶部标识 ---------- */
            .chip-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0 0 14px; }
            .subject-chip {
              display: inline-flex; align-items: center; gap: 8px;
              padding: 5px 14px 5px 5px;
              border-radius: 14px;
              background: ${p.card};
              border: 1px solid ${p.border};
            }
            .subject-badge {
              display: inline-flex; align-items: center; justify-content: center;
              width: 26px; height: 26px; border-radius: 9px;
            }
            .subject-name { font-size: 15px; font-weight: 700; color: ${p.heading}; }
            .best-chip {
              display: inline-flex; align-items: center; gap: 5px;
              padding: 5px 12px; border-radius: 14px;
              background: ${p.accentSoft};
              color: ${p.accent};
              font-size: 13px; font-weight: 500;
            }

            /* ---------- 段落卡片 ---------- */
            section {
              background: ${p.card};
              border: 1px solid ${p.border};
              border-radius: 16px;
              box-shadow: 0 1px 2px rgba(23, 27, 43, 0.04);
              padding: 18px;
              margin: 0 0 14px;
            }
            section:last-child { margin-bottom: 0; }
            h2 {
              font-weight: 700; font-size: 16px;
              color: ${p.heading}; margin: 0 0 12px; letter-spacing: 0;
            }
            p { margin: 0 0 12px; }
            p:last-child { margin-bottom: 0; }
            img {
              display: block; width: 100%; height: auto;
              margin: 12px 0; border-radius: 12px; background: ${p.imageBg};
            }
            a.zoom { display: block; text-decoration: none; }
            a.zoom:active img { opacity: .82; }
            table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 15px; }
            th, td { border: 1px solid ${p.tableBorder}; padding: 8px 10px; text-align: left; }
            th { background: ${p.tableHeadBg}; font-weight: 600; }
            pre, code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 14px; }
            pre {
              background: ${p.tableHeadBg}; border: 1px solid ${p.border};
              border-radius: 12px; padding: 14px; overflow-x: auto; white-space: pre-wrap;
            }
            blockquote {
              margin: 12px 0; padding: 4px 0 4px 14px;
              border-left: 3px solid ${p.quoteBorder}; color: ${p.quoteText};
            }
            ol, ul { padding-left: 22px; margin: 0 0 12px; }
            .empty { color: ${p.muted}; }

            /* ---------- 公式排版 ---------- */
            /* 下面这套是 KaTeX 不可用时的兜底排版；KaTeX 渲染成功后会被中和掉。 */
            .math {
              font-family: $MATH_FONT;
              font-size: 1.02em;
              line-height: 1.35;
              white-space: normal;
            }
            /* $$ / \[..\] 独立公式：兜底也按块居中排版 */
            .math[data-display="1"] {
              display: block;
              text-align: center;
              margin: .45em 0;
            }
            .math[data-math-engine="katex"], .math[data-math-engine="katex-lenient"] {
              font-family: inherit;
              font-size: 1em;
              line-height: inherit;
            }
            .katex { font-size: 1.05em; }
            .katex-display { margin: .5em 0; overflow-x: auto; overflow-y: hidden; }
            sup, sub { font-size: .72em; line-height: 0; }
            .frac {
              display: inline-block;
              vertical-align: -0.48em;
              text-align: center;
              margin: 0 .18em;
              line-height: 1.25;
            }
            .frac > .num { display: block; padding: 0 .3em; border-bottom: 1.2px solid currentColor; }
            .frac > .den { display: block; padding: 0 .3em; }
            .sqrt { display: inline-block; white-space: nowrap; }
            .sqrt > .rad { border-top: 1.2px solid currentColor; padding: 0 .12em; }
            .sqrt > .idx { font-size: .66em; vertical-align: .55em; margin-right: -.12em; }
            .sp { display: inline-block; width: .17em; }
            .sp.wide { width: .33em; }
            .sp.wider { width: .66em; }

            /* 顶标类命令（\vec \hat \overline…）：符号用 CSS 画在头顶 */
            .acc { position: relative; display: inline-block; }
            .acc::before {
              position: absolute;
              top: -.58em; left: 50%;
              transform: translateX(-50%);
              font-size: .68em; line-height: 1;
              pointer-events: none;
            }
            .acc-vec::before { content: "→"; letter-spacing: -.12em; }
            .acc-harpoon::before { content: "⟶"; letter-spacing: -.2em; }
            .acc-harpoon-l::before { content: "⟵"; letter-spacing: -.2em; }
            .acc-hat::before { content: "^"; font-weight: 600; }
            .acc-tilde::before { content: "~"; }
            .ovl { border-top: 1.2px solid currentColor; padding: 0 .1em; }
            .und { text-decoration: underline; text-underline-offset: .18em; }

            /* \begin{cases} / 矩阵 / aligned 等环境的兜底排版 */
            .env { display: inline-flex; align-items: stretch; vertical-align: middle; margin: 0 .12em; }
            .env-block { display: flex; justify-content: center; margin: .2em 0; }
            .env-delim { display: flex; align-items: center; font-size: 1.7em; font-weight: 300; line-height: 1; }
            .env-rows { display: inline-flex; flex-direction: column; justify-content: center; padding: .08em 0; }
            .env-row { display: block; text-align: left; padding: .05em .3em; white-space: nowrap; }
            .env-cell-gap { display: inline-block; width: 1.1em; }
        """.trimIndent()
    }
}
