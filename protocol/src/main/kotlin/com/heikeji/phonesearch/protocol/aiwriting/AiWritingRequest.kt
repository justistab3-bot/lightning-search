package com.heikeji.phonesearch.protocol.aiwriting

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode
import com.heikeji.phonesearch.protocol.codec.UrlForm

/**
 * AI 作文的请求构造。
 *
 * 与搜题那套完全不同：**没有签名、没有 KDUSS**，只需要 `cuid` + `appid=scancode` + 版本号。
 * 实测版本号不影响结果（1170/6.49.0 与 1810/7.7.0 都能通），所以沿用本应用现有的版本。
 *
 * 中文与英语是**两套端点、两套参数**（`language` 在 body 里是数字、在 query 里是英文名）。
 */
object AiWritingRequest {

    // ---- 中文端点 ----
    const val PATH_WRITING_INTENT = "/aiwriting/ai/composition/writingintent"
    const val PATH_WRITE_CHECK = "/aiwriting/ai/composition/writeCheck"
    const val PATH_PRE_INSTANT = "/aiwriting/ai/composition/preInstantWriting"
    const val PATH_INSTANT = "/aiwriting/ai/composition/instantWriting"

    // ---- 英语端点 ----
    const val PATH_PRE_ENG_INSTANT = "/aiwriting/ai/composition/preEngInstantWriting"
    const val PATH_ENG_INSTANT = "/aiwriting/ai/composition/engInstantWriting"

    // ---- 提纲端点（中文）----
    const val PATH_THOUGHT_PRE_INSTANT =
        "/aiwriting/ai/composition/writingThought/preInstantWriting"
    const val PATH_THOUGHT_INSTANT =
        "/aiwriting/ai/composition/writingThought/instantWriting"
    const val PATH_THOUGHT_RESULT = "/aiwriting/ai/composition/writingThought/result"

    const val PATH_CUNGONG_REFRESH = "/aiwriting/ai/composition/cungongRefresh"

    /** 中文可选字数。 */
    val WORD_COUNTS_CHINESE = listOf("200+", "400+", "600+", "800+")

    /** 英语可选字数。 */
    val WORD_COUNTS_ENGLISH = listOf("60+", "80+", "100+", "120+")

    const val DEFAULT_WORD_COUNT_CHINESE = "800+"
    const val DEFAULT_WORD_COUNT_ENGLISH = "100+"

    /** 年级（小学到高三）。 */
    val GRADES = listOf(
        1 to "一年级", 2 to "二年级", 3 to "三年级", 4 to "四年级", 5 to "五年级", 6 to "六年级",
        7 to "初一", 8 to "初二", 9 to "初三",
        10 to "高一", 11 to "高二", 12 to "高三",
    )

    const val DEFAULT_GRADE = 6

    /**
     * 服务端对 `gradeId` 几乎不敏感（实测二年级与高三生成的水平、字数都差不多），
     * 但 `describe` 是会被采纳的写作要求，所以把年级折进去，让选择真正起点作用。
     */
    fun gradeHint(gradeId: Int): String {
        val label = GRADES.firstOrNull { it.first == gradeId }?.second ?: return ""
        return "写作要求：符合${label}学生的认知水平和语言风格。"
    }

    /** query 里的 `queryType` 固定用枚举值 5（原实现如此）。 */
    private const val QUERY_TYPE_ENUM = "5"

    fun wordCountsOf(language: EssayLanguage): List<String> =
        if (language == EssayLanguage.ENGLISH) WORD_COUNTS_ENGLISH else WORD_COUNTS_CHINESE

    fun defaultWordCount(language: EssayLanguage): String =
        if (language == EssayLanguage.ENGLISH) DEFAULT_WORD_COUNT_ENGLISH
        else DEFAULT_WORD_COUNT_CHINESE

    /** 生成前「准备」接口的路径。 */
    fun preInstantPath(mode: WritingMode, language: EssayLanguage): String = when {
        mode == WritingMode.ENGLISH || language == EssayLanguage.ENGLISH -> PATH_PRE_ENG_INSTANT
        mode == WritingMode.THINKING || mode == WritingMode.THINKING_MAP -> PATH_THOUGHT_PRE_INSTANT
        else -> PATH_PRE_INSTANT
    }

    /** 流式生成接口的路径。 */
    fun instantPath(mode: WritingMode, language: EssayLanguage): String = when {
        mode == WritingMode.ENGLISH || language == EssayLanguage.ENGLISH -> PATH_ENG_INSTANT
        mode == WritingMode.THINKING || mode == WritingMode.THINKING_MAP -> PATH_THOUGHT_INSTANT
        else -> PATH_INSTANT
    }

    // ---- 请求体 ----

