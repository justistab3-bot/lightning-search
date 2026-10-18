package com.heikeji.phonesearch.protocol.render

/**
 * 学科图标：24x24 视口下的 SVG path 数据（线稿风格）。
 *
 * 放在 `:protocol` 里是为了让原生界面（PathParser 绘制）和答案页 HTML（内联 SVG）
 * 共用同一份定义，避免两处各画一遍导致不一致。
 */
object SubjectIcons {

    /** 数学：根号与被开方数，避免与对勾混淆。 */
    const val MATH = "M2 13 L5 13 L8 20 L12 4 L22 4 M15 10 L21 17 M21 10 L15 17"

    /** 语文：翻开的书 */
    const val CHINESE =
        "M2.5 5 C6 3.5 9 4 12 6 C15 4 18 3.5 21.5 5 L21.5 20 " +
            "C18 18.5 15 19 12 21 C9 19 6 18.5 2.5 20 Z M12 6 L12 21 " +
            "M5.5 8.5 L9 9.5 M5.5 12 L9 13 M15 9.5 L18.5 8.5 M15 13 L18.5 12"

    /** 英语：Aa 字母，和地理的地球明确区分。 */
    const val ENGLISH =
        "M2 19 L7 5 L12 19 M4 14 L10 14 " +
            "M21 11 L21 19 M21 12 C17 8 14 11 14 15 C14 19 18 21 21 17"

    /** 物理：原子与交叉电子轨道。 */
    const val PHYSICS =
        "M22 12 A10 4 0 1 1 2 12 A10 4 0 1 1 22 12 Z " +
            "M17 20.66 A10 4 60 1 1 7 3.34 A10 4 60 1 1 17 20.66 Z " +
            "M7 20.66 A10 4 120 1 1 17 3.34 A10 4 120 1 1 7 20.66 Z " +
            "M13 12 A1 1 0 1 1 11 12 A1 1 0 1 1 13 12 Z"

    /** 化学：带液面与气泡的锥形瓶。 */
    const val CHEMISTRY =
        "M8 2.5 L16 2.5 M9 2.5 L9 9 L3.5 19 A1.6 1.6 0 0 0 5 21.5 " +
            "L19 21.5 A1.6 1.6 0 0 0 20.5 19 L15 9 L15 2.5 " +
            "M6 15 L18 15 M9 18.5 L9.1 18.5 M13 11.5 L13.1 11.5 M15 18 L15.1 18"

    /** 生物：叶子 */
    const val BIOLOGY =
        "M21 3 C10 3 4 7 4 14 C4 18 7 20 11 20 C18 20 21 12 21 3 Z " +
            "M3 22 L17 8 M8 17 L8 12 M12 13 L17 13"

    /** 历史：古典建筑立柱。 */
    const val HISTORY =
        "M2 7 L12 2 L22 7 Z M3 10 L21 10 M5 10 L5 18 M10 10 L10 18 " +
            "M14 10 L14 18 M19 10 L19 18 M3 18 L21 18 M2 22 L22 22"

    /** 地理：带经纬线的地球。 */
    const val GEOGRAPHY =
        "M22 12 A10 10 0 1 1 2 12 A10 10 0 1 1 22 12 Z M2 12 L22 12 " +
            "M12 2 C6 7 6 17 12 22 C18 17 18 7 12 2 M4 6.5 L20 6.5 M4 17.5 L20 17.5"

    /** 政治：天平 */
    const val POLITICS =
        "M12 3 L12 20 M5 7 L19 7 M8 21 L16 21 M5 7 L2 13 L8 13 Z M19 7 L16 13 L22 13 Z"

    /** 默认：文档 */
    const val DEFAULT =
        "M6 3 L14 3 L18 7 L18 21 L6 21 Z M14 3 L14 7 L18 7 M9 11 L15 11 M9 15 L15 15"

    fun pathData(subject: String): String {
        val value = subject.trim()
        return when {
            value.isEmpty() -> DEFAULT
            value.contains("数学") -> MATH
            value.contains("语文") -> CHINESE
            value.contains("英语") || value.contains("英文") -> ENGLISH
            value.contains("物理") -> PHYSICS
            value.contains("化学") -> CHEMISTRY
            value.contains("生物") -> BIOLOGY
            value.contains("历史") -> HISTORY
            value.contains("地理") -> GEOGRAPHY
            value.contains("政治") || value.contains("思想") || value.contains("道德") || value.contains("法治") -> POLITICS
            else -> DEFAULT
        }
    }
}
