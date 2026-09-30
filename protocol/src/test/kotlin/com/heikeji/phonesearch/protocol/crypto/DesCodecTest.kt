package com.heikeji.phonesearch.protocol.crypto

import com.heikeji.phonesearch.protocol.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DesCodec 的固定向量测试。
 *
 * 向量来自 tools/des-oracle/OriginalDes.java —— 那是原 APK P0.e 的逐字 Java 副本，
 * 与这里的 Kotlin 移植版相互独立，用于抓出移植笔误。
 */
class DesCodecTest {

    private fun readResource(name: String): List<String> =
        javaClass.getResourceAsStream(name)
            ?.bufferedReader(Charsets.UTF_8)
            ?.readLines()
            ?: error("missing test resource $name")

    @Test
    fun `encode and decode match the original oracle vectors`() {
        var checked = 0
        for (line in readResource("/des-vectors.tsv")) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split("\t")
            assertEquals("malformed vector line: $line", 3, parts.size)
            val (plain, key, cipher) = parts

            assertEquals("encode mismatch for plain='$plain' key='$key'", cipher, DesCodec.encode(plain, key))
            assertEquals("decode mismatch for cipher='$cipher' key='$key'", plain, DesCodec.decode(cipher, key))
            checked++
        }
        assertTrue("expected a substantial vector corpus, got $checked", checked > 300)
    }

    @Test
    fun `signB vectors yield the expected device secret`() {
        var checked = 0
        for (line in readResource("/signb-vectors.tsv")) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split("\t")
            assertEquals("malformed vector line: $line", 4, parts.size)
            val (cuid, signA, signB, deviceSecret) = parts

            assertEquals(deviceSecret, com.heikeji.phonesearch.protocol.sign.SignA.parseDeviceSecret(cuid, signA, signB))
            assertEquals(
                "device secret must round-trip through the signA plaintext layout",
                signA,
                DesCodec.encode(
                    "8&%d*##" + DesCodec.decode(signA, "@fG2SuLA").substring(7, 17) +
                        "##" + com.heikeji.phonesearch.protocol.ProtocolProfile.CERTIFICATE_DIGEST +
                        "##" + cuid,
                    "@fG2SuLA",
                ),
            )
            checked++
        }
        assertTrue(checked >= 4)
    }

    @Test
    fun `cipher length is four characters per padded byte`() {
        for (length in 1..32) {
            val plain = "A".repeat(length)
            val cipher = DesCodec.encode(plain, "@fG2SuLA")
            // 补齐规则：padLen = 8 - (len % 8)，整除时补满 8 字节，所以块数恒为 len/8 + 1。
            val blocks = length / 8 + 1
            assertEquals(blocks * 8 * 4, cipher.length)
            assertTrue(cipher.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }

    @Test
    fun `rejects empty and non printable plaintext`() {
        assertThrowsProtocolException { DesCodec.encode("", "@fG2SuLA") }
        assertThrowsProtocolException { DesCodec.encode("ab\u00e9", "@fG2SuLA") }
        assertThrowsProtocolException { DesCodec.encode("a\tb", "@fG2SuLA") }
    }

    @Test
    fun `rejects malformed ciphertext`() {
        // 空串
        assertThrowsProtocolException { DesCodec.decode("", "@fG2SuLA") }
        // 长度不是 32 的倍数
        assertThrowsProtocolException { DesCodec.decode("0000", "@fG2SuLA") }
        // 每 4 字符组的第 1、3 位必须是 '0'
        val valid = DesCodec.encode("ABCDEFGH", "@fG2SuLA")
        assertThrowsProtocolException { DesCodec.decode("1" + valid.substring(1), "@fG2SuLA") }
        // 非法 hex 字符
        assertThrowsProtocolException { DesCodec.decode("0z" + valid.substring(2), "@fG2SuLA") }
    }

    @Test
    fun `decode rejects ciphertext produced with a different key`() {
        val cipher = DesCodec.encode("8&%d*##Ab3xY9zQ1w##x##cuid|0", "@fG2SuLA")
        assertThrowsProtocolException { DesCodec.decode(cipher, "ABCDE#G4") }
    }

    private fun assertThrowsProtocolException(block: () -> Unit) {
        try {
            block()
        } catch (expected: ProtocolException) {
            return
        }
        throw AssertionError("expected ProtocolException")
    }
}
