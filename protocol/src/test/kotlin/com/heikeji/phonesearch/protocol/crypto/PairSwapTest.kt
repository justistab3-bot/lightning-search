package com.heikeji.phonesearch.protocol.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * swapOuterPairs 的行为与边界（含 n 超过长度一半、n == 长度）。
 */
class PairSwapTest {

    private fun swap(input: String, count: Int): String {
        val chars = input.toCharArray()
        PairSwap.swap(chars, count)
        return String(chars)
    }

    @Test
    fun `swaps outer pairs in order`() {
        assertEquals("abcdefgh", swap("abcdefgh", 0))
        assertEquals("hbcdefga", swap("abcdefgh", 1))
        assertEquals("hgcdefba", swap("abcdefgh", 2))
        assertEquals("hgfedcba", swap("abcdefgh", 4))
    }

    @Test
    fun `n beyond half length swaps pairs back`() {
        assertEquals("hgfdecba", swap("abcdefgh", 5))
        assertEquals("hgcdefba", swap("abcdefgh", 6))
        assertEquals("hbcdefga", swap("abcdefgh", 7))
        assertEquals("abcdefgh", swap("abcdefgh", 8))
    }

    @Test
    fun `reverses when count equals half the length`() {
        val input = "0123456789abcdef"
        assertEquals(input.reversed(), swap(input, input.length / 2))
    }

    @Test
    fun `supports the exact call shapes used by the protocol`() {
        // responseKey 派生：32 长度 swap 15，96 长度 swap 3，128 长度 swap 60
        swap("0".repeat(32), 15)
        swap("0".repeat(96), 3)
        swap("0".repeat(128), 60)
    }
}
