package com.heikeji.phonesearch.protocol.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import com.heikeji.phonesearch.protocol.chat.model.ChatTurn
import com.heikeji.phonesearch.protocol.core.InputBase

/**
 * 提问接口，对应官方 kdchat H5 的 `POST /kdchat/api/ask`。
 *
 * 同一端点有三种调用形态（官方 H5 各页面走法不同，抓包对齐）：
 * - [Input.buildTextInput]：纯文字提问（快问 AI 主聊天）
 * - [Input.buildAiSolveInput]：AI 解题 —— 带搜题上下文（sid/subjectId/
 *   picSearchInfo{etid,pid}/matchType/questionGrade，from=wholesearch），
 *   服务端据此讲解对应那道题（ai-pure-page）
 * - 带图提问见 [KdChatPhotoAsk]（老端点 /kdchat/photo/ask）
 */
object KdChatAsk {

    /** 官方端点。 */
    const val URL = "/kdchat/api/ask"

    /** 官方 Input。 */
    class Input private constructor(private val fields: LinkedHashMap<String, Any?>) :
        InputBase() {

        override val modelClass: Class<*> = KdChatAsk::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = fields

        companion object {
            /**
             * 官方纯文字提问（快问 AI）。
             *
             * @param history 已完成的问答（**不含**本次提问）；服务端据此维持上下文
             * @param thinkEnabled 深度思考
             * @param searchEnabled 联网搜索
             */
            fun buildTextInput(
                sessionId: String,
                content: String,
                history: List<ChatTurn>,
                grade: Int,
                thinkEnabled: Boolean,
                searchEnabled: Boolean,
            ): Input = Input(linkedMapOf(
                "subjectId" to "",
                "sid" to "",
                "agentId" to "",
                "searchEnabled" to if (searchEnabled) "1" else "0",
                "thinkEnabled" to if (thinkEnabled) "1" else "0",
                "isSugContent" to "0",
                "sugType" to "0",
                "grade" to grade.toString(),
                "content" to content,
                "feVc" to KdChat.FE_VC,
                "toolType" to KdChat.TOOL_TYPE_NORMAL,
                "sessionId" to sessionId,
                "isHitQueryRewrite" to "1",
                "inputType" to "1",
                "referInfo" to "",
                "from" to "home",
                "scene" to "",
                "isKeyPointContent" to "0",
                "context" to KdChat.contextJson(history),
            ))

            /**
             * 官方 AI 解题（ai-pure-page 抓包对齐）：带搜题结果上下文讲解指定题目。
             *
             * @param sid 搜题响应里的 sid
             * @param subjectId 科目 ID
             * @param etid 该题的加密题目编号（answers.tids[i]）
             * @param pid 图片 pid（picture.pid）
             * @param pvalLabel 官方抓包为 1
             */
            fun buildAiSolveInput(
                sessionId: String,
                grade: Int,
                picMd5: String,
                subjectId: String,
                sid: String,
                etid: String,
                pid: String,
                pvalLabel: Int = 1,
            ): Input = Input(linkedMapOf(
                "subjectId" to subjectId,
                "sid" to sid,
                "agentId" to "",
                "searchEnabled" to "0",
                "thinkEnabled" to "0",
                "isSugContent" to "0",
                "passthrough" to "",
                "matchType" to "2",
                "questionGrade" to "50",
                "imageInfo" to """{"picMD5":"$picMd5"}""",
                "picSearchInfo" to
                    """{"etid":"$etid","answerSrc":0,"answerContent":"","pvalNLabel":0,"pvalLabel":$pvalLabel,"pid":"$pid"}""",
                "grade" to grade.toString(),
                "content" to KdChat.AI_SOLVE_PROMPT,
                "feVc" to KdChat.FE_VC,
                "toolType" to KdChat.TOOL_TYPE_IMAGE,
                "sessionId" to sessionId,
                "isHitQueryRewrite" to "1",
                "inputType" to "1",
                "referInfo" to "",
                "from" to KdChat.FROM_WHOLESEARCH,
                "scene" to "",
                "isKeyPointContent" to "0",
                "context" to "[]",
            ))
        }
    }
}
