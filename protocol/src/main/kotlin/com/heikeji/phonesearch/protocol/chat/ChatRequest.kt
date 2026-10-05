package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import com.heikeji.phonesearch.protocol.chat.model.ChatTurn
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 快问 AI（`kdchat` 系列接口）的请求参数。
 *
 * 与搜题那套不同：**明文表单参数**，不加密，只走通用签名。
 * 实测抓包（快对 7.7.0）：
 * ```
 * POST /kdchat/api/create
 *   appId=scancode&grade=6&scene=&from=&feVc=211&...公共参数...&sign=...&_t_=...&kakorrhaphiophobia=...
 *   -> {"errNo":0,"errstr":"succ","data":{"sessionId":142120884764}}
 *
 * POST /kdchat/api/ask
 *   content=<问题>&sessionId=<会话>&context=<JSON数组>&toolType=normal&from=home
 *   &feVc=211&thinkEnabled=0&searchEnabled=0&isSugContent=0&isKeyPointContent=0
 *   &isHitQueryRewrite=1&inputType=1&grade=6&...公共参数...&sign=...
 * ```
 */
object ChatRequest {

    const val PATH_CONF_INIT = "/kdchat/conf/init"
    const val PATH_GUIDE = "/kdchat/api/guide"
    const val PATH_CREATE = "/kdchat/api/create"
    const val PATH_ASK = "/kdchat/api/ask"
    const val PATH_STOP = "/kdchat/api/stop"

    /** 拍照提问（multipart：图片 + 同一批表单字段）。 */
    const val PATH_PHOTO_ASK = "/kdchat/photo/ask"

    /** 前端版本号，抓包里固定 211。 */
    const val FE_VC = "211"

    /** 普通对话。 */
    const val TOOL_TYPE_NORMAL = "normal"

    /** 带图提问。 */
    const val TOOL_TYPE_IMAGE = "image"

    /** 建会话。 */
    fun createParams(grade: Int): Map<String, String> = linkedMapOf(
        "appId" to "scancode",
        "grade" to grade.toString(),
        "scene" to "",
        "from" to "",
        "feVc" to FE_VC,
    )

    /** 推荐问题 / 引导。 */
    fun guideParams(grade: Int): Map<String, String> = linkedMapOf(
        "grade" to grade.toString(),
        "feVc" to FE_VC,
        "from" to "home",
    )

    /**
     * 提问。
     *
     * @param history 已完成的问答（**不含**本次提问）；服务端据此维持上下文
     * @param thinkEnabled 深度思考
     * @param searchEnabled 联网搜索
     */
    fun askParams(
        sessionId: String,
        content: String,
        history: List<ChatTurn>,
        grade: Int,
        thinkEnabled: Boolean,
        searchEnabled: Boolean,
    ): Map<String, String> = linkedMapOf(
        "subjectId" to "",
        "sid" to "",
        "agentId" to "",
        "searchEnabled" to if (searchEnabled) "1" else "0",
        "thinkEnabled" to if (thinkEnabled) "1" else "0",
        "isSugContent" to "0",
        "sugType" to "0",
        "grade" to grade.toString(),
        "content" to content,
        "feVc" to FE_VC,
        "toolType" to TOOL_TYPE_NORMAL,
        "sessionId" to sessionId,
        "isHitQueryRewrite" to "1",
        "inputType" to "1",
        "referInfo" to "",
        "from" to "home",
        "scene" to "",
        "isKeyPointContent" to "0",
        "context" to contextJson(history),
    )

    /**
     * 带图提问的表单字段（图片本身走 multipart 的 `image` 部分）。
     *
     * 与纯文字的区别：
     * - `toolType` 是 `image`；
     * - 多一个 `imageInfo`，内容是 `{"picMD5":"<图片 md5>"}`。
     *
     * H5 里 `pagesearchInfo` 是**可选**的（`a.pagesearchInfo && (...)`），
     * 所以这里不发也能用；服务端拿 `imageInfo` 自己找书页。
     */
    fun photoAskParams(
        sessionId: String,
        content: String,
        history: List<ChatTurn>,
        grade: Int,
        thinkEnabled: Boolean,
        searchEnabled: Boolean,
        picMd5: String,
    ): Map<String, String> = linkedMapOf(
        "subjectId" to "",
        "sid" to "",
        "agentId" to "",
        "searchEnabled" to if (searchEnabled) "1" else "0",
        "thinkEnabled" to if (thinkEnabled) "1" else "0",
        "isSugContent" to "0",
        "imageInfo" to """{"picMD5":"$picMd5"}""",
        "grade" to grade.toString(),
        "content" to content,
        "feVc" to FE_VC,
        "toolType" to TOOL_TYPE_IMAGE,
        "sessionId" to sessionId,
        "isHitQueryRewrite" to "1",
        "inputType" to "1",
        "referInfo" to "",
        "from" to "home",
        "scene" to "",
        "isKeyPointContent" to "0",
        "context" to contextJson(history),
    )

    /**
     * 拼 `context` 数组。
     *
     * 对照 H5 的构造逻辑：**只带已回答过的用户提问**（回答还没出来的那轮不带），
     * `role` 恒为 `user`，`intent` 空数组、`isCard` 为 `"0"`（快问来源）。
     */
    fun contextJson(history: List<ChatTurn>): String {
        val array = JsonArray()
        for (turn in history) {
            if (turn.role != ChatRole.USER) continue
            if (turn.text.isEmpty()) continue
            val item = JsonObject()
            item.addProperty("toolType", TOOL_TYPE_NORMAL)
            item.addProperty("role", "user")
            item.addProperty("content", turn.text)
            item.addProperty("time", turn.timeSeconds)
            item.add("intent", JsonArray())
            item.addProperty("isCard", "0")
            array.add(item)
        }
        return array.toString()
    }
}
