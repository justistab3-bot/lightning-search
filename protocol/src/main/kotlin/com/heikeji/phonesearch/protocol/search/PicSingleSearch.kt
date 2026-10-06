package com.heikeji.phonesearch.protocol.search

import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 单题搜题接口，对应官方 `com.kuaiduizuoye.scan.common.net.model.v1.PicSingleSearch`。
 *
 * 官方结构：模型类（响应字段：sid/searchInfo/picture/answers/validatedInfo/…）
 * 内嵌 `Input`（URL + buildInput + getParams）。本工程响应解析在
 * `search.parse` 层（org.json + Gson），请求侧与官方逐字段对齐。
 */
object PicSingleSearch {

    /** 官方 `PicSingleSearch.Input`。 */
    class Input private constructor(
        private val picMD5: String,
        private val shumei: String,
        private val ref: String,
        private val pageExtraInfo: String,
        private val referer: String,
        private val isStudentMode: String,
        private val grade: String,
        private val from: String,
        private val abtest: String,
    ) : InputBase() {

        override val modelClass: Class<*> = PicSingleSearch::class.java
        override val url: String = URL
        override val pid: String = PID
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "picMD5" to picMD5,
            "shumei" to shumei,
            "ref" to ref,
            "pageExtraInfo" to pageExtraInfo,
            "referer" to referer,
            "isStudentMode" to isStudentMode,
            "grade" to grade,
            "from" to from,
            "abtest" to abtest,
        )

        companion object {
            /** 官方 `Input.URL`。 */
            const val URL = "/picsearch/submit/singlesearch"

            /** 官方 `__pid`：默认业务主机。 */
            const val PID = ""

            /** 官方 7.7.0 抓包固定值。 */
            const val SHUMEI = ""
            const val REF = "0"
            const val IS_STUDENT_MODE = "1"
            const val FROM = "otherPage"
            const val ABTEST = "{}"

            /** 普通单题首搜。 */
            const val REFERER_SINGLE = "1"

            /** 由整页题块框选触发的精搜。 */
            const val REFERER_CROP = "3"

            /** 官方 `Input.buildInput`。 */
            fun buildInput(
                picMd5: String,
                grade: String,
                pageExtraInfo: String = "",
                referer: String = REFERER_SINGLE,
                shumei: String = SHUMEI,
                ref: String = REF,
                isStudentMode: String = IS_STUDENT_MODE,
                from: String = FROM,
                abtest: String = ABTEST,
            ): Input = Input(
                picMD5 = picMd5,
                shumei = shumei,
                ref = ref,
                pageExtraInfo = pageExtraInfo,
                referer = referer,
                isStudentMode = isStudentMode,
                grade = grade,
                from = from,
                abtest = abtest,
            )
        }
    }
}
