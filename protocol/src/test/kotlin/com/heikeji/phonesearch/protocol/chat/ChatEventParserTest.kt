package com.heikeji.phonesearch.protocol.chat

import com.heikeji.phonesearch.protocol.aiwriting.SseParser
import com.heikeji.phonesearch.protocol.chat.model.ChatEvent
import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import com.heikeji.phonesearch.protocol.chat.model.ChatTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快问 AI 流解析测试。
 *
 * 样本全部来自真机实测（`/kdchat/api/ask`，`thinkEnabled=1`，问「你好」共 100 帧），
 * 不是照文档猜的。
 */
class ChatEventParserTest {

    private val parser = SseParser()

    /** 把多行原始流喂给解析器，收集所有 [ChatEvent]。 */
    private fun feed(raw: String): List<ChatEvent> {
        val events = ArrayList<ChatEvent>()
        for (line in raw.trimIndent().lines()) {
            val event = parser.feed(line.trimEnd('\r')) ?: continue
            events.addAll(ChatEventParser.parse(event))
        }
        parser.finish()?.let { events.addAll(ChatEventParser.parse(it)) }
        return events
    }

    // ------------------------------------------------------------------ 三种事件

    @Test
    fun `response frame yields started with the server ids`() {
        val events = feed(
            """
            id:1
            event:response
            data:{"sessionId":142120923415,"questionId":142120923423,"answerId":142120923424,"contentType":26,"execStrategyId":2463,"questionRecordId":"","answerRecordId":"","queryIntent":[],"kwIntent":"","isLogin":0,"createTimeStamp":1791230207847,"kdEnableReaskThink":1,"aiGenTag":{"contentProducer":"001191440101MA9Y59E48Y00000","produceID":"scancode_ask_142120923424"}}

            """,
        )
        val started = events.single() as ChatEvent.Started
        assertEquals("142120923415", started.sessionId)
        assertEquals("142120923423", started.questionId)
        assertEquals("142120923424", started.answerId)
        assertEquals(26, started.contentType)
        assertTrue(started.reaskThinkEnabled)
    }

    @Test
    fun `reasoning frame carries the thinking text and cost`() {
        val events = feed(
            """
            id:2
            event:msg
            data:{"source":"kw","data":{"contentType":26,"content":"{\"text\":\"\",\"reasoningContent\":\"用户\",\"reasoningCostTime\":121}","seq":2}}

            """,
        )
        val delta = events.single() as ChatEvent.Delta
        assertEquals("", delta.text)
        assertEquals("用户", delta.reasoning)
        assertEquals(121L, delta.reasoningCostMs)
        assertEquals("kw", delta.source)
        assertEquals(2, delta.seq)
    }

    @Test
    fun `answer frame carries plain text without reasoning`() {
        val events = feed(
            """
            id:99
            event:msg
            data:{"source":"kw","data":{"contentType":26,"content":"{\"text\":\"？\"}","seq":98}}

            """,
        )
        val delta = events.single() as ChatEvent.Delta
        assertEquals("？", delta.text)
        assertEquals("", delta.reasoning)
        assertEquals(0L, delta.reasoningCostMs)
    }

    @Test
    fun `close frame data is the literal nil`() {
        val events = feed(
            """
            id:100
            event:close
            data:<nil>

            """,
        )
        assertEquals(ChatEvent.Closed, events.single())
    }

    @Test
    fun `unknown events are ignored`() {
        val events = feed(
            """
            id:5
            event:audio
            data:{"whatever":1}

            """,
        )
        assertTrue(events.isEmpty())
    }

    // 实测：「我叫小明，请记住」这条回答整段走 kdcommand，不是 msg

    @Test
    fun `kdcommand carries the answer text`() {
        val events = feed(
            """
            id:3
            event:kdcommand
            data:{"cmd":"setUsername","data":{"content":"{\"text\":\"小明你好，我已经记住你了\",\"name\":\"小明\"}"}}

            """,
        )
        val command = events.single() as ChatEvent.Command
        assertEquals("setUsername", command.cmd)
        assertEquals("小明你好，我已经记住你了", command.text)
    }

    @Test
    fun `kdcommand without text is still reported so callers can skip it`() {
        val events = feed(
            """
            id:3
            event:kdcommand
            data:{"cmd":"deleteRecord","data":{"content":"{}"}}

            """,
        )
        val command = events.single() as ChatEvent.Command
        assertEquals("deleteRecord", command.cmd)
        assertEquals("", command.text)
    }

    @Test
    fun `close with an empty data line also works`() {
        // 实测两种都有：`data:<nil>` 和 `data:` 后面什么都没有
        val events = feed(
            """
            id:4
            event:close
            data:

            """,
        )
        assertEquals(ChatEvent.Closed, events.single())
    }

    @Test
    fun `error frame surfaces a readable message`() {
        val events = feed(
            """
            id:9
            event:error
            data:{"errstr":"服务开小差了"}

            """,
        )
        assertEquals("服务开小差了", (events.single() as ChatEvent.Failed).message)
    }

    @Test
    fun `revoke frame reports type and reason`() {
        val events = feed(
            """
            id:9
            event:revoke
            data:{"data":{"type":"1","reason":"answer_infringement"}}

            """,
        )
        val revoked = events.single() as ChatEvent.Revoked
        assertEquals(1, revoked.type)
        assertEquals("answer_infringement", revoked.reason)
    }

    @Test
    fun `refresh frame yields the new session id`() {
        val events = feed(
            """
            id:9
            event:kdrefresh
            data:{"sessionId":142120999999}

            """,
        )
        assertEquals("142120999999", (events.single() as ChatEvent.Refreshed).sessionId)
    }

