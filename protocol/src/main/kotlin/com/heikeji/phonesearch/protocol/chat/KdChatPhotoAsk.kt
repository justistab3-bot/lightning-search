package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.chat.model.ChatTurn
import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 带图提问接口（快问 AI 选图提问用的老端点），对应 `POST /kdchat/photo/ask`。
 *
 * multipart 里第一部分是图片，其余是同一批表单字段。
 * 与 [KdChatAsk] 的差异：`toolType=image` + `imageInfo{picMD5}`；
 * `pagesearchInfo` 在官方 H5 里是**可选**的（`a.pagesearchInfo && (...)`），
 * 所以不发也能用。
 */
object KdChatPhotoAsk {

    /** 端点。 */
    const val URL = "/kdchat/photo/ask"

    /** Input。 */
    class Input private constructor(private val fields: LinkedHashMap<String, Any?>) :
        InputBase() {

        override val modelClass: Class<*> = KdChatPhotoAsk::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = fields

        companion object {
            /** buildInput（history 语义同 [KdChatAsk.Input.buildTextInput]）。 */
            fun buildInput(
                sessionId: String,
                content: String,
                history: List<ChatTurn>,
                grade: Int,
                thinkEnabled: Boolean,
                searchEnabled: Boolean,
                picMd5: String,
            ): Input = Input(linkedMapOf(
                "subjectId" to "",
                "sid" to "",
                "agentId" to "",
                "searchEnabled" to if (searchEnabled) "1" else "0",
                "thinkEnabled" to if (thinkEnabled) "1" else "0",
                "isSugContent" to "0",
                "imageInfo" to """{"picMD5":"$picMd5"}""",
                "grade" to grade.toString(),
                "content" to content,
                "feVc" to KdChat.FE_VC,
                "toolType" to KdChat.TOOL_TYPE_IMAGE,
                "sessionId" to sessionId,
                "isHitQueryRewrite" to "1",
                "inputType" to "1",
                "referInfo" to "",
                "from" to "home",
                "scene" to "",
                "isKeyPointContent" to "0",
                "context" to KdChat.contextJson(history),
            ))
        }
    }
}
