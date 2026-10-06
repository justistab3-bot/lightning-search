package com.heikeji.phonesearch.protocol.search

import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 整页搜题接口，对应官方 `com.kuaiduizuoye.scan.common.net.model.v1.PicPageSearch`。
 *
 * 与单题的差异（官方字段）：多 `imgCorrection`，`referer` 官方 7.7.0 传空串；
 * 响应多 `answers.locInfo`/`locs`/`angles`（题框定位），解析见 `search.parse.PageSearchParser`。
 */
object PicPageSearch {

    /** 官方 `PicPageSearch.Input`。 */
    class Input private constructor(
        private val picMD5: String,
        private val shumei: String,
        private val ref: String,
        private val referer: String,
        private val isStudentMode: String,
        private val grade: String,
        private val from: String,
        private val imgCorrection: String,
        private val abtest: String,
    ) : InputBase() {

        override val modelClass: Class<*> = PicPageSearch::class.java
        override val url: String = URL
        override val pid: String = PID
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "picMD5" to picMD5,
            "shumei" to shumei,
            "ref" to ref,
            "referer" to referer,
            "isStudentMode" to isStudentMode,
            "grade" to grade,
            "from" to from,
            "imgCorrection" to imgCorrection,
            "abtest" to abtest,
        )

        companion object {
            /** 官方 `Input.URL`。 */
            const val URL = "/picsearch/submit/pagesearch"

            /** 官方 `__pid`：默认业务主机。 */
            const val PID = ""

            /** 官方 7.7.0 抓包固定值。 */
            const val SHUMEI = ""
            const val REF = "0"
            const val IS_STUDENT_MODE = "1"
            const val FROM = "otherPage"
            const val ABTEST = "{}"

            /** 官方 7.7.0 整页搜题传空串。 */
            const val REFERER_PAGE = ""

            const val IMG_CORRECTION = "0"

            /** 官方 `Input.buildInput`。 */
            fun buildInput(
                picMd5: String,
                grade: String,
                referer: String = REFERER_PAGE,
                shumei: String = SHUMEI,
                ref: String = REF,
                isStudentMode: String = IS_STUDENT_MODE,
                from: String = FROM,
                imgCorrection: String = IMG_CORRECTION,
                abtest: String = ABTEST,
            ): Input = Input(
                picMD5 = picMd5,
                shumei = shumei,
                ref = ref,
                referer = referer,
                isStudentMode = isStudentMode,
                grade = grade,
                from = from,
                imgCorrection = imgCorrection,
                abtest = abtest,
            )
        }
    }
}
