package com.heikeji.phonesearch.data

import android.content.Context
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingRequest

/**
 * 本地用户偏好：年级 + 引导完成标记。
 *
 * **为什么年级要单独存**：搜题接口和 AI 作文接口都要传年级，而年级以前是从登录会话里取的
 * （`sessions.current()?.grade ?: 0`）。但实测确认搜题、整页搜题、快问 AI、AI 作文
 * **都不需要登录**（见 `SearchProbeTest`），所以没登录时也得有个年级可用，
 * 否则只能传 0 —— 那会让识别结果明显变差。
 *
 * 优先级：**登录会话的年级 > 本地年级 > [AiWritingRequest.DEFAULT_GRADE]**。
 * 登录后以账号里的为准，退出登录则回落到本地存的值。
 */
object UserPrefs {

    private const val PREFS = "user_prefs"
    private const val KEY_GRADE = "grade"
    private const val KEY_ONBOARDED = "onboarded"

    /** 合法范围就是 AI 作文那套 1..12（一年级到高三）。 */
    val GRADE_RANGE: IntRange = 1..12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本地年级。没设过就用默认值。 */
    fun grade(context: Context): Int {
        val value = prefs(context).getInt(KEY_GRADE, AiWritingRequest.DEFAULT_GRADE)
        return if (value in GRADE_RANGE) value else AiWritingRequest.DEFAULT_GRADE
    }

    fun setGrade(context: Context, grade: Int) {
        if (grade !in GRADE_RANGE) return
        prefs(context).edit().putInt(KEY_GRADE, grade).apply()
    }

    /** 引导页（选年级 + 账号说明）是否走过。 */
    fun onboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, done).apply()
    }

    /**
     * 实际用于请求的年级：登录了用账号里的，否则用本地的。
     *
     * 搜题和 AI 作文都走这一个口子，避免两处各写一遍再慢慢跑偏。
     */
    fun effectiveGrade(context: Context, sessionGrade: Int?): Int =
        sessionGrade?.takeIf { it in GRADE_RANGE } ?: grade(context)

    /** 年级的中文名，取不到就返回空串。 */
    fun gradeLabel(grade: Int): String =
        AiWritingRequest.GRADES.firstOrNull { it.first == grade }?.second.orEmpty()
}
