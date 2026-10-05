package com.heikeji.phonesearch.protocol.aiwriting

import com.heikeji.phonesearch.protocol.aiwriting.model.AiWritingEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解析 + 事件解析的回归测试。
 *
 * 其中 `real captured stream` 用的是**真实抓包**（`aiwriting-sse-sample.txt`），
 * 包含 3 个增量事件 + 1 个结束事件 + 1 个 close，避免只靠手写样本自说自话。
 */
class AiWritingSseTest {

    // ------------------------------------------------------------------ SSE 解析器

    @Test
    fun `parses id event and data`() {
        val parser = SseParser()
        assertNull(parser.feed("id:7"))
        assertNull(parser.feed("event:msg"))
        assertNull(parser.feed("data:{\"a\":1}"))
        val event = parser.feed("")
        assertNotNull(event)
        assertEquals("7", event!!.id)
        assertEquals("msg", event.event)
        assertEquals("{\"a\":1}", event.data)
    }

    @Test
    fun `joins multiple data lines with newline`() {
        val parser = SseParser()
        parser.feed("data:line1")
        parser.feed("data:line2")
        val event = parser.feed("")
        assertEquals("line1\nline2", event!!.data)
    }

    @Test
    fun `ignores comment lines used as heartbeat`() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed("data:x"))
        assertEquals("x", parser.feed("")!!.data)
    }

    @Test
    fun `strips a single space after the colon`() {
        val parser = SseParser()
        parser.feed("data:  two spaces")
        assertEquals(" two spaces", parser.feed("")!!.data)
    }

    @Test
    fun `consecutive blank lines do not emit empty events`() {
        val parser = SseParser()
        assertNull(parser.feed(""))
        assertNull(parser.feed(""))
        assertNull(parser.feed("data:a"))
        assertNotNull(parser.feed(""))
        assertNull(parser.feed(""))
    }

    @Test
    fun `finish flushes a trailing event without a blank line`() {
        val parser = SseParser()
        parser.feed("event:close")
        parser.feed("data:<nil>")
        val event = parser.finish()
        assertNotNull(event)
        assertEquals("close", event!!.event)
        assertEquals("<nil>", event.data)
    }

    @Test
    fun `finish returns null when nothing pending`() {
        val parser = SseParser()
        parser.feed("data:a")
        parser.feed("")
        assertNull(parser.finish())
    }

    // ------------------------------------------------------------------ 真实抓包

    private fun feedSample(): List<AiWritingEvent> {
        val text = javaClass.getResourceAsStream("/aiwriting-sse-sample.txt")!!
            .bufferedReader()
            .readText()
        val parser = SseParser()
        val out = ArrayList<AiWritingEvent>()
        for (line in text.split("\n")) {
            parser.feed(line.trimEnd('\r'))?.let { out.addAll(AiWritingEventParser.parse(it)) }
        }
        parser.finish()?.let { out.addAll(AiWritingEventParser.parse(it)) }
        return out
    }

    @Test
    fun `real captured stream yields deltas, one finished and one closed`() {
        val events = feedSample()
        val deltas = events.filterIsInstance<AiWritingEvent.Delta>()
        val finished = events.filterIsInstance<AiWritingEvent.Finished>()
        val closed = events.filterIsInstance<AiWritingEvent.Closed>()

        assertEquals("增量事件数", 3, deltas.size)
        assertEquals("结束事件数", 1, finished.size)
        assertEquals("close 事件数", 1, closed.size)
        assertTrue(
            "不应有未识别事件",
            events.filterIsInstance<AiWritingEvent.Unknown>().isEmpty(),
        )
    }

    @Test
    fun `finished article carries the full segmented text`() {
        val article = feedSample()
            .filterIsInstance<AiWritingEvent.Finished>()
            .single()
            .article

        assertEquals("我的暑假", article.title)
        assertEquals("记叙文", article.queryType)
        assertEquals("记叙文-叙事", article.articleType)
        assertTrue("应有分段", article.paragraphs.size >= 5)
        assertTrue("字数应为正", article.wordCount > 0)
        assertTrue("sid 不应为空", article.sid.isNotEmpty())
        assertTrue("正文不应为空", article.text.length > 300)

        // 分段顺序与 paraIndex 一致
        article.paragraphs.forEachIndexed { index, paragraph ->
            assertEquals(index, paragraph.paraIndex)
            assertTrue(paragraph.content.isNotBlank())
        }
    }

    @Test
    fun `delta pieces are incremental not cumulative`() {
        val deltas = feedSample().filterIsInstance<AiWritingEvent.Delta>()
        // 每个片段都很短；若是全量快照，后面的会越滚越长
        assertTrue("片段应很短", deltas.all { it.text.length <= 8 })
    }

    // ------------------------------------------------------------------ 事件解析边界

    @Test
    fun `close event maps to Closed`() {
        val events = AiWritingEventParser.parse(SseEvent("512", "close", "<nil>"))
        assertEquals(listOf<AiWritingEvent>(AiWritingEvent.Closed), events)
    }

    @Test
    fun `malformed json becomes Unknown instead of throwing`() {
        val events = AiWritingEventParser.parse(SseEvent("1", "msg", "<html>oops"))
        assertEquals(1, events.size)
        assertTrue(events[0] is AiWritingEvent.Unknown)
    }

    @Test
    fun `more flag one never produces Finished`() {
        val events = AiWritingEventParser.parse(
            SseEvent("1", "msg", """{"more":1,"articleResult":[{"content":"当"}]}"""),
        )
        assertEquals(1, events.size)
        assertEquals("当", (events[0] as AiWritingEvent.Delta).text)
    }

    @Test
    fun `finished falls back to articleResult when session is absent`() {
        val events = AiWritingEventParser.parse(
            SseEvent(
                "2",
                "msg",
                """{"more":0,"title":"T","articleResult":[{"content":"兜底正文"}]}""",
            ),
        )
        val finished = events.filterIsInstance<AiWritingEvent.Finished>().single()
        assertEquals("兜底正文", finished.article.text)
        assertEquals(1, finished.article.paragraphs.size)
    }

    @Test
    fun `unknown event name is preserved`() {
        val events = AiWritingEventParser.parse(SseEvent("9", "heartbeat", "{}"))
        val unknown = events.single() as AiWritingEvent.Unknown
        assertEquals("heartbeat", unknown.event)
    }

    // ------------------------------------------------------------------ 英语作文（真实抓包）

    private fun feedResource(path: String): List<AiWritingEvent> {
        val text = javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
        val parser = SseParser()
        val out = ArrayList<AiWritingEvent>()
        for (line in text.split("\n")) {
            parser.feed(line.trimEnd('\r'))?.let { out.addAll(AiWritingEventParser.parse(it)) }
        }
        parser.finish()?.let { out.addAll(AiWritingEventParser.parse(it)) }
        return out
    }

    @Test
    fun `english stream uses the same event shape as chinese`() {
        val events = feedResource("/aiwriting-sse-english-sample.txt")
        assertEquals(2, events.filterIsInstance<AiWritingEvent.Delta>().size)
        assertEquals(1, events.filterIsInstance<AiWritingEvent.Finished>().size)
        assertEquals(1, events.filterIsInstance<AiWritingEvent.Closed>().size)
        assertTrue(events.filterIsInstance<AiWritingEvent.Unknown>().isEmpty())
    }

    @Test
    fun `english article is segmented and has no articleType`() {
        val article = feedResource("/aiwriting-sse-english-sample.txt")
            .filterIsInstance<AiWritingEvent.Finished>()
            .single()
            .article

        assertEquals("My Summer Holiday", article.title)
        assertEquals("", article.articleType)
        assertEquals(3, article.paragraphs.size)
        assertTrue(article.wordCount > 0)
        assertTrue(article.text.contains("summer holiday", ignoreCase = true))
    }

    @Test
    fun `english deltas are whole words not single characters`() {
        val deltas = feedResource("/aiwriting-sse-english-sample.txt")
            .filterIsInstance<AiWritingEvent.Delta>()
        // 英语的片段是单词，比中文的 1-2 字长
        assertTrue("英语片段应长于单个字母", deltas.any { it.text.length > 2 })
    }

    // ------------------------------------------------------------------ markdown 标题

    @Test
    fun `leading markdown heading is lifted out of the body`() {
        val article = AiWritingEventParser.parse(
            SseEvent(
                "1",
                "msg",
                """{"more":0,"title":"为什么这世界上有黑与白","session":{"selected":[""" +
                    """{"paragraph_id":"a","content":"# 为什么这世界上有黑与白"},""" +
                    """{"paragraph_id":"b","content":"昨天上美术课，老师让我们画我的家。"}]}}""",
            ),
        ).filterIsInstance<AiWritingEvent.Finished>().single().article

        assertEquals("为什么这世界上有黑与白", article.heading)
        assertEquals("为什么这世界上有黑与白", article.displayTitle)
        assertEquals("标题应从正文里移除", 1, article.paragraphs.size)
        assertFalse("正文不应再带 #", article.text.contains("#"))
    }

    @Test
    fun `heading of any level is recognized`() {
        for (marker in listOf("#", "##", "###", "######")) {
            val article = AiWritingEventParser.parse(
                SseEvent(
                    "1",
                    "msg",
                    """{"more":0,"session":{"selected":[""" +
                        """{"content":"$marker 标题"},""" +
                        """{"content":"正文"}]}}""",
                ),
            ).filterIsInstance<AiWritingEvent.Finished>().single().article
            assertEquals("$marker 未识别", "标题", article.heading)
            assertEquals(1, article.paragraphs.size)
        }
    }

    @Test
    fun `ordinary first paragraph is left alone`() {
        val article = AiWritingEventParser.parse(
            SseEvent(
                "1",
                "msg",
                """{"more":0,"title":"我的暑假","session":{"selected":[""" +
                    """{"content":"当考试结束的铃声响起。"},""" +
                    """{"content":"暑假开始了。"}]}}""",
            ),
        ).filterIsInstance<AiWritingEvent.Finished>().single().article

        assertEquals("", article.heading)
        assertEquals("没有标题时回落到 title", "我的暑假", article.displayTitle)
        assertEquals(2, article.paragraphs.size)
    }

    @Test
    fun `hash inside a paragraph is not treated as a heading`() {
        val article = AiWritingEventParser.parse(
            SseEvent(
                "1",
                "msg",
                """{"more":0,"session":{"selected":[""" +
                    """{"content":"话题 #1 是这么回事"}]}}""",
            ),
        ).filterIsInstance<AiWritingEvent.Finished>().single().article

        assertEquals("", article.heading)
        assertEquals(1, article.paragraphs.size)
    }
}
