package com.heikeji.phonesearch.protocol.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.heikeji.phonesearch.protocol.ProtocolException

/**
 * Gson 的薄封装，语义对齐原实现里 `JSONObject.optString` / `optJSONObject` / `optJSONArray` / `optInt` 的容错行为：
 * 字段缺失、类型不符、值为 null 时都返回默认值，而不是抛异常。
 */
object Json {

    /** 解析对象；失败抛 [ProtocolException]。 */
    fun parseObject(text: String, errorMessage: String): JsonObject {
        val element = try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw ProtocolException(errorMessage, e)
        }
        if (!element.isJsonObject) throw ProtocolException(errorMessage)
        return element.asJsonObject
    }

    /** 容错解析：失败返回 null。 */
    fun tryParseObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        return try {
            val element = JsonParser.parseString(text)
            if (element.isJsonObject) element.asJsonObject else null
        } catch (e: RuntimeException) {
            null
        }
    }
}

/** 对应 `optString(name, "")`。 */
fun JsonObject?.strOrEmpty(name: String): String {
    val element = this?.get(name) ?: return ""
    if (element.isJsonNull) return ""
    return if (element.isJsonPrimitive) element.asString else ""
}

/** 对应 `optJSONObject(name)`。 */
fun JsonObject?.objOrNull(name: String): JsonObject? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonObject) element.asJsonObject else null
}

/** 对应 `optJSONArray(name)`。 */
fun JsonObject?.arrOrNull(name: String): JsonArray? {
    val element = this?.get(name) ?: return null
    return if (element.isJsonArray) element.asJsonArray else null
}

/** 对应 `optInt(name, default)`：数字直接取，数字字符串也接受。 */
fun JsonObject?.intOr(name: String, default: Int): Int {
    val element: JsonElement = this?.get(name) ?: return default
    if (element.isJsonNull) return default
    if (!element.isJsonPrimitive) return default
    val primitive = element.asJsonPrimitive
    if (primitive.isNumber) return primitive.asInt
    return primitive.asString.trim().toIntOrNull() ?: default
}
