package com.heikeji.phonesearch.protocol.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * RC4 已知向量。
 *
 * 前三条是公开的标准向量；第四条用真实 responseKey 加密登录内层明文，
 * 期望值由 .NET 独立实现算出（见计划 §7）。
 */
class Rc4Test {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    private fun bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `matches published rc4 vectors`() {
        assertEquals("BBF316E8D940AF0AD3", hex(Rc4.apply(bytes("Plaintext"), "Key")))
        assertEquals("1021BF0420", hex(Rc4.apply(bytes("pedia"), "Wiki")))
        assertEquals("45A01F645FC35B383552544B9BF5", hex(Rc4.apply(bytes("Attack at dawn"), "Secret")))
    }

    @Test
    fun `matches an independently computed responseKey vector`() {
        val key = "2dbfba7d51d4b2f873a5ad84ef28502d6a5b948fd556709227dec541fa562658" +
            "fa36556fa01e9653f13a2a3926f17be331cd22941af1d9898bc5ba70303e7f05"
        val plain = "&phone=13800000000&tokenCode=1234"
        val expected = "CBF105C5617549002DB7F2E5BB9F946D857ABA6D1996C01B007B41D126F37EB53A"
        assertEquals(expected, hex(Rc4.apply(bytes(plain), key)))
    }

    @Test
    fun `is an involution`() {
        val key = "2dbfba7d51d4b2f873a5ad84ef28502d6a5b948fd556709227dec541fa562658"
        val plain = bytes("&phone=13800000000&tokenCode=1234&inviteCode=")
        val once = Rc4.apply(plain, key)
        val twice = Rc4.apply(once, key)
        assertTrue(plain.contentEquals(twice))
    }

    @Test
    fun `handles empty input and long keys`() {
        assertEquals("", hex(Rc4.apply(ByteArray(0), "Key")))
        val long = Rc4.apply(bytes("x"), "k".repeat(4096))
        assertEquals(1, long.size)
    }
}
