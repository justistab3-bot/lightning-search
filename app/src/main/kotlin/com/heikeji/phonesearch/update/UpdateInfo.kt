package com.heikeji.phonesearch.update

/** 一个可用的更新。 */
data class UpdateInfo(
    /** 去掉 `v` 前缀的版本号，例如 `1.12.0`。 */
    val versionName: String,
    /** release 的 tag，例如 `v1.12.0`。 */
    val tagName: String,
    /** release 标题。 */
    val title: String,
    /** 更新说明（markdown 原文，展示时做轻量处理）。 */
    val changelog: String,
    /** APK 直链。 */
    val apkUrl: String,
    val apkFileName: String,
) {
    val hasApk: Boolean get() = apkUrl.isNotEmpty()
}

/** 版本号比较。 */
object Version {

    /**
     * 语义化比较：`1.12.0` > `1.9.0`（逐段按整数比，不是字符串比）。
     *
     * @return a > b 返回正数，a < b 返回负数，相等返回 0
     */
    fun compare(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(index) { 0 }
            val y = right.getOrElse(index) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** [candidate] 是否比 [current] 新。 */
    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    private fun segments(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .split('.', '-', '_', '+')
        .mapNotNull { it.trim().toIntOrNull() }
}
