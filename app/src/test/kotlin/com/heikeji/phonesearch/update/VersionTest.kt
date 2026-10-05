package com.heikeji.phonesearch.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较的回归测试。
 *
 * 这段逻辑错了的后果很隐蔽：要么永远提示「已是最新」，要么每次都提示更新。
 * 注意 `1.10.0` 必须大于 `1.9.0`——按字符串比会得到相反的结果。
 */
class VersionTest {

    @Test
    fun `numeric segments compare as numbers not strings`() {
        assertTrue(Version.isNewer("1.10.0", "1.9.0"))
        assertTrue(Version.isNewer("1.12.0", "1.11.0"))
        assertTrue(Version.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `v prefix is ignored`() {
        assertEquals(0, Version.compare("v1.11.0", "1.11.0"))
        assertEquals(0, Version.compare("V1.11.0", "v1.11.0"))
        assertTrue(Version.isNewer("v1.12.0", "1.11.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(Version.isNewer("1.11.0", "1.11.0"))
        assertFalse(Version.isNewer("v1.11.0", "1.11.0"))
    }

    @Test
    fun `older versions are not newer`() {
        assertFalse(Version.isNewer("1.10.0", "1.11.0"))
        assertFalse(Version.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(0, Version.compare("1.11", "1.11.0"))
        assertTrue(Version.isNewer("1.11.1", "1.11"))
        assertFalse(Version.isNewer("1.11", "1.11.0"))
    }

    @Test
    fun `suffixes are tolerated`() {
        assertTrue(Version.isNewer("1.12.0-beta", "1.11.0"))
        assertTrue(Version.isNewer("1.12.0+build3", "1.11.0"))
        assertEquals(0, Version.compare("1.12.0-beta", "1.12.0"))
    }

    @Test
    fun `garbage does not crash and never claims newer`() {
        assertFalse(Version.isNewer("", "1.11.0"))
        assertFalse(Version.isNewer("latest", "1.11.0"))
        assertEquals(0, Version.compare("", ""))
    }
}
