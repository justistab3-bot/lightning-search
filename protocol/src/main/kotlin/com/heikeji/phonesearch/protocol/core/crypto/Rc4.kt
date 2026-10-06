package com.heikeji.phonesearch.protocol.core.crypto

import java.nio.charset.StandardCharsets

/**
 * 标准 RC4（KSA + PRGA），对应原实现的 f1.b.z / f1.b.G。
 *
 * 密钥按 UTF-8 取字节；协议内实际密钥是 128 字符小写 hex，ASCII 与 UTF-8 等价。
 */
object Rc4 {

    fun apply(data: ByteArray, key: String): ByteArray =
        apply(data, key.toByteArray(StandardCharsets.UTF_8))

    fun apply(data: ByteArray, key: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "RC4 key must not be empty" }

        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }

        val out = ByteArray(data.size)
        var i = 0
        j = 0
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }
}