    // ------------------------------------------------------------------ 整条流

    @Test
    fun `a real thinking stream splits into reasoning then answer`() {
        // 真实结构：1 response + 75 思考 + 23 正文 + 1 close
        val raw = buildString {
            appendLine("id:1")
            appendLine("event:response")
            appendLine("""data:{"sessionId":1,"questionId":2,"answerId":3,"contentType":26,"kdEnableReaskThink":1}""")
            appendLine()
            var id = 2
            repeat(3) { i ->
                appendLine("id:$id")
                appendLine("event:msg")
                appendLine(
                    """data:{"source":"kw","data":{"contentType":26,"seq":$i,"content":"{\"text\":\"\",\"reasoningContent\":\"思考$i\",\"reasoningCostTime\":${100 * (i + 1)}}"}}""",
                )
                appendLine()
                id++
            }
            repeat(2) { i ->
                appendLine("id:$id")
                appendLine("event:msg")
                appendLine(
                    """data:{"source":"kw","data":{"contentType":26,"seq":${i + 3},"content":"{\"text\":\"正文$i\"}"}}""",
                )
                appendLine()
                id++
            }
            appendLine("id:$id")
            appendLine("event:close")
            appendLine("data:<nil>")
            appendLine()
        }

        val events = feed(raw)
        assertEquals(7, events.size)
        assertTrue(events.first() is ChatEvent.Started)
        assertEquals(ChatEvent.Closed, events.last())

        val deltas = events.filterIsInstance<ChatEvent.Delta>()
        assertEquals(5, deltas.size)
        assertEquals("思考0思考1思考2", deltas.joinToString("") { it.reasoning })
        assertEquals("正文0正文1", deltas.joinToString("") { it.text })
        // 耗时取最后一个思考分片的值
        assertEquals(300L, deltas.filter { it.reasoning.isNotEmpty() }.last().reasoningCostMs)
    }

    // ------------------------------------------------------------------ 请求构造

    @Test
    fun `ask params carry the two switches`() {
        val on = ChatRequest.askParams("1", "你好", emptyList(), 6, thinkEnabled = true, searchEnabled = true)
        assertEquals("1", on["thinkEnabled"])
        assertEquals("1", on["searchEnabled"])
        assertEquals("normal", on["toolType"])
        assertEquals("211", on["feVc"])
        assertEquals("你好", on["content"])

        val off = ChatRequest.askParams("1", "你好", emptyList(), 6, thinkEnabled = false, searchEnabled = false)
        assertEquals("0", off["thinkEnabled"])
        assertEquals("0", off["searchEnabled"])
    }

    @Test
    fun `empty history becomes an empty json array`() {
        assertEquals("[]", ChatRequest.contextJson(emptyList()))
    }

    @Test
    fun `context keeps only user turns and uses the observed shape`() {
        val history = listOf(
            ChatTurn(ChatRole.USER, "第一个问题", 1791229331),
            ChatTurn(ChatRole.ASSISTANT, "第一个回答", 1791229332),
            ChatTurn(ChatRole.USER, "第二个问题", 1791229346),
        )
        val json = ChatRequest.contextJson(history)
        assertEquals(
            """[{"toolType":"normal","role":"user","content":"第一个问题","time":1791229331,"intent":[],"isCard":"0"},""" +
                """{"toolType":"normal","role":"user","content":"第二个问题","time":1791229346,"intent":[],"isCard":"0"}]""",
            json,
        )
    }

    @Test
    fun `context skips blank turns`() {
        val history = listOf(
            ChatTurn(ChatRole.USER, "", 1),
            ChatTurn(ChatRole.USER, "有效", 2),
        )
        assertFalse(ChatRequest.contextJson(history).contains("\"content\":\"\""))
        assertTrue(ChatRequest.contextJson(history).contains("有效"))
    }

    @Test
    fun `create params match the capture`() {
        val params = ChatRequest.createParams(6)
        assertEquals("scancode", params["appId"])
        assertEquals("6", params["grade"])
        assertEquals("211", params["feVc"])
    }

    // ------------------------------------------------------------------ 带图提问

    @Test
    fun `photo ask marks the tool type and carries the image md5`() {
        val params = ChatRequest.photoAskParams(
            sessionId = "142120914113",
            content = "1, 2, 3, 4, 5, ",
            history = emptyList(),
            grade = 6,
            thinkEnabled = false,
            searchEnabled = false,
            picMd5 = "4c4be24bcb6a6dc1f884ecf20198e756",
        )
        assertEquals("image", params["toolType"])
        assertEquals("""{"picMD5":"4c4be24bcb6a6dc1f884ecf20198e756"}""", params["imageInfo"])
        assertEquals("142120914113", params["sessionId"])
        assertEquals("1, 2, 3, 4, 5, ", params["content"])
        // pagesearchInfo 在 H5 里是可选的，不发也要能work
        assertFalse(params.containsKey("pagesearchInfo"))
    }

    @Test
    fun `photo ask honours the switches and keeps context`() {
        val params = ChatRequest.photoAskParams(
            sessionId = "1",
            content = "",
            history = listOf(ChatTurn(ChatRole.USER, "上一轮", 100)),
            grade = 6,
            thinkEnabled = true,
            searchEnabled = true,
            picMd5 = "abc",
        )
        assertEquals("1", params["thinkEnabled"])
        assertEquals("1", params["searchEnabled"])
        assertTrue(params["context"]!!.contains("上一轮"))
    }
}
