package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 建会话接口，对应官方 kdchat H5 的 `POST /kdchat/api/create`。
 *
 * 实测抓包（快对 7.7.0）：
 * ```
 * appId=scancode&grade=6&scene=&from=&feVc=211&...公共参数...&sign=...
 * -> {"errNo":0,"errstr":"succ","data":{"sessionId":142120884764}}
 * ```
 */
object KdChatCreate {

    /** 官方端点。 */
    const val URL = "/kdchat/api/create"

    /** 官方 Input。 */
    class Input private constructor(
        private val appId: String,
        private val grade: String,
        private val scene: String,
        private val from: String,
        private val feVc: String,
    ) : InputBase() {

        override val modelClass: Class<*> = KdChatCreate::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "appId" to appId,
            "grade" to grade,
            "scene" to scene,
            "from" to from,
            "feVc" to feVc,
        )

        companion object {
            /** 官方 buildInput。 */
            fun buildInput(
                grade: Int,
                appId: String = KdChat.APP_ID,
                scene: String = "",
                from: String = "",
                feVc: String = KdChat.FE_VC,
            ): Input = Input(
                appId = appId,
                grade = grade.toString(),
                scene = scene,
                from = from,
                feVc = feVc,
            )
        }
    }
}
