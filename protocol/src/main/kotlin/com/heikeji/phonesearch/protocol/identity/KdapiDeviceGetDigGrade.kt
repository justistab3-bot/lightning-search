package com.heikeji.phonesearch.protocol.identity

import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 学段上报接口，对应官方 `com.kuaiduizuoye.scan.common.net.model.v1.KdapiDeviceGetDigGrade`。
 *
 * 官方在搜题前上报 `grade`，响应 `{"data":{"digGrade":N}}` 回填到后续
 * 所有请求的公共参数 `digGrade`。
 */
object KdapiDeviceGetDigGrade {

    /** 官方 `KdapiDeviceGetDigGrade.Input`。 */
    class Input private constructor(private val grade: Int) : InputBase() {

        override val modelClass: Class<*> = KdapiDeviceGetDigGrade::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf("grade" to grade)

        companion object {
            /** 官方 `Input.URL`。 */
            const val URL = "/kdapi/device/getdiggrade"

            /** 官方 `Input.buildInput`。 */
            fun buildInput(grade: Int): Input = Input(grade)
        }
    }
}
