package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 推荐问题接口，对应官方 kdchat H5 的 `POST /kdchat/api/guide`。
 */
object KdChatGuide {

    /** 官方端点。 */
    const val URL = "/kdchat/api/guide"

    /** 官方 Input。 */
    class Input private constructor(
        private val grade: String,
        private val feVc: String,
        private val from: String,
    ) : InputBase() {

        override val modelClass: Class<*> = KdChatGuide::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "grade" to grade,
            "feVc" to feVc,
            "from" to from,
        )

        companion object {
            /** 官方 buildInput。 */
            fun buildInput(
                grade: Int,
                feVc: String = KdChat.FE_VC,
                from: String = "home",
            ): Input = Input(grade.toString(), feVc, from)
        }
    }
}
