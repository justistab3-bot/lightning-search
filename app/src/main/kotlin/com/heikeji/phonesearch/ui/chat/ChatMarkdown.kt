package com.heikeji.phonesearch.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 轻量 Markdown 渲染（快问 AI / AI 解题的回答）。
 *
 * 除标题/粗体/代码/列表/引用/链接外，还渲染 LaTeX 公式：
 * `$$..$$`（独立行）、`\[..\]`（独立行）、`$..$` 与 `\(..\)`（行内）。
 * 公式经 jlatexmath 画成 Drawable 后以 ImageSpan 插入；解析失败时保留原文。
 *
 * 性能约定：**流式期间调用方应直接显示纯文本**，等回答结束后再走本渲染器 ——
 * 公式与整段重排版只做一次，避免逐字刷新卡住主线程。
 */
object ChatMarkdown {

    /** 行内代码的背景色（淡灰）。 */
    private const val CODE_BG = 0x14000000

    /** 公式初始字号（会被等比缩放到目标高度，此值只影响画质）。 */
    private const val MATH_BASE_SIZE = 48f

    fun render(source: String, view: TextView): CharSequence {
        if (source.isEmpty()) return ""
        val out = SpannableStringBuilder()
        var inCodeBlock = false

        for (rawLine in source.split('\n')) {
            val line = rawLine.trimEnd('\r')

            // 代码块围栏
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }

            if (inCodeBlock) {
                val start = out.length
                out.append(line).append('\n')
                out.setSpan(TypefaceSpan("monospace"), start, out.length - 1, SPAN_FLAGS)
                out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length - 1, SPAN_FLAGS)
                continue
            }

            // 独立行公式：整行只有一对 $$ 或 \[ \]
            val trimmed = line.trim()
            val block = BLOCK_MATH.find(trimmed)
            if (block != null && block.range.first == 0 && block.range.last + 1 == trimmed.length) {
                appendMath(out, block.groupValues[1], view, display = true)
                continue
            }

