package com.heikeji.phonesearch.protocol.envelope

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.json.Json
import com.heikeji.phonesearch.protocol.json.intOr
import com.heikeji.phonesearch.protocol.json.strOrEmpty

/**
 * antispam 响应的解包（原 f1.b.q0）。
 *
 * 响应形如 `{"errNo":0,"data":{"data":"<signB>"}}`，其中 data 也可能是 JSON 字符串，
 * 需要逐层剥到最内层的字符串。
 *
 * 放在 :protocol 内部是为了不把 Gson 类型泄漏到 :app 的编译类路径上。
 */
object Envelope {

    private const val MAX_DEPTH = 5

    fun extractInnerData(text: String): String {
        var payload = text
        var depth = 0
        while (depth++ < MAX_DEPTH) {
            val json = Json.tryParseObject(payload) ?: break
            val errNo = json.intOr("errNo", json.intOr("errno", 0))
            if (errNo != 0) {
                throw ProtocolException(
                    json.strOrEmpty("errstr").ifEmpty { "设备签名初始化被拒绝（$errNo）" },
                )
            }
            val data = json.get("data") ?: break
            when {
                data.isJsonPrimitive -> {
                    val value = data.asString.trim()
                    if (value.isEmpty()) break
                    if (Json.tryParseObject(value) != null) {
                        payload = value
                    } else {
                        return value
                    }
                }
                data.isJsonObject -> payload = data.toString()
                else -> break
            }
        }
        throw ProtocolException("设备签名初始化响应无法识别")
    }
}
