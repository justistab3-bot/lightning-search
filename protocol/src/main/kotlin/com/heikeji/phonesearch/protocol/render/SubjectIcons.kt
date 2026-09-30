package com.heikeji.phonesearch.protocol.render

/**
 * 学科图标：24x24 视口下的 SVG path 数据（线稿风格）。
 *
 * 放在 `:protocol` 里是为了让原生界面（PathParser 绘制）和答案页 HTML（内联 SVG）
 * 共用同一份定义，避免两处各画一遍导致不一致。
 */
object SubjectIcons {

    /** 数学：根号 */
    const val MATH = "M3 13 L6.5 13 L9.5 20 L15.5 4 L21 4"

    /** 语文：翻开的书 */
    const val CHINESE =
        "M4 5.5 C7 3.8 10 3.8 12 5.4 C14 3.8 17 3.8 20 5.5 L20 18.5 " +
            "C17 16.8 14 16.8 12 18.4 C10 16.8 7 16.8 4 18.5 Z M12 5.4 L12 18.4"

    /** 英语：地球 */
    const val ENGLISH =
        "M12 3 a9 9 0 1 0 0.01 0 M3 12 L21 12 " +
            "M12 3 C8.5 7 8.5 17 12 21 C15.5 17 15.5 7 12 3"

    /** 物理：锥形瓶（实验器材） */
    const val PHYSICS =
        "M9 3 L9 8.5 L4.5 19 C4 20.2 5 21 6.2 21 L17.8 21 C19 21 20 20.2 19.5 19 " +
            "L15 8.5 L15 3 M8 3 L16 3 M6.8 16 L17.2 16"

    /** 化学：试管 */
    const val CHEMISTRY =
        "M9 3 L9 17.5 A3 3 0 0 0 15 17.5 L15 3 M8 3 L16 3 M9 11.5 L15 11.5"

    /** 生物：叶子 */
    const val BIOLOGY =
        "M20 4 C10 4 4 10 4 20 C14 20 20 14 20 4 Z M4 20 C9 15 14 10 20 4"

    /** 历史：沙漏 */
    const val HISTORY = "M6 3 L18 3 L13 12 L18 21 L6 21 L11 12 Z"

    /** 地理：折叠地图 */
    const val GEOGRAPHY =
        "M3 6 L9 4 L15 6 L21 4 L21 18 L15 20 L9 18 L3 20 Z M9 4 L9 18 M15 6 L15 20"

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
            value.contains("政治") || value.contains("思想") -> POLITICS
            else -> DEFAULT
        }
    }
}
