package com.heikeji.phonesearch.net

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `validatedInfo` 的判定规则。
 *
 * 这条规则来自一次真实事故（抓包定位）：匿名整页/单题搜题时，服务器在**成功响应**里
 * 同时返回了完整答案和 `validatedInfo`：
 * ```
 * "answers":{"count":4,"locs":[...],"locInfo":[...]},
 * "validatedInfo":"{\"appId\":\"scancode\",\"hitValidate\":1,\"validateRule\":\"2\"}"
 * ```
 * 而旧代码「只要 validatedInfo 非空就当被拦」，于是把已经拿到的答案扔掉、
 * 跳去官方反抓取验证页 —— 那个页面写着「需要登录」，用户看到的就是「不登录用不了」。
 *
 * 对照官方 APP 的抓包：它拿到同样的字段照样展示答案。
 *
 * 所以判据是**有没有答案**，不是有没有 validatedInfo。
 */
class SearchAnswerDetectionTest {

    @Test
    fun `single search with answers counts as usable`() {
        val body = """
            {"sid":"abc","searchInfo":{"subjectId":5},
             "answers":{"count":2,"tids":["a","b"]},
             "validatedInfo":"{\"appId\":\"scancode\",\"hitValidate\":1,\"validateRule\":\"2\"}"}
        """.trimIndent()
        assertTrue("有 answers.count 就算拿到答案，不该跳验证页", SearchAnswers.hasUsableAnswer(JSONObject(body)))
    }

    @Test
    fun `page search with answers counts as usable`() {
        val body = """
            {"sid":"abc","searchInfo":{"subjectId":5},
             "answers":{"count":4,"locs":["x","y","z","w"],"locInfo":[{},{},{},{}]},
             "validatedInfo":"{\"appId\":\"scancode\",\"hitValidate\":1,\"validateRule\":\"2\"}"}
        """.trimIndent()
        assertTrue(SearchAnswers.hasUsableAnswer(JSONObject(body)))
    }

    @Test
    fun `zero answers is not usable`() {
        val body = """
            {"sid":"abc",
             "answers":{"count":0,"tids":[]},
             "validatedInfo":"{\"appId\":\"scancode\",\"hitValidate\":1,\"validateRule\":\"2\"}"}
        """.trimIndent()
        assertFalse("没有答案才算真被拦", SearchAnswers.hasUsableAnswer(JSONObject(body)))
    }

    @Test
    fun `no answers field at all is not usable`() {
        val body = """
            {"sid":"abc",
             "validatedInfo":"{\"appId\":\"scancode\",\"hitValidate\":1,\"validateRule\":\"2\"}"}
        """.trimIndent()
        assertFalse(SearchAnswers.hasUsableAnswer(JSONObject(body)))
    }

    @Test
    fun `blocks payload counts as usable`() {
        assertTrue(
            "整页搜题的题块载荷也算有结果",
            SearchAnswers.hasUsableAnswer(JSONObject("""{"sid":"abc","blocks":[{"id":1}]}""")),
        )
    }

    @Test
    fun `normal success without validatedInfo is usable`() {
        assertTrue(
            SearchAnswers.hasUsableAnswer(JSONObject("""{"sid":"abc","answers":{"count":1,"tids":["a"]}}""")),
        )
    }
}
