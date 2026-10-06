package com.heikeji.phonesearch.protocol.search.parse

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.codec.PageExtraInfo
import com.heikeji.phonesearch.protocol.search.model.PageWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整页搜题解析的回归测试，覆盖交接文档 §15「整页解析」清单。
 *
 * 解析器的核心要求是**容错**：四个数组长度可以不一致、单个题块坏掉不能影响其他题块。
 */
class PageSearchParserTest {

    private val uploadWidth = 2400
    private val uploadHeight = 3200

    // ------------------------------------------------------------------ 构造响应

    private fun answerJson(question: String, answer: String): String =
        """{"question":{"content":"$question"},"answer":[{"content":"$answer"}]}"""

    private fun pageResponse(
        mainPageInfo: List<String> = emptyList(),
        tids: List<String>? = null,
        locs: List<String>? = null,
        angles: List<String>? = null,
        pictureWidth: Int = 2400,
        pictureHeight: Int = 3200,
        rotateAngle: Int = 0,
        dealInfo: String? = null,
        encode: Int = 0,
        encryption: Int = 0,
        gzip: Int = 0,
    ): JsonObject {
        val answers = JsonObject()
        answers.add("mainPageInfo", JsonParser.parseString(
            mainPageInfo.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" },
        ))
        tids?.let {
            answers.add("tids", JsonParser.parseString(it.joinToString(",", "[", "]") { v -> "\"$v\"" }))
        }
        locs?.let {
            answers.add("locs", JsonParser.parseString(it.joinToString(",", "[", "]") { v -> "\"$v\"" }))
        }
        angles?.let {
            answers.add("angles", JsonParser.parseString(it.joinToString(",", "[", "]") { v -> "\"$v\"" }))
        }
        answers.addProperty("encryption", encryption)
        answers.addProperty("gzip", gzip)

        val picture = JsonObject()
        picture.addProperty("width", pictureWidth)
        picture.addProperty("height", pictureHeight)
        dealInfo?.let { picture.add("dealInfo", JsonParser.parseString(it)) }

        val root = JsonObject()
        root.addProperty("sid", "page-sid")
        root.addProperty("encode", encode)
        root.addProperty("rotateAngle", rotateAngle)
        root.add("picture", picture)
        root.add("searchInfo", JsonParser.parseString("""{"subjectName":"数学"}"""))
        root.add("answers", answers)
        return root
    }

    private fun parse(root: JsonObject) =
        PageSearchParser.parse(root, uploadWidth, uploadHeight, responseKey = null)

    // ------------------------------------------------------------------ 基本解析

