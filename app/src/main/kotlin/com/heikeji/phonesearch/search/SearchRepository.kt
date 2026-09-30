package com.heikeji.phonesearch.search

import com.heikeji.phonesearch.net.ApiClient
import com.heikeji.phonesearch.net.ApiException
import com.heikeji.phonesearch.net.ProtocolContext
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.decode.AnswerDecoder
import com.heikeji.phonesearch.protocol.model.AnswerItem
import com.heikeji.phonesearch.protocol.model.SearchChallenge
import com.heikeji.phonesearch.protocol.model.SearchResult
import com.heikeji.phonesearch.protocol.parse.AnswerParser
import org.json.JSONObject
import java.util.UUID

/**
 * 搜题编排：请求 -> （可能的验证挑战）-> 解码 -> 解析。
 *
 * 解码与解析逻辑对应原 O0.a 的 case 2。
 */
class SearchRepository(
    private val apiClient: ApiClient,
    private val protocol: ProtocolContext,
    private val challenges: SearchChallengeStore,
) {

    /** 发起搜题。若服务端要求验证，抛出 [com.heikeji.phonesearch.net.SearchChallengeException] 并已存好挑战。 */
    fun search(jpeg: ByteArray, grade: Int, kduss: String): SearchResult {
        val data = try {
            apiClient.searchRaw(jpeg, grade)
        } catch (e: com.heikeji.phonesearch.net.SearchChallengeException) {
            challenges.put(
                SearchChallenge(
                    localToken = UUID.randomUUID().toString(),
                    validatedInfo = e.validatedInfo,
                    sid = e.sid,
                    originalJpeg = jpeg,
                    kdussSnapshot = kduss,
                ),
            )
            throw e
        }
        return parse(data)
    }

    /** 验证完成后再用同一张图重试。 */
    fun retryAfterVerification(jpeg: ByteArray, grade: Int, kduss: String): SearchResult {
        challenges.clear()
        return search(jpeg, grade, kduss)
    }

    /** 是否存在尚未完成的验证挑战（登录中断后据此决定是否续跑原题）。 */
    fun hasPendingChallenge(): Boolean = challenges.peek() != null

    private fun parse(data: JSONObject): SearchResult {
        val answers = data.optJSONObject("answers")
            ?: throw ApiException("搜索响应缺少 answers，无法读取题目")
        val mainPageInfo = answers.optJSONArray("mainPageInfo")
            ?: throw ApiException("搜索响应缺少 mainPageInfo，无法读取答案")
        val tids = answers.optJSONArray("tids")

        val encode = data.optInt("encode", 0)
        val encryption = answers.optInt("encryption", 0)
        val gzip = answers.optInt("gzip", 0) == 1
        val subject = data.optJSONObject("searchInfo")?.optString("subjectName", "").orEmpty()
        val sid = data.optString("sid", "")
        val responseKey = protocol.responseKey()

        val items = ArrayList<AnswerItem>()
        for (index in 0 until mainPageInfo.length()) {
            val element = mainPageInfo.opt(index)
            if (element == null || element === JSONObject.NULL) continue
            val raw = if (element is String) element else element.toString()
            if (raw.trim().isEmpty()) continue

            val tid = tids?.optString(index, "").orEmpty()
            val decoded = try {
                AnswerDecoder.decode(raw, tid, encode, encryption, gzip, responseKey)
            } catch (e: ProtocolException) {
                throw ApiException("第 ${index + 1} 条答案：${e.message}", 0, e)
            }
            items.add(AnswerParser.parse(decoded, items.size + 1, subject, sid))
        }

        if (items.isEmpty() && (tids?.length() ?: 0) > 0) {
            throw ApiException("已匹配题目，但服务未返回可显示的答案内容")
        }
        return SearchResult(items = items, sid = sid, subject = subject)
    }
}
