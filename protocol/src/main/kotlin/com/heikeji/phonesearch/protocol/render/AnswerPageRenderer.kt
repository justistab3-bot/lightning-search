package com.heikeji.phonesearch.protocol.render

import com.heikeji.phonesearch.protocol.model.AnswerItem

/**
 * 把一条 [AnswerItem] 渲染成答案页 WebView 的完整 HTML。
 *
 * 视觉语言：暖羊皮纸底、象牙白卡片、衬线标题、宽行高、大圆角图片。
 *
 * 明暗两套配色由调用方通过 [darkTheme] 显式指定，不依赖 `prefers-color-scheme`——
 * WebView 是否跟随应用夜间模式在各版本上并不一致，显式传参更可靠。
 * 学科徽标颜色同理由调用方传入（`:protocol` 不依赖 Android 资源）。
 *
 * 公式：先清洗 HTML，再用 [LatexRenderer] 把文本节点里的 LaTeX 转成 HTML 排版，
 * 因此答案页 WebView 可以保持**禁用 JavaScript**。
 *
 * 安全约束：
 * - CSP 限制为 `default-src 'none'; img-src https: data:; style-src 'unsafe-inline'`
 * - 图片只允许 https（由 [AnswerHtmlSanitizer] 保证）
 * - 调用方必须对 WebView 关闭 JavaScript 并禁止导航
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
                append(headerChips(item.subject, subjectColorHex, isBestMatch = index == 1))
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
    ): String {
        val color = colorHex ?: "#87867F"
        val sb = StringBuilder("<div class=\"chip-row\">")
        if (subject.isNotBlank()) {
            sb.append("<span class=\"subject-chip\">")
            sb.append("<span class=\"subject-badge\" style=\"background:").append(color).append("\">")
            sb.append("<svg viewBox=\"0 0 24 24\" width=\"15\" height=\"15\" fill=\"none\" ")
                .append("stroke=\"#FAF9F5\" stroke-width=\"2\" stroke-linecap=\"round\" ")
                .append("stroke-linejoin=\"round\"><path d=\"")
                .append(SubjectIcons.pathData(subject))
                .append("\"/></svg>")
            sb.append("</span><span class=\"subject-name\">")
                .append(AnswerHtmlSanitizer.escape(subject))
                .append("</span></span>")
        }
        if (isBestMatch) {
            sb.append("<span class=\"best-chip\">")
            sb.append("<svg viewBox=\"0 0 24 24\" width=\"13\" height=\"13\" fill=\"#C96442\">")
                .append("<path d=\"").append(STAR_PATH).append("\"/></svg>")
            sb.append("最佳匹配</span>")
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

    private const val SERIF = "Georgia, 'Noto Serif CJK SC', 'Source Han Serif SC', serif"
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

    private val LIGHT = Palette(
        bg = "#F5F4ED",
        text = "#3D3D3A",
        card = "#FAF9F5",
        border = "#F0EEE6",
        heading = "#141413",
        imageBg = "#F0EEE6",
        tableBorder = "#E8E6DC",
        tableHeadBg = "#F5F4ED",
        quoteBorder = "#D1CFC5",
        quoteText = "#5E5D59",
        muted = "#87867F",
        accent = "#C96442",
        accentSoft = "#F6EAE4",
    )

    private val DARK = Palette(
        bg = "#141413",
        text = "#D1CFC5",
        card = "#30302E",
        border = "#3D3D3A",
        heading = "#FAF9F5",
        imageBg = "#3D3D3A",
        tableBorder = "#3D3D3A",
        tableHeadBg = "#3D3D3A",
        quoteBorder = "#5E5D59",
        quoteText = "#B0AEA5",
        muted = "#87867F",
        accent = "#D97757",
        accentSoft = "#3A2C25",
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
            .subject-name { font-family: $SERIF; font-size: 15px; color: ${p.heading}; }
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
              padding: 18px;
              margin: 0 0 14px;
            }
            section:last-child { margin-bottom: 0; }
            h2 {
              font-family: $SERIF; font-weight: 500; font-size: 16px;
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
            .math[data-math-engine="katex"] {
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
        """.trimIndent()
    }
}
