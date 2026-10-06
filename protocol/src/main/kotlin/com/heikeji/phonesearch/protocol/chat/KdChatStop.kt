package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 停止生成接口，对应官方 kdchat H5 的 `POST /kdchat/api/stop`。
 */
object KdChatStop {

    /** 官方端点。 */
    const val URL = "/kdchat/api/stop"

    /** 官方 Input。 */
    class Input private constructor(
        private val sessionId: String,
        private val answerId: String,
    ) : InputBase() {

        override val modelClass: Class<*> = KdChatStop::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "sessionId" to sessionId,
            "answerId" to answerId,
        )

        companion object {
            /** 官方 buildInput。 */
            fun buildInput(sessionId: String, answerId: String): Input =
                Input(sessionId, answerId)
        }
    }
}
