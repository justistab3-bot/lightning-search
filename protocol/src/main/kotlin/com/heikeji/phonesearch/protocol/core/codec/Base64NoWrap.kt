package com.heikeji.phonesearch.protocol.core.codec

import com.heikeji.phonesearch.protocol.ProtocolException
import java.util.Base64

/**
 * Android `Base64.decode(str, Base64.NO_WRAP)` / `Base64.encodeToString(bytes, Base64.NO_WRAP)` 的等价实现。
 *
 * Android 的解码器容忍缺失的尾部 `=` 填充，而 `java.util.Base64.getDecoder()` 不容忍，
 * 所以这里先按需补齐再解码。
 */
object Base64NoWrap {

    fun decode(value: String): ByteArray {
        val cleaned = value.filterNot { it == '\n' || it == '\r' }
        val padded = when (cleaned.length % 4) {
            0 -> cleaned
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw ProtocolException("答案数据不是有效的 Base64", e)
        }
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
