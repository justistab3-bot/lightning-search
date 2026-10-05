package com.heikeji.phonesearch.protocol.aiwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求构造的回归测试。
 *
 * 重点是**文体必须走 `describe`**：实测把 `queryType` 改成「诗歌」，
 * 服务端只回显不生效（`articleType` 仍是「记叙文-叙事」），
 * 而 `describe` 里的写作要求是真会被采纳的。这些用例把这个结论钉住。
 */
class AiWritingRequestTest {

    @Test
    fun `requirements carry both grade and genre`() {
        val text = AiWritingRequest.writingRequirements(11, "议论文")
        assertTrue("应含年级", text.contains("高二"))
        assertTrue("应含文体", text.contains("议论文"))
        assertTrue("应含写法要求", text.contains("中心论点"))
        assertTrue("应以写作要求开头", text.startsWith("写作要求："))
    }

    @Test
    fun `requirements without genre only carry the grade`() {
        val text = AiWritingRequest.writingRequirements(6, null)
        assertTrue(text.contains("六年级"))
        assertFalse("没选文体就不该出现写法要求", text.contains("写成"))
    }

    @Test
    fun `blank genre is treated as auto`() {
        val text = AiWritingRequest.writingRequirements(6, "   ")
        assertFalse(text.contains("写成"))
    }

    @Test
    fun `every genre produces a non-empty requirement`() {
        for (genre in AiWritingRequest.GENRES) {
            val text = AiWritingRequest.writingRequirements(11, genre)
            assertTrue("$genre 没有生成要求", text.contains(genre))
        }
    }

    @Test
    fun `unknown grade still yields the genre requirement`() {
        val text = AiWritingRequest.writingRequirements(999, "记叙文")
        assertFalse("未知年级不该编出年级要求", text.contains("学生"))
        assertTrue(text.contains("记叙文"))
    }

    @Test
    fun `preInstantBody puts the requirement into describe`() {
        val body = AiWritingRequest.preInstantBody(
            title = "我的暑假",
            queryType = "议论文",
            wordCount = "800+",
            gradeId = 11,
            writeDate = 0L,
            language = EssayLanguage.CHINESE,
            describe = AiWritingRequest.writingRequirements(11, "议论文"),
        )
        assertTrue("describe 应带上写法要求", body.contains("中心论点"))
        assertTrue(body.contains("高二"))
        assertTrue("queryType 仍按原样传", body.contains("议论文"))
        assertTrue("language 中文应为 1", body.contains("\"language\":1"))
    }

    @Test
    fun `instantQuery encodes describe so it survives the URL`() {
        val query = AiWritingRequest.instantQuery(
            sid = "s",
            cuid = "c",
            sessionId = "ss",
            title = "我的暑假",
            wordCount = "800+",
            gradeId = 11,
            language = EssayLanguage.CHINESE,
            describe = AiWritingRequest.writingRequirements(11, "议论文"),
        )
        // 中文与标点必须编码，否则请求行会被截断
        assertFalse("不应出现裸中文", query.contains("议论文"))
        assertTrue("应出现编码后的 describe", query.contains("describe="))
        assertTrue("加号要编码成 %2B", query.contains("wordCount=800%2B"))
    }

    @Test
    fun `english uses its own endpoints and language values`() {
        assertEquals(
            AiWritingRequest.PATH_PRE_ENG_INSTANT,
            AiWritingRequest.preInstantPath(
                com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode.ENGLISH,
                EssayLanguage.ENGLISH,
            ),
        )
        assertEquals(
            AiWritingRequest.PATH_ENG_INSTANT,
            AiWritingRequest.instantPath(
                com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode.QUICK,
                EssayLanguage.ENGLISH,
            ),
        )

        val body = AiWritingRequest.preInstantBody(
            title = "My Summer Holiday",
            queryType = "记叙文",
            wordCount = "100+",
            gradeId = 11,
            writeDate = 0L,
            language = EssayLanguage.ENGLISH,
            describe = "",
        )
        assertTrue("英语的 language 是 2", body.contains("\"language\":2"))

        val query = AiWritingRequest.preInstantQuery(
            title = "My Summer Holiday",
            wordCount = "100+",
            gradeId = 11,
            language = EssayLanguage.ENGLISH,
            describe = "",
        )
        assertTrue("query 里用英文名", query.contains("language=English"))
    }

    @Test
    fun `chinese quick mode uses the plain endpoints`() {
        assertEquals(
            AiWritingRequest.PATH_PRE_INSTANT,
            AiWritingRequest.preInstantPath(
                com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode.QUICK,
                EssayLanguage.CHINESE,
            ),
        )
        assertEquals(
            AiWritingRequest.PATH_INSTANT,
            AiWritingRequest.instantPath(
                com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode.QUICK,
                EssayLanguage.CHINESE,
            ),
        )
    }

    @Test
    fun `thought mode routes to the writingThought endpoints`() {
        val mode = com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode.THINKING
        assertEquals(
            AiWritingRequest.PATH_THOUGHT_PRE_INSTANT,
            AiWritingRequest.preInstantPath(mode, EssayLanguage.CHINESE),
        )
        assertEquals(
            AiWritingRequest.PATH_THOUGHT_INSTANT,
            AiWritingRequest.instantPath(mode, EssayLanguage.CHINESE),
        )
    }

    @Test
    fun `word count presets differ by language`() {
        assertTrue(AiWritingRequest.wordCountsOf(EssayLanguage.CHINESE).contains("800+"))
        assertTrue(AiWritingRequest.wordCountsOf(EssayLanguage.ENGLISH).contains("100+"))
        assertFalse(AiWritingRequest.wordCountsOf(EssayLanguage.ENGLISH).contains("800+"))
    }
}
