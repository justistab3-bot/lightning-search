package com.heikeji.phonesearch.protocol.chat

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import com.heikeji.phonesearch.protocol.chat.model.ChatTurn

/**
 * kdchat（快问 AI / AI 解题）域共享常量与工具。
 *
 * 官方这些值散落在 H5 各页面与抓包样本里；集中在此便于对照升级。
 */
object KdChat {

    /** 前端版本号，抓包里固定 211。 */
    const val FE_VC = "211"

    /** appId 恒为 scancode（快对扫描端）。 */
    const val APP_ID = "scancode"

    /** 普通对话。 */
    const val TOOL_TYPE_NORMAL = "normal"

    /** 带图提问。 */
    const val TOOL_TYPE_IMAGE = "image"

    /** AI 解题的固定提问语（官方 ai-pure-page 实测）。 */
    const val AI_SOLVE_PROMPT = "小对，帮我讲解一下"

    /** AI 解题来源标识（官方抓包 from=wholesearch）。 */
    const val FROM_WHOLESEARCH = "wholesearch"

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
