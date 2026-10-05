package com.heikeji.phonesearch.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * 轻量 Markdown 渲染。
 *
 * 快问 AI 的回答是 Markdown，直接丢给 TextView 会露出 `**` `#` 这类记号。
 * 这里只处理聊天里真正会出现的几种，不追求完整规范：
 *
 * - `# 标题` ~ `###### 标题` -> 加大加粗，去掉井号
 * - `**粗体**` / `*斜体*` / `_斜体_`
 * - `` `行内代码` `` / ``` ```代码块``` ```
 * - `- ` `* ` `+ ` `1. ` 列表 -> 统一成 `• ` 并缩进
 * - `> 引用` -> 缩进 + 变灰
 * - `[文字](链接)` -> 只留文字
 */
object ChatMarkdown {

    /** 行内代码的背景色（淡灰）。 */
    private const val CODE_BG = 0x14000000

    fun render(source: String): CharSequence {
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

            appendLine(out, line)
        }

        // 去掉结尾多余换行
        while (out.isNotEmpty() && out[out.length - 1] == '\n') out.delete(out.length - 1, out.length)
        return out
    }

    private fun appendLine(out: SpannableStringBuilder, line: String) {
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
            appendInline(out, trimmed.removePrefix(">").trim())
            out.append('\n')
            return
        }

        // 列表
        val bullet = BULLET.find(trimmed)
        if (bullet != null) {
            out.append("  ".repeat(indent.coerceAtMost(3) + 1))
            out.append("• ")
            appendInline(out, trimmed.substring(bullet.value.length))
            out.append('\n')
            return
        }

        out.append("  ".repeat(indent.coerceAtMost(3)))
        appendInline(out, trimmed)
        out.append('\n')
    }

    /** 处理行内的粗体/斜体/代码/链接。 */
    private fun appendInline(out: SpannableStringBuilder, text: String) {
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

    private const val SPAN_FLAGS = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

    private val HEADING = Regex("^(#{1,6})\\s*(.+)$")
    private val BULLET = Regex("^([-*+]|\\d{1,2}\\.)\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\(([^)]*)\\)")
    private val CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val ITALIC = Regex("(?<![*\\w])[*_]([^*_]+)[*_](?![*\\w])")
}
