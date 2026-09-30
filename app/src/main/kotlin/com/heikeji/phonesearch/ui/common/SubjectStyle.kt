package com.heikeji.phonesearch.ui.common

import android.content.Context
import androidx.core.content.ContextCompat
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.protocol.render.SubjectIcons

/**
 * 学科标识：按学科给一个低饱和的土质色 + 一个代表字，渲染成圆角方块徽标。
 *
 * 配色刻意保持低饱和，与整体的暖色调一致——不引入高饱和的"彩色标签"。
 */
object SubjectStyle {

    data class Style(val colorRes: Int, val glyph: String)

    fun of(subject: String): Style {
        val value = subject.trim()
        return when {
            value.isEmpty() -> Style(R.color.subject_default, "题")
            value.contains("数学") -> Style(R.color.subject_math, "数")
            value.contains("语文") -> Style(R.color.subject_chinese, "语")
            value.contains("英语") || value.contains("英文") -> Style(R.color.subject_english, "英")
            value.contains("物理") -> Style(R.color.subject_physics, "物")
            value.contains("化学") -> Style(R.color.subject_chemistry, "化")
            value.contains("生物") -> Style(R.color.subject_biology, "生")
            value.contains("历史") -> Style(R.color.subject_history, "史")
            value.contains("地理") -> Style(R.color.subject_geography, "地")
            value.contains("政治") || value.contains("思想") -> Style(R.color.subject_politics, "政")
            else -> Style(R.color.subject_default, value.substring(0, 1))
        }
    }

    /** 学科色的十六进制字符串，供答案页 HTML 使用。 */
    fun colorHex(context: Context, subject: String): String {
        val color = ContextCompat.getColor(context, of(subject).colorRes)
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun glyph(subject: String): String = of(subject).glyph

    /** 该学科的图标路径（与答案页 HTML 里的内联 SVG 共用）。 */
    fun iconPath(subject: String): String = SubjectIcons.pathData(subject)
}