    /** 第一步：标题 -> 文体识别。英语作文没有文体识别，可跳过。 */
    fun writingIntentBody(cuid: String, title: String, gradeId: Int): String {
        val json = JsonObject()
        json.addProperty("hybrid", 1)
        json.addProperty("pageFrom", "标题输入页")
        json.addProperty("adid", cuid)
        json.addProperty("cuid", cuid)
        json.add("needAnti", JsonArray())
        json.addProperty("sid", "")
        json.addProperty("title", title)
        json.addProperty("gradeId", gradeId)
        return json.toString()
    }

    /** 查重检查。 */
    fun writeCheckBody(cuid: String, title: String): String {
        val json = JsonObject()
        json.addProperty("hybrid", 1)
        json.addProperty("cuid", cuid)
        json.addProperty("title", title)
        json.addProperty("describe", "")
        json.addProperty("sid", "")
        json.addProperty("source", 1)
        json.addProperty("language", EssayLanguage.CHINESE.code)
        return json.toString()
    }

    /**
     * 第二步：准备生成。
     *
     * 注意 `queryType` 在 **query 里传的是 `5`**（枚举），在 **body 里传的是中文字体名**，
     * 两者不一致是原实现的行为，照抄。
     */
    fun preInstantBody(
        title: String,
        queryType: String,
        wordCount: String,
        gradeId: Int,
        writeDate: Long,
        language: EssayLanguage,
    ): String {
        val json = JsonObject()
        json.addProperty("hybrid", 1)
        json.addProperty("writeDate", writeDate)
        json.addProperty("searchFrom", "shouye")
        json.add("entityList", JsonArray())
        json.addProperty("isDefaultTitle", 2)
        json.addProperty("preSid", "")
        json.addProperty("sessionId", "")
        json.add("session", JsonObject())
        json.addProperty("title", title)
        json.addProperty("describe", gradeHint(gradeId))
        json.addProperty("sid", "")
        json.addProperty("language", language.code)
        json.addProperty("queryType", queryType)
        json.addProperty("wordCount", wordCount)
        json.addProperty("gradeId", gradeId)
        json.add("historySids", JsonArray())
        return json.toString()
    }

    /** 第二步的 query。 */
    fun preInstantQuery(
        title: String,
        wordCount: String,
        gradeId: Int,
        language: EssayLanguage,
    ): String {
        val params = LinkedHashMap<String, String?>()
        params["uid"] = ""
        params["gradeId"] = gradeId.toString()
        params["sid"] = ""
        params["queryType"] = QUERY_TYPE_ENUM
        params["searchFrom"] = ""
        params["move"] = ""
        params["title"] = title
        params["wordCount"] = wordCount
        params["describe"] = gradeHint(gradeId)
        params["voiceDescribe"] = ""
        params["entityStr"] = ""
        params["language"] = language.queryName
        params["isDefaultTitle"] = "2"
        params["photoTextId"] = ""
        params["channel"] = ""
        return UrlForm.encodeForm(params)
    }

    /** 第三步：流式生成的 query。 */
    fun instantQuery(
        sid: String,
        cuid: String,
        sessionId: String,
        title: String,
        wordCount: String,
        gradeId: Int,
        language: EssayLanguage,
    ): String {
        val params = LinkedHashMap<String, String?>()
        params["sid"] = sid
        params["cuid"] = cuid
        params["appid"] = "scancode"
        params["eventId"] = ""
        params["sessionId"] = sessionId
        params["channel"] = "xiaomi"
        params["gradeId"] = gradeId.toString()
        params["queryType"] = QUERY_TYPE_ENUM
        params["searchFrom"] = ""
        params["move"] = ""
        params["title"] = title
        params["wordCount"] = wordCount
        params["describe"] = gradeHint(gradeId)
        params["entityStr"] = ""
        params["entityContent"] = ""
        params["language"] = language.queryName
        return UrlForm.encodeForm(params)
    }

    /** 取生成结果（提纲流程的收尾）。 */
    fun thoughtResultQuery(sid: String, cuid: String): String {
        val params = LinkedHashMap<String, String?>()
        params["sid"] = sid
        params["cuid"] = cuid
        return UrlForm.encodeForm(params)
    }

    /** 提交流式结果的 query。 */
    fun cungongRefreshQuery(sid: String): String {
        val params = LinkedHashMap<String, String?>()
        params["hybrid"] = "1"
        params["sid"] = sid
        return UrlForm.encodeForm(params)
    }
}

/**
 * 作文语言。
 *
 * `code` 用于请求体（数字），`queryName` 用于 query（英文名）——服务端就是这么要求的。
 */
enum class EssayLanguage(val code: Int, val queryName: String, val label: String) {
    CHINESE(1, "Chinese", "中文"),
    ENGLISH(2, "English", "英语"),
}