            appendLine(out, line, view)
        }

        // 去掉结尾多余换行
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendLine(out: SpannableStringBuilder, line: String, view: TextView) {
        val heading = HEADING.find(line)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val text = heading.groupValues[2].trim()
            val start = out.length
            out.append(text)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, SPAN_FLAGS)
            // h1 放大 1.35 倍，之后每级递减
            out.setSpan(RelativeSizeSpan(1.35f - 0.1f * (level - 1).coerceAtMost(3)), start, out.length, SPAN_FLAGS)
            out.append('\n')
            return
        }

        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length

        // 引用
        if (trimmed.startsWith(">")) {
            out.append("  ")
            appendInline(out, trimmed.removePrefix(">").trim(), view)
            out.append('\n')
            return
        }

        // 列表
        val bullet = BULLET.find(trimmed)
        if (bullet != null) {
            out.append("  ".repeat(indent.coerceAtMost(3) + 1))
            out.append("• ")
            appendInline(out, trimmed.substring(bullet.value.length), view)
            out.append('\n')
            return
        }

        out.append("  ".repeat(indent.coerceAtMost(3)))
        appendInline(out, trimmed, view)
        out.append('\n')
    }

    /**
     * 行内内容：先按公式切段，公式画成图片，其余走粗体/斜体/代码/链接处理。
     */
    private fun appendInline(out: SpannableStringBuilder, text: String, view: TextView) {
        if (text.isEmpty()) return

        var cursor = 0
        for (match in INLINE_MATH.findAll(text)) {
            // 公式前的普通文本
            if (match.range.first > cursor) {
                appendPlain(out, text.substring(cursor, match.range.first))
            }
            val latex = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (latex.isNotEmpty()) {
                appendMath(out, latex, view, display = false)
            } else {
                appendPlain(out, match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            appendPlain(out, text.substring(cursor))
        }
    }

    /** 处理行内的粗体/斜体/代码/链接。 */
    private fun appendPlain(out: SpannableStringBuilder, text: String) {
        if (text.isEmpty()) return
        val builder = SpannableStringBuilder(text)

        // 链接：只留文字
        for (match in LINK.findAll(text).toList().asReversed()) {
            builder.replace(match.range.first, match.range.last + 1, match.groupValues[1])
        }

        // 行内代码
        for (match in CODE.findAll(builder).toList().asReversed()) {
            val start = match.range.first
            val end = match.range.last + 1
            val content = match.groupValues[1]
            builder.replace(start, end, content)
            val stop = start + content.length
            builder.setSpan(TypefaceSpan("monospace"), start, stop, SPAN_FLAGS)
            builder.setSpan(BackgroundColorSpan(CODE_BG), start, stop, SPAN_FLAGS)
        }

        // 粗体（先于斜体，避免 ** 被当成两个 *）
        for (match in BOLD.findAll(builder).toList().asReversed()) {
            val start = match.range.first
            val end = match.range.last + 1
            val content = match.groupValues[1]
            builder.replace(start, end, content)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, start + content.length, SPAN_FLAGS)
        }

        // 斜体
        for (match in ITALIC.findAll(builder).toList().asReversed()) {
            val start = match.range.first
            val end = match.range.last + 1
            val content = match.groupValues[1]
            builder.replace(start, end, content)
            builder.setSpan(StyleSpan(Typeface.ITALIC), start, start + content.length, SPAN_FLAGS)
        }

        // 残留的单独星号/下划线去掉
        var i = 0
        while (i < builder.length) {
            if (builder[i] == '*' || builder[i] == '_') builder.delete(i, i + 1) else i++
        }

        out.append(builder)
    }

    /**
     * 把一段 LaTeX 画成与正文行高协调的图片插进文本。
     * 解析失败时保留原文（公式仍可读）。
     */
    private fun appendMath(
        out: SpannableStringBuilder,
        latex: String,
        view: TextView,
        display: Boolean,
    ) {
        val drawable = runCatching {
            JLatexMathDrawable.builder(latex)
                .textSize(MATH_BASE_SIZE)
                .align(JLatexMathDrawable.ALIGN_CENTER)
                // 跟随气泡文字颜色（夜间模式是浅色文字，默认黑色会看不清）
                .color(view.currentTextColor)
                .build()
        }.getOrNull()

        if (drawable == null) {
            out.append(if (display) "\n\$\$$latex\$\$\n" else "\$$latex\$")
            return
        }

        // 目标高度：行内约 1.5 倍正文字高，独立行约 1.8 倍
        val target = (view.textSize * if (display) 1.8f else 1.5f).toInt().coerceAtLeast(24)
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth < 1 || intrinsicHeight < 1) {
            out.append(latex)
            return
        }
        val scale = target.toFloat() / intrinsicHeight
        val width = (intrinsicWidth * scale).toInt().coerceAtLeast(8)
        drawable.setBounds(0, 0, width, target)

        val start = out.length
        if (display) {
            // 独立行公式居中：先单独起一行
            out.append('\n')
            out.append('\uFFFC')
            out.append('\n')
        } else {
            out.append('\uFFFC')
            out.append(' ')
        }
        out.setSpan(MathImageSpan(drawable), start, out.length, SPAN_FLAGS)
    }

    /** ImageSpan：底边对齐文本基线（配合 ALIGN_CENTER 的绘制内对齐）。 */
    private class MathImageSpan(drawable: android.graphics.drawable.Drawable) :
        ImageSpan(drawable, ImageSpan.ALIGN_BASELINE)

    private const val SPAN_FLAGS = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

    private val HEADING = Regex("^(#{1,6})\\s*(.+)$")
    private val BULLET = Regex("^([-*+]|\\d{1,2}\\.)\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)]*)\\)")
    private val CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITALIC = Regex("(?<![*\\w])[*_]([^*_]+)[*_](?![*\\w])")

    /** 独立行公式：整行只有 $$..$$ 或 \[..\]。 */
    private val BLOCK_MATH = Regex("^\\s*\\\$\\\$\\s*(.+?)\\s*\\\$\\\$\\s*$|^\\s*\\\\\\[\\s*(.+?)\\s*\\\\\\]\\s*$")

    /** 行内公式：$$..$$、$..$、\(..\)。 */
    private val INLINE_MATH = Regex("\\\$\\\$([^\\$\\n]+?)\\\$\\\$|\\\$([^\\$\\n]+?)\\$|\\\\\\((.+?)\\\\\\)")
}
