package com.heikeji.phonesearch.protocol.search.parse

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLoginNoticeTest {
    private val notice = "<b>【登录提醒】</b><p>亲爱的用户，请【先在个人中心登录】后再使用相关功能。</p>"

    private fun answer(content: String) = JsonObject().apply {
        add("question", JsonObject().apply { addProperty("content", content) })
        add("answer", JsonArray().apply {
            add(JsonObject().apply { addProperty("content", content) })
        })
    }

    @Test fun `single answer login notice requests login`() {
        assertThrows(SearchLoginRequiredException::class.java) {
            AnswerParser.parse(answer(notice).toString(), 1)
        }
    }

    @Test fun `html login page requests login`() {
        assertThrows(SearchLoginRequiredException::class.java) {
            AnswerParser.parse("<html><body>$notice</body></html>", 1)
        }
    }

    @Test fun `html tags entities and whitespace do not hide the directive`() {
        assertTrue(SearchLoginNotice.isRequired("请去 <b>个人中心</b>&nbsp;登 录，再使用相关功能。"))
    }

    @Test fun `ordinary questions mentioning accounts remain answers`() {
        val item = AnswerParser.parse(answer("用户登录后如何修改个人中心？").toString(), 1)
        assertFalse(SearchLoginNotice.isRequired(item))
        assertFalse(SearchLoginNotice.isRequired("请先登录校园网，然后计算下面的题目。"))
    }

    @Test fun `page candidate arrays must propagate the login requirement`() {
        val candidates = JsonArray().apply { add(answer(notice)) }
        val data = JsonObject().apply {
            add("answers", JsonObject().apply {
                add("mainPageInfo", JsonArray().apply { add(candidates.toString()) })
            })
        }
        assertThrows(SearchLoginRequiredException::class.java) {
            PageSearchParser.parse(data, 100, 100, null)
        }
    }

    @Test fun `page results must not convert login notices into empty block warnings`() {
        val data = JsonObject().apply {
            add("answers", JsonObject().apply {
                add("mainPageInfo", JsonArray().apply { add(answer(notice).toString()) })
            })
        }
        assertThrows(SearchLoginRequiredException::class.java) {
            PageSearchParser.parse(data, 100, 100, null)
        }
    }
}
