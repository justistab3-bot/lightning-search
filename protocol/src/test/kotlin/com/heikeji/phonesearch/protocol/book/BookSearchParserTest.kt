package com.heikeji.phonesearch.protocol.book

import com.heikeji.phonesearch.protocol.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「查看整本答案」解析测试。
 *
 * 结构对照原生 `SearchBookSearch`（`/search/submit/booksearch` 的响应）。
 */
class BookSearchParserTest {

    private fun parse(json: String) = BookSearchParser.parseJson(json)

    // ------------------------------------------------------------------ answerList（首选来源）

    @Test
    fun `answerList becomes the page list`() {
        val result = parse(
            """
            {"bookId":"d7430b88","name":"高中化学 人教版","subject":"化学","grade":"高中",
             "term":"全册","version":"人教版","hasAnswer":1,
             "answerList":[
               {"origin":"https://cdn/scan_a.jpg","thumbnail":"https://cdn/scan_a_t.jpg","w":1200,"h":1600,"isHD":true},
               {"origin":"https://cdn/scan_b.jpg","thumbnail":"https://cdn/scan_b_t.jpg","w":1200,"h":1600,"isHD":false}
             ]}
            """.trimIndent(),
        )

        assertEquals("d7430b88", result.bookId)
        assertEquals("高中化学 人教版", result.name)
        assertEquals("化学", result.subject)
        assertEquals(2, result.pages.size)
        assertTrue(result.hasAnswer)

        val first = result.pages[0]
        assertEquals("https://cdn/scan_a.jpg", first.origin)
        assertEquals("https://cdn/scan_a_t.jpg", first.thumbnail)
        assertEquals(1200, first.width)
        assertEquals(1600, first.height)
        assertTrue(first.isHd)
        assertEquals("https://cdn/scan_a_t.jpg", first.previewUrl)
        assertEquals("https://cdn/scan_a.jpg", first.fullUrl)

        assertFalse(result.pages[1].isHd)
    }

    @Test
    fun `page falls back to origin when thumbnail is missing`() {
        val page = parse(
            """{"answerList":[{"origin":"https://cdn/only.jpg","w":10,"h":10}]}""",
        ).pages.single()
        assertEquals("https://cdn/only.jpg", page.previewUrl)
        assertEquals("https://cdn/only.jpg", page.fullUrl)
    }

    @Test
    fun `blank entries are skipped`() {
        val result = parse(
            """{"answerList":[{"origin":"","thumbnail":""},{"origin":"https://cdn/ok.jpg"}]}""",
        )
        assertEquals(1, result.pages.size)
        assertEquals("https://cdn/ok.jpg", result.pages.single().origin)
    }

    // ------------------------------------------------------------------ answers 退路

    @Test
    fun `falls back to answers and oriAnswers when answerList is absent`() {
        val result = parse(
            """{"bookId":"b1","answers":["t1.jpg","t2.jpg"],"oriAnswers":["o1.jpg","o2.jpg"]}""",
        )
        assertEquals(2, result.pages.size)
        assertEquals("t1.jpg", result.pages[0].thumbnail)
        assertEquals("o1.jpg", result.pages[0].origin)
        assertEquals("t2.jpg", result.pages[1].thumbnail)
        assertEquals("o2.jpg", result.pages[1].origin)
    }

    @Test
    fun `uneven answers lists still line up`() {
        val result = parse("""{"answers":["t1.jpg"],"oriAnswers":["o1.jpg","o2.jpg"]}""")
        assertEquals(2, result.pages.size)
        assertEquals("", result.pages[1].thumbnail)
        assertEquals("o2.jpg", result.pages[1].origin)
    }

    @Test
    fun `answerList wins over the flattened lists`() {
        val result = parse(
            """
            {"answers":["stale_t.jpg"],"oriAnswers":["stale_o.jpg"],
             "answerList":[{"origin":"fresh.jpg","thumbnail":"fresh_t.jpg"}]}
            """.trimIndent(),
        )
        assertEquals(1, result.pages.size)
        assertEquals("fresh.jpg", result.pages.single().origin)
    }

    // ------------------------------------------------------------------ 边界

    @Test
    fun `hasAnswer is true when pages exist even if the flag says otherwise`() {
        val result = parse("""{"hasAnswer":0,"answerList":[{"origin":"a.jpg"}]}""")
        assertTrue(result.hasAnswer)
    }

    @Test
    fun `hasAnswer is false when the book has no pages`() {
        val result = parse("""{"bookId":"x","hasAnswer":0}""")
        assertTrue(result.isEmpty)
        assertFalse(result.hasAnswer)
    }

    @Test
    fun `requirePages throws on an empty book`() {
        val root = Json.parseObject("""{"bookId":"x"}""", "bad")
        try {
            BookSearchParser.requirePages(root)
            throw AssertionError("应当抛异常")
        } catch (e: com.heikeji.phonesearch.protocol.ProtocolException) {
            assertTrue(e.message!!.contains("没有可查看的答案"))
        }
    }

    @Test
    fun `missing optional fields do not break parsing`() {
        val result = parse("""{"answerList":[{"origin":"a.jpg"}]}""")
        assertEquals("", result.name)
        assertEquals("", result.subject)
        assertEquals("", result.cover)
        assertEquals(0, result.pages.single().width)
    }

    // ------------------------------------------------------------------ 整页响应里的教材信息

    @Test
    fun `related book is read from the data level`() {
        val info = BookSearchParser.relatedBookOf(
            Json.parseObject(
                """{"sid":"s","relatedBook":{"bookId":"bk1","pageId":"pg1"}}""",
                "bad",
            ),
        )!!
        assertEquals("bk1", info.bookId)
        assertEquals("pg1", info.pageId)
        assertTrue(info.isUsable)
    }

    @Test
    fun `related book falls back to the answers level`() {
        val info = BookSearchParser.relatedBookOf(
            Json.parseObject(
                """{"sid":"s","answers":{"relatedBook":{"bookId":"bk2"}}}""",
                "bad",
            ),
        )!!
        assertEquals("bk2", info.bookId)
    }

    @Test
    fun `top level bookId is accepted when relatedBook is absent`() {
        val info = BookSearchParser.relatedBookOf(
            Json.parseObject("""{"bookId":"bk3","pageId":"pg3"}""", "bad"),
        )!!
        assertEquals("bk3", info.bookId)
        assertEquals("pg3", info.pageId)
    }

    @Test
    fun `related book is null when the server sends nothing usable`() {
        assertNull(BookSearchParser.relatedBookOf(Json.parseObject("""{"sid":"s"}""", "bad")))
        assertNull(
            BookSearchParser.relatedBookOf(
                Json.parseObject("""{"relatedBook":{"bookId":"","pageId":""}}""", "bad"),
            ),
        )
    }
}