    @Test
    fun `parses a block with candidate, location and angle`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("<p>题1</p>", "<p>答1</p>")),
                tids = listOf("t1"),
                locs = listOf("10@20@100@20@100@200@10@200"),
                angles = listOf("0"),
            ),
        )

        assertEquals("page-sid", result.sid)
        assertEquals("数学", result.subject)
        assertEquals(2400, result.pictureWidth)
        assertEquals(3200, result.pictureHeight)
        assertTrue(result.positioningAvailable)
        assertEquals(1, result.blocks.size)

        val block = result.blocks[0]
        assertEquals(0, block.serviceIndex)
        assertEquals(1, block.candidates.size)
        assertEquals("<p>答1</p>", block.candidates[0].answerHtml)
        assertNotNull(block.location)
        assertEquals(10, block.location!!.left)
        assertEquals(200, block.location!!.bottom)
        assertEquals(90, block.location!!.width)
        assertEquals("", block.warning)
    }

    @Test
    fun `block count is the max of the four array lengths`() {
        // mainPageInfo 只有 1 项，locs 有 3 项 -> 3 个题块
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("<p>题1</p>", "<p>答1</p>")),
                locs = listOf(
                    "10@20@100@20@100@200@10@200",
                    "110@20@200@20@200@200@110@200",
                    "210@20@300@20@300@200@210@200",
                ),
            ),
        )
        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[0].hasAnswer)
        assertFalse(result.blocks[1].hasAnswer)
        assertFalse(result.blocks[2].hasAnswer)
    }

    @Test
    fun `missing tids locs angles still parses mainPageInfo`() {
        val result = parse(pageResponse(mainPageInfo = listOf(answerJson("q", "a"))))
        assertEquals(1, result.blocks.size)
        assertEquals(1, result.blocks[0].candidates.size)
    }

    @Test
    fun `empty arrays yield no blocks`() {
        val result = parse(pageResponse())
        assertTrue(result.isEmpty)
        assertTrue(result.positioningAvailable)
    }

    @Test
    fun `missing answers throws`() {
        val root = JsonObject()
        root.addProperty("sid", "x")
        assertThrows(ProtocolException::class.java) { parse(root) }
    }

    @Test
    fun `missing mainPageInfo throws`() {
        val root = pageResponse()
        (root.getAsJsonObject("answers")).remove("mainPageInfo")
        assertThrows(ProtocolException::class.java) { parse(root) }
    }

    // ------------------------------------------------------------------ 候选形态

    @Test
    fun `html answer becomes a raw html candidate`() {
        val html = "<!DOCTYPE html><html><body><p>整页答案</p></body></html>"
        val result = parse(pageResponse(mainPageInfo = listOf(html)))
        val item = result.blocks[0].candidates[0]
        assertEquals(html, item.rawHtml)
    }

    @Test
    fun `json array answer becomes multiple candidates`() {
        val array = "[${answerJson("q1", "a1")},${answerJson("q2", "a2")},${answerJson("q3", "a3")}]"
        val result = parse(pageResponse(mainPageInfo = listOf(array)))
        val candidates = result.blocks[0].candidates
        assertEquals(3, candidates.size)
        assertEquals("a1", candidates[0].answerHtml)
        assertEquals("a2", candidates[1].answerHtml)
        assertEquals("a3", candidates[2].answerHtml)
    }

    // ------------------------------------------------------------------ encode / encryption

    @Test
    fun `unsupported encode value throws`() {
        assertThrows(ProtocolException::class.java) {
            parse(pageResponse(mainPageInfo = listOf(answerJson("q", "a")), encode = 2))
        }
    }

    @Test
    fun `unsupported encryption value throws`() {
        assertThrows(ProtocolException::class.java) {
            parse(pageResponse(mainPageInfo = listOf(answerJson("q", "a")), encryption = 2))
        }
    }

    // ------------------------------------------------------------------ 题框

    @Test
    fun `loc with fewer than eight points is rejected`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@100@20@100@200"),
            ),
        )
        assertNull(result.blocks[0].location)
        assertEquals(PageWarnings.LOCATION_INVALID, result.blocks[0].warning)
    }

    @Test
    fun `loc out of picture bounds is rejected`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@99999@20@99999@200@10@200"),
            ),
        )
        assertNull(result.blocks[0].location)
        assertEquals(PageWarnings.LOCATION_INVALID, result.blocks[0].warning)
    }

    @Test
    fun `zero area loc is rejected`() {
        // 四点共线，鞋带面积为 0
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@10@20@10@20@10@20"),
            ),
        )
        assertNull(result.blocks[0].location)
        assertEquals(PageWarnings.LOCATION_INVALID, result.blocks[0].warning)
    }

    @Test
    fun `missing loc reports the dedicated warning`() {
        val result = parse(pageResponse(mainPageInfo = listOf(answerJson("q", "a"))))
        assertNull(result.blocks[0].location)
        assertEquals(PageWarnings.NO_LOCATION, result.blocks[0].warning)
    }

    // ------------------------------------------------------------------ 角度

    @Test
    fun `angle boundaries are inclusive`() {
        for (value in listOf(-360, -90, 0, 90, 360)) {
            val result = parse(
                pageResponse(
                    mainPageInfo = listOf(answerJson("q", "a")),
                    angles = listOf(value.toString()),
                ),
            )
            assertEquals("angle $value 应被接受", value, result.blocks[0].angle)
        }
    }

    @Test
    fun `angle outside the closed interval is rejected with a warning`() {
        // 带上合法 loc，确保角度警告不会被更早的定位警告盖掉
        for (value in listOf("-361", "361", "abc", "")) {
            val result = parse(
                pageResponse(
                    mainPageInfo = listOf(answerJson("q", "a")),
                    locs = listOf("10@20@100@20@100@200@10@200"),
                    angles = listOf(value),
                ),
            )
            assertEquals("angle '$value' 应被拒绝", 0, result.blocks[0].angle)
            if (value.isNotEmpty()) {
                assertEquals(PageWarnings.ANGLE_INVALID, result.blocks[0].warning)
            }
        }
    }

    // ------------------------------------------------------------------ 定位可用性

    @Test
    fun `picture size mismatch disables positioning`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@100@20@100@200@10@200"),
                pictureWidth = 1200,
                pictureHeight = 1600,
            ),
        )
        assertFalse(result.positioningAvailable)
        assertEquals(PageWarnings.SIZE_MISMATCH, result.positioningWarning)
        assertNull(result.blocks[0].location)
        // 答案仍然要能显示
        assertTrue(result.blocks[0].hasAnswer)
    }

    @Test
    fun `non zero rotate angle disables positioning`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@100@20@100@200@10@200"),
                rotateAngle = 90,
            ),
        )
        assertFalse(result.positioningAvailable)
        assertEquals(PageWarnings.CROPPED_OR_ROTATED, result.positioningWarning)
    }

    @Test
    fun `deal info transformation disables positioning`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@100@20@100@200@10@200"),
                dealInfo = """{"isDeal":1,"isCorrect":0,"direction":0}""",
            ),
        )
        assertFalse(result.positioningAvailable)
        assertEquals(PageWarnings.CROPPED_OR_ROTATED, result.positioningWarning)
    }

    @Test
    fun `zero deal info keeps positioning available`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(answerJson("q", "a")),
                locs = listOf("10@20@100@20@100@200@10@200"),
                dealInfo = """{"isDeal":0,"isCorrect":0,"direction":0}""",
            ),
        )
        assertTrue(result.positioningAvailable)
        assertNotNull(result.blocks[0].location)
    }

    @Test
    fun `unknown picture size disables positioning`() {
        val result = parse(pageResponse(mainPageInfo = listOf(answerJson("q", "a")), pictureWidth = 0))
        assertFalse(result.positioningAvailable)
        assertEquals(PageWarnings.SIZE_UNKNOWN, result.positioningWarning)
    }

    // ------------------------------------------------------------------ 容错

    @Test
    fun `one broken block does not affect the others`() {
        val result = parse(
            pageResponse(
                mainPageInfo = listOf(
                    answerJson("<p>好题</p>", "<p>好答案</p>"),
                    "not json at all",
                    answerJson("<p>另一题</p>", "<p>另一答案</p>"),
                ),
                locs = listOf(
                    "10@20@100@20@100@200@10@200",
                    "bad",
                    "210@20@300@20@300@200@210@200",
                ),
            ),
        )
        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[0].hasAnswer)
        assertFalse(result.blocks[1].hasAnswer)
        assertTrue(result.blocks[1].warning.isNotEmpty())
        assertTrue(result.blocks[2].hasAnswer)
        assertNotNull(result.blocks[2].location)
    }

    @Test
    fun `null entries inside arrays are skipped`() {
        val root = pageResponse(mainPageInfo = listOf(answerJson("q", "a")))
        val answers = root.getAsJsonObject("answers")
        answers.add("mainPageInfo", JsonParser.parseString("[null,\"${answerJson("q2", "a2").replace("\"", "\\\"")}\"]"))
        val result = parse(root)
        assertEquals(2, result.blocks.size)
        assertFalse(result.blocks[0].hasAnswer)
        assertTrue(result.blocks[1].hasAnswer)
    }

    // ------------------------------------------------------------------ pageExtraInfo

    @Test
    fun `pageExtraInfo uses serviceIndex and bounding rect with dot zero`() {
        val json = PageExtraInfo.build(
            wholeSearchSid = "sid-abc",
            serviceIndex = 3,
            loc = PageExtraInfo.formatLoc(120, 340, 1980, 1260),
        )
        assertEquals(
            """{"wholeSearchSid":"sid-abc","index":3,"loc":"120.0@340.0@1980.0@1260.0"}""",
            json,
        )
    }

    @Test
    fun `quad bounding rect string is dot zero formatted`() {
        val quad = com.heikeji.phonesearch.protocol.search.model.QuestionQuad
            .parse("10@20@100@40@100@200@10@180", 2400, 3200)!!
        assertEquals("10.0@20.0@100.0@200.0", quad.boundingRectString())
    }
}
