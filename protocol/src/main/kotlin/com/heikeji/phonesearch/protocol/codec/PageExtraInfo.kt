package com.heikeji.phonesearch.protocol.codec

import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.ProtocolProfile

/**
 * 框选精搜的关联信息，对应原 `K.C0012l` 构造的 `pageExtraInfo`。
 *
 * ```
 * {"wholeSearchSid":"<整页响应 sid>","index":3,"loc":"120.0@340.0@1980.0@1260.0"}
 * ```
 *
 * 要点：
 * - `index` 用题块的 **serviceIndex**，不是客户端列表位置。
 * - `loc` 是**包围矩形**（left@top@right@bottom），不是 8 点原始字符串。
 * - 原 APK 先取整数坐标再用 `Float.toString` 序列化，所以值带 `.0`，这里保持该格式。
 */
object PageExtraInfo {

    fun build(wholeSearchSid: String, serviceIndex: Int, loc: String): String {
        val json = JsonObject()
        json.addProperty("wholeSearchSid", wholeSearchSid)
        json.addProperty("index", serviceIndex)
        json.addProperty("loc", loc)
        return json.toString()
    }

    /** `120.0@340.0@1980.0@1260.0`。 */
    fun formatLoc(left: Int, top: Int, right: Int, bottom: Int): String {
        val separator = ProtocolProfile.LOC_SEPARATOR
        return "${left.toFloat()}$separator${top.toFloat()}$separator" +
            "${right.toFloat()}$separator${bottom.toFloat()}"
    }
}
