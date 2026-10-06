package com.heikeji.phonesearch.search

import com.heikeji.phonesearch.net.ApiException
import com.heikeji.phonesearch.net.ProtocolContext
import com.heikeji.phonesearch.net.SearchApi
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.codec.PageExtraInfo
import com.heikeji.phonesearch.protocol.search.decode.AnswerDecoder
import com.heikeji.phonesearch.protocol.search.model.AnswerItem
import com.heikeji.phonesearch.protocol.search.model.PageSearchResult
import com.heikeji.phonesearch.protocol.search.model.SearchChallenge
import com.heikeji.phonesearch.protocol.search.model.SearchMode
import com.heikeji.phonesearch.protocol.search.model.SearchResult
import com.heikeji.phonesearch.protocol.search.parse.AnswerParser
import com.heikeji.phonesearch.protocol.search.parse.PageSearchParser
import org.json.JSONObject
import java.util.UUID

/**
 * 搜题编排：请求 -> （可能的验证挑战）-> 解码 -> 解析。
 *
 * 三种模式共用一套挑战机制，挑战会保存**完整 [SearchTask]**，
 * 验证成功后按原模式恢复，不会把整页/框选降级成普通单题。
 */
class SearchRepository(
    private val apiClient: SearchApi,
    private val protocol: ProtocolContext,
    private val challenges: SearchChallengeStore,
) {

    // ------------------------------------------------------------------ 单题 / 框选

    /**
     * 普通单题或框选精搜。
     *
     * 框选时若整页关联信息可用，会发送 `referer=3` 与 `pageExtraInfo={wholeSearchSid,index,loc}`；
     * 否则**自动退化为普通单题参数**（`referer=1`、空 pageExtraInfo）。
     */
    fun search(task: SearchTask, grade: Int): SearchResult {
        val pageExtraInfo = pageExtraInfoOf(task)
        val effectiveMode = if (task.requestMode == SearchMode.CROP_SINGLE &&
            pageExtraInfo.isEmpty()
        ) {
            SearchMode.SINGLE
        } else {
            task.requestMode
        }

        val data = try {
            apiClient.searchRaw(task.uploadJpeg, grade, effectiveMode, pageExtraInfo)
        } catch (e: com.heikeji.phonesearch.net.SearchChallengeException) {
            rememberChallenge(e, task)
            throw e
        }
        return parseSingle(data, task.uploadJpeg)
    }

    // ------------------------------------------------------------------ 整页

    /** 整页搜题：返回题块与候选答案。 */
    fun searchPage(task: SearchTask, grade: Int): PageSearchResult {
        val data = try {
            apiClient.searchRaw(task.uploadJpeg, grade, SearchMode.PAGE)
        } catch (e: com.heikeji.phonesearch.net.SearchChallengeException) {
            rememberChallenge(e, task)
            throw e
        }
        return try {
            PageSearchParser.parseJson(
                dataJson = data.toString(),
                uploadWidth = task.uploadWidth,
                uploadHeight = task.uploadHeight,
                responseKey = protocol.responseKey(),
            )
        } catch (e: ProtocolException) {
            throw ApiException(e.message ?: "整页结果无法解析", 0, e)
        }
    }

    // ------------------------------------------------------------------ 挑战

    fun hasPendingChallenge(): Boolean = challenges.peek() != null

    fun pendingTask(): SearchTask? = challenges.peek()?.task

    fun clearChallenge() {
        challenges.clear()
    }

    /**
     * 验证完成后按**原模式**恢复。
     *
     * 调用方必须先核对任务未过期（generation / 图片 / KDUSS / UID）。
     */
    fun retryAfterVerification(task: SearchTask, grade: Int): SearchOutcome {
        challenges.clear()
        return when (task.requestMode) {
            SearchMode.PAGE -> SearchOutcome.Page(searchPage(task, grade))
            else -> SearchOutcome.Single(search(task, grade))
        }
    }

    /** 验证成功后的恢复结果，按模式区分。 */
    sealed interface SearchOutcome {
        data class Single(val result: SearchResult) : SearchOutcome
        data class Page(val result: PageSearchResult) : SearchOutcome
    }

    private fun rememberChallenge(
        e: com.heikeji.phonesearch.net.SearchChallengeException,
        task: SearchTask,
    ) {
        challenges.put(
            SearchChallenge(
                localToken = UUID.randomUUID().toString(),
                validatedInfo = e.validatedInfo,
                sid = e.sid,
                originalJpeg = task.uploadJpeg,
                kdussSnapshot = task.kdussSnapshot,
            ),
            task,
        )
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 构造框选关联信息。
     *
     * `index` 用 serviceIndex；`loc` 是框选在整页响应图片坐标系下的包围矩形，
     * 用 `Float.toString` 序列化（所以带 `.0`）。
     */
    private fun pageExtraInfoOf(task: SearchTask): String {
        if (task.requestMode != SearchMode.CROP_SINGLE) return ""
        if (!task.hasPageLink) return ""
        val rect = task.selectedRectNormalized ?: return ""
        if (rect.size != 4) return ""
        if (task.uploadWidth < 1 || task.uploadHeight < 1) return ""

        val left = Math.round(rect[0] * task.uploadWidth)
        val top = Math.round(rect[1] * task.uploadHeight)
        val right = Math.round(rect[2] * task.uploadWidth)
        val bottom = Math.round(rect[3] * task.uploadHeight)
        if (right - left < 1 || bottom - top < 1) return ""

        return PageExtraInfo.build(
            wholeSearchSid = task.wholeSearchSid,
            serviceIndex = task.serviceBlockIndex,
            loc = PageExtraInfo.formatLoc(left, top, right, bottom),
        )
    }

    private fun parseSingle(data: JSONObject, uploadJpeg: ByteArray): SearchResult {
        val answers = data.optJSONObject("answers")
            ?: throw ApiException("搜索响应缺少 answers，无法读取题目")
        val mainPageInfo = answers.optJSONArray("mainPageInfo")
            ?: throw ApiException("搜索响应缺少 mainPageInfo，无法读取答案")
        val tids = answers.optJSONArray("tids")

        val encode = data.optInt("encode", 0)
        val encryption = answers.optInt("encryption", 0)
        val gzip = answers.optInt("gzip", 0) == 1
        val searchInfo = data.optJSONObject("searchInfo")
        val subject = searchInfo?.optString("subjectName", "").orEmpty()
        val subjectId = searchInfo?.optInt("subjectId", 0) ?: 0
        val sid = data.optString("sid", "")
        val pid = data.optJSONObject("picture")?.optString("pid", "").orEmpty()
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
            items.add(AnswerParser.parse(decoded, items.size + 1, subject, sid, tid))
        }

        if (items.isEmpty() && (tids?.length() ?: 0) > 0) {
            throw ApiException("已匹配题目，但服务未返回可显示的答案内容")
        }
        return SearchResult(
            items = items,
            sid = sid,
            subject = subject,
            subjectId = subjectId,
            pid = pid,
        )
    }
}
