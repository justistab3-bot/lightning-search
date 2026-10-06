package com.heikeji.phonesearch.protocol.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * responseKey 派生。
 *
 * 期望值是独立算出来的：用 .NET 的 MD5 按同样的步骤重算了一遍，不取自 Kotlin 实现。
 */
class ResponseKeyTest {

    /**
     * 独立期望值：deviceSecret = "9fK2mQ7xLp"，VC=1810。
     *
     * 用 Python 按同样的 MD5+交换步骤独立重算，不取自 Kotlin 实现。
     * （VC=1170 的旧期望值为 2dbfba7d51d4b2f873a5ad84ef28502d…，公式本身没变。）
     */
    private val expected =
        "c6acf04db2a0098a7246f09db43a18fd" +
            "6a5b948fd556709227dec541fa5632d2" +
            "fa363a37cae4b52e4572ceab50de97e6" +
            "31cd22941af1d9898bc5ba70303e7f05"

    @Test
    fun `derives the expected 128 hex key`() {
        val key = ResponseKey.derive("9fK2mQ7xLp")
        assertEquals(expected, key)
        assertEquals(128, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{128}")))
    }

    @Test
    fun `is deterministic and sensitive to the device secret`() {
        assertEquals(ResponseKey.derive("9fK2mQ7xLp"), ResponseKey.derive("9fK2mQ7xLp"))
        assertNotEquals(ResponseKey.derive("9fK2mQ7xLp"), ResponseKey.derive("9fK2mQ7xLq"))
        assertNotEquals(ResponseKey.derive("9fK2mQ7xLp"), ResponseKey.derive("0123456789"))
    }

    @Test
    fun `works for every device secret in the oracle corpus`() {
        for (secret in listOf("9fK2mQ7xLp", "0123456789")) {
            val key = ResponseKey.derive(secret)
            assertEquals(128, key.length)
            assertTrue(key.matches(Regex("[0-9a-f]{128}")))
        }
    }
}
