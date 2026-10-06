package com.heikeji.phonesearch.protocol.core.sign

import com.heikeji.phonesearch.protocol.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通用请求签名。
 *
 * 期望值由 .NET 独立实现算出（同样的 canonical / Base64 / MD5 步骤），不取自 Kotlin 实现。
 */
class RequestSignerTest {

    private val deviceSecretDigest = "065ada3b4483d3f8293f9b95ab1efd81"
    private val tSeconds = 1_750_000_000L
    private val uptimeMillis = 123_456_789L

    private val items = listOf(
        "picMD5=ABC123DEF456",
        "shumei=",
        "ref=1",
        "pageExtraInfo=",
        "referer=1",
        "isStudentMode=1",
        "grade=9",
        "from=homePage",
        "cuid=3F2A1B4C5D6E7F8091A2B3C4D5E6F701|0",
    )

    private fun sign(values: List<String>) =
        RequestSigner.sign(values, deviceSecretDigest, tSeconds, uptimeMillis)

    @Test
    fun `matches the independently computed signature`() {
        assertEquals("9b72b23a7d7b8795fd54493f0fd3a408", sign(items))
    }

    @Test
    fun `is order independent because items are sorted`() {
        assertEquals(sign(items), sign(items.reversed()))
        assertEquals(sign(items), sign(items.shuffled(kotlin.random.Random(42))))
    }

    @Test
    fun `is sensitive to every input component`() {
        assertTrue(sign(items) != sign(items + "extra=1"))
        assertTrue(
            sign(items) !=
                RequestSigner.sign(items, "00000000000000000000000000000000", tSeconds, uptimeMillis),
        )
        assertTrue(
            sign(items) !=
                RequestSigner.sign(items, deviceSecretDigest, tSeconds + 1, uptimeMillis),
        )
        assertTrue(
            sign(items) !=
                RequestSigner.sign(items, deviceSecretDigest, tSeconds, uptimeMillis + 1),
        )
    }

    @Test
    fun `produces lowercase hex`() {
        assertTrue(sign(items).matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `rejects reserved and malformed items`() {
        for (bad in listOf("sign=deadbeef", "_t_=1", "kakorrhaphiophobia=1")) {
            try {
                sign(items + bad)
                throw AssertionError("expected rejection of $bad")
            } catch (expected: ProtocolException) {
                // ok
            }
        }
        try {
            sign(items + "x=a\u0000b")
            throw AssertionError("expected rejection of NUL")
        } catch (expected: ProtocolException) {
            // ok
        }
    }

    @Test
    fun `usability check mirrors the original guard`() {
        assertTrue(RequestSigner.isUsable("9b72b23a7d7b8795fd54493f0fd3a408"))
        assertFalse(RequestSigner.isUsable(""))
        assertFalse(RequestSigner.isUsable("error"))
        assertFalse(RequestSigner.isUsable("errorSomething"))
        assertFalse(RequestSigner.isUsable("init_error"))
        assertFalse(RequestSigner.isUsable("so_error"))
        assertFalse(RequestSigner.isUsable("abc&def"))
    }

    @Test
    fun `toItems keeps insertion order and renders nulls as empty`() {
        val params = linkedMapOf<String, String?>(
            "phone" to "13800000000",
            "tokenCode" to "1234",
            "inviteCode" to null,
        )
        assertEquals(listOf("phone=13800000000", "tokenCode=1234", "inviteCode="), RequestSigner.toItems(params))
    }
}
