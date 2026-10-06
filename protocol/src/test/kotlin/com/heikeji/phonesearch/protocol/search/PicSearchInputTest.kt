package com.heikeji.phonesearch.protocol.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PicSingleSearch / PicPageSearch 的 Input 参数契约。
 *
 * 官方 7.7.0（vc=1810）抓包对齐：这些默认值直接决定搜题能否拿到真答案，
 * 每处改动都必须与抓包样本对得上。
 */
class PicSearchInputTest {

    @Test
    fun `single search input matches the official capture`() {
        val input = PicSingleSearch.Input.buildInput(
            picMd5 = "ABCDEF1234",
            grade = "6",
        )
        assertEquals("/picsearch/submit/singlesearch", input.url)
        assertEquals("", input.pid)

        val params = input.params()
        assertEquals("ABCDEF1234", params["picMD5"])
        assertEquals("", params["shumei"])
        assertEquals("0", params["ref"])
        assertEquals("", params["pageExtraInfo"])
        assertEquals("1", params["referer"])
        assertEquals("1", params["isStudentMode"])
        assertEquals("6", params["grade"])
        assertEquals("otherPage", params["from"])
        assertEquals("{}", params["abtest"])
        assertFalse(params.containsKey("imgCorrection"))
    }

    @Test
    fun `crop refine search carries pageExtraInfo and referer 3`() {
        val input = PicSingleSearch.Input.buildInput(
            picMd5 = "ABCDEF1234",
            grade = "8",
            pageExtraInfo = "{\"sid\":\"x\",\"index\":1,\"loc\":\"1@2@3@4@5@6@7@8\"}",
            referer = PicSingleSearch.Input.REFERER_CROP,
        )
        val params = input.params()
        assertEquals("3", params["referer"])
        assertEquals(
            "{\"sid\":\"x\",\"index\":1,\"loc\":\"1@2@3@4@5@6@7@8\"}",
            params["pageExtraInfo"],
        )
    }

    @Test
    fun `page search input matches the official capture`() {
        val input = PicPageSearch.Input.buildInput(
            picMd5 = "ABCDEF1234",
            grade = "6",
        )
        assertEquals("/picsearch/submit/pagesearch", input.url)
        assertEquals("", input.pid)

        val params = input.params()
        assertEquals("ABCDEF1234", params["picMD5"])
        assertEquals("", params["shumei"])
        assertEquals("0", params["ref"])
        assertEquals("", params["referer"])
        assertEquals("1", params["isStudentMode"])
        assertEquals("6", params["grade"])
        assertEquals("otherPage", params["from"])
        assertEquals("0", params["imgCorrection"])
        assertEquals("{}", params["abtest"])
        assertFalse(params.containsKey("pageExtraInfo"))
    }
}
