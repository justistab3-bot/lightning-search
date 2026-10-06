package com.heikeji.phonesearch.protocol.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备身份域 Input 契约（官方 Getdid / KdapiDeviceGetDigGrade 对齐）。
 */
class IdentityInputTest {

    @Test
    fun `getdid input uses the resource host and official path`() {
        val input = Getdid.Input.buildInput("base64-param")
        assertEquals("/userident/user/getdid", input.url)
        assertEquals("resource", input.pid)
        assertEquals("base64-param", input.params()["param"])
    }

    @Test
    fun `dig grade input matches the official capture`() {
        val input = KdapiDeviceGetDigGrade.Input.buildInput(6)
        assertEquals("/kdapi/device/getdiggrade", input.url)
        assertEquals("", input.pid)
        assertEquals(6, input.params()["grade"])
    }

    @Test
    fun `device info payload round trips through the official entry key`() {
        val payload = DeviceInfo.buildPayload(
            DeviceInfo.Facts(
                appId = "scancode",
                osVersion = "16",
                language = "zh",
                country = "CN",
                brand = "Xiaomi",
                model = "25067PYE3C",
                sn = "unknown",
                androidId = "android-id",
                typewriting = "",
                powerOnTime = 12345L,
                memoryGb = 8,
                hardDiskBytes = 1024,
                screen = "1080*2400",
            ),
        )
        // RC4 后是 Base64；对照老实现逐字段解密校验一次
        val decoded = com.heikeji.phonesearch.protocol.core.crypto.Rc4.apply(
            com.heikeji.phonesearch.protocol.core.codec.Base64NoWrap.decode(payload),
            DeviceInfo.ENTRY_KEY,
        ).toString(Charsets.UTF_8)
        assertTrue(decoded.startsWith("{"))
        assertTrue(decoded.contains("\"appId\":\"scancode\""))
        assertTrue(decoded.contains("\"model\":\"25067PYE3C\""))
        assertTrue(decoded.contains("\"did\":\"\""))
        assertTrue(decoded.contains("\"cpu\":\"armeabi-v7a\""))
    }
}
