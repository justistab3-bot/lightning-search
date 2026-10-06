package com.heikeji.phonesearch.protocol.core.sign

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.core.crypto.DesCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignATest {

    private val cuid = "3F2A1B4C5D6E7F8091A2B3C4D5E6F701|0"
    private val random10 = "Ab3xY9zQ1w"
    private val deviceSecret = "9fK2mQ7xLp"

    private fun signA() = SignA.build(cuid, random10)

    private fun signB(secret: String = deviceSecret) =
        DesCodec.encode(
            random10 + "xy" + secret,
            random10.substring(0, 5) + NetConfig.SIGN_B_KEY_SUFFIX,
        )

    @Test
    fun `random10 has the required shape`() {
        repeat(200) {
            val value = SignA.random10()
            assertEquals(10, value.length)
            assertTrue(value.matches(Regex("[A-Za-z0-9]{10}")))
        }
    }

    @Test
    fun `build produces the plaintext layout the server expects`() {
        val cipher = signA()
        val plainLength = NetConfig.SIGN_A_PLAIN_PREFIX.length + 10 + 2 +
            NetConfig.CERTIFICATE_DIGEST.length + 2 + cuid.length
        assertEquals(plainLength, 53 + cuid.length)
        assertEquals(((plainLength / 8) + 1) * 8 * 4, cipher.length)

        val decoded = DesCodec.decode(cipher, NetConfig.SIGN_A_KEY)
        assertEquals(NetConfig.SIGN_A_PLAIN_PREFIX, decoded.substring(0, 7))
        assertEquals(random10, decoded.substring(7, 17))
        assertEquals("##", decoded.substring(17, 19))
        assertEquals(NetConfig.CERTIFICATE_DIGEST, decoded.substring(19, 51))
        assertEquals("##", decoded.substring(51, 53))
        assertEquals(cuid, decoded.substring(53))
    }

    @Test
    fun `round trips signA and signB into the device secret`() {
        assertEquals(deviceSecret, SignA.parseDeviceSecret(cuid, signA(), signB()))
    }

    @Test
    fun `rejects a signB bound to a different random10`() {
        val otherRandom10 = "q7Wm2Zp0Kd"
        val foreignSignB = DesCodec.encode(
            otherRandom10 + "xy" + deviceSecret,
            otherRandom10.substring(0, 5) + NetConfig.SIGN_B_KEY_SUFFIX,
        )
        assertThrows { SignA.parseDeviceSecret(cuid, signA(), foreignSignB) }
    }

    @Test
    fun `rejects a signA bound to a different cuid`() {
        assertThrows { SignA.parseDeviceSecret("AABBCCDDEEFF00112233445566778899|0", signA(), signB()) }
    }

    @Test
    fun `rejects tampered inputs`() {
        val cipher = signA()
        val tampered = cipher.substring(0, cipher.length - 1) + if (cipher.last() == 'f') 'e' else 'f'
        assertThrows { SignA.parseDeviceSecret(cuid, tampered, signB()) }
        assertThrows { SignA.parseDeviceSecret(cuid, "", signB()) }
        assertThrows { SignA.parseDeviceSecret(cuid, signA(), "") }
        assertThrows { SignA.build("", random10) }
        assertThrows { SignA.build(cuid, "short") }
        assertThrows { SignA.build(cuid, "has space!") }
    }

    @Test
    fun `rejects a device secret that does not satisfy the 22 character layout`() {
        // 长度不足 22 的 signB 明文
        val shortSignB = DesCodec.encode(random10 + "x", random10.substring(0, 5) + NetConfig.SIGN_B_KEY_SUFFIX)
        assertThrows { SignA.parseDeviceSecret(cuid, signA(), shortSignB) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (expected: ProtocolException) {
            return
        }
        throw AssertionError("expected ProtocolException")
    }
}
