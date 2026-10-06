package com.heikeji.phonesearch.protocol.core.crypto

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/**
 * 摘要工具（对应原实现的 P0.e.a 与图片 picMD5 的大小写要求）。
 *
 * 注意大小写：协议签名、responseKey 一律小写 hex；图片 picMD5 必须大写 hex。
 */
object Digests {

    private val LOWER = "0123456789abcdef".toCharArray()
    private val UPPER = "0123456789ABCDEF".toCharArray()

    /**
     * 小写 MD5。原实现用 US-ASCII 取字节，协议输入全部是可打印 ASCII，故与 UTF-8 等价。
     */
    fun md5Lower(input: String): String =
        hex(md5(input.toByteArray(StandardCharsets.US_ASCII)), LOWER)

    /** 大写 MD5，用于图片 picMD5。 */
    fun md5Upper(bytes: ByteArray): String = hex(md5(bytes), UPPER)

    private fun md5(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(bytes)

    private fun hex(bytes: ByteArray, table: CharArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = table[v ushr 4]
            out[i * 2 + 1] = table[v and 0x0F]
        }
        return String(out)
    }
}
