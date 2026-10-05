package com.heikeji.phonesearch.protocol.parse

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.decode.AnswerDecoder
import com.heikeji.phonesearch.protocol.json.Json
import com.heikeji.phonesearch.protocol.json.intOr
import com.heikeji.phonesearch.protocol.json.objOrNull
import com.heikeji.phonesearch.protocol.json.strOrEmpty
import com.heikeji.phonesearch.protocol.model.AnswerItem
import com.heikeji.phonesearch.protocol.model.PageQuestionBlock
import com.heikeji.phonesearch.protocol.model.PageSearchResult
import com.heikeji.phonesearch.protocol.model.PageWarnings
import com.heikeji.phonesearch.protocol.model.QuestionQuad

/**
 * 整页搜题响应解析（原 `O0.C0040i` 的整页分支）。
 *
 * 与单题解析的关键差异：
 * - 题块数不是 `mainPageInfo.length`，而是四个数组长度的最大值。
 * - 每个索引独立解析，单个题块的失败只写进该题块的 warning，不影响其他题块。
 * - 定位是否可用由服务端图片尺寸、上传图片尺寸、`rotateAngle` 与 `dealInfo` 共同决定。
 */
object PageSearchParser {

    /**
     * 只吃 JSON 字符串的入口。
     *
     * `:app` 不依赖 Gson（`:protocol` 用 `implementation` 引入，不对外暴露），
     * 所以对外只暴露这个签名，Gson 类型不越过模块边界。
     */
    fun parseJson(
        dataJson: String,
        uploadWidth: Int,
        uploadHeight: Int,
        responseKey: String?,
    ): PageSearchResult = parse(
        data = Json.parseObject(dataJson, "整页响应格式无法识别"),
        uploadWidth = uploadWidth,
        uploadHeight = uploadHeight,
        responseKey = responseKey,
    )

    /**
     * @param uploadWidth / [uploadHeight] **本次实际上传的 JPEG** 的像素尺寸
     * @return 解析结果；`answers` 或 `mainPageInfo` 缺失时抛 [ProtocolException]
     */
    fun parse(
        data: JsonObject,
        uploadWidth: Int,
        uploadHeight: Int,
        responseKey: String?,
    ): PageSearchResult {
        val encode = data.intOr("encode", 0)
        if (encode != 0 && encode != 1) {
            throw ProtocolException("暂不支持服务返回的答案编码类型：$encode")
        }

        val answers = data.objOrNull("answers")
            ?: throw ProtocolException("整页响应缺少 answers，无法读取题目")
        val mainPageInfo = answers.get("mainPageInfo")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw ProtocolException("整页响应缺少 mainPageInfo，无法读取答案")

        val encryption = answers.intOr("encryption", 0)
        if (encryption != 0 && encryption != 1) {
            throw ProtocolException("暂不支持服务返回的答案加密类型：$encryption")
        }
        val gzip = answers.intOr("gzip", 0) == 1
        val tids = answers.arrayOrNull("tids")
        val locs = answers.arrayOrNull("locs")
        val angles = answers.arrayOrNull("angles")

        val sid = data.strOrEmpty("sid")
        val subject = data.objOrNull("searchInfo").strOrEmpty("subjectName")

        val picture = data.objOrNull("picture")
        val pictureWidth = picture.intOr("width", 0)
        val pictureHeight = picture.intOr("height", 0)
        val rotateAngle = data.intOr("rotateAngle", 0)
        val dealInfo = picture.objOrNull("dealInfo")

        val positioningWarning = positioningWarning(
            pictureWidth = pictureWidth,
            pictureHeight = pictureHeight,
            uploadWidth = uploadWidth,
            uploadHeight = uploadHeight,
            rotateAngle = rotateAngle,
            dealInfo = dealInfo,
        )
        val positioning = positioningWarning.isEmpty()

        val blockCount = maxOf(
            mainPageInfo.size(),
            tids?.size() ?: 0,
            locs?.size() ?: 0,
            angles?.size() ?: 0,
        )

        val blocks = ArrayList<PageQuestionBlock>(blockCount)
        for (index in 0 until blockCount) {
            blocks.add(
                parseBlock(
                    index = index,
                    mainPageInfo = mainPageInfo,
                    tids = tids,
                    locs = locs,
                    angles = angles,
                    encode = encode,
                    encryption = encryption,
                    gzip = gzip,
                    responseKey = responseKey,
                    subject = subject,
                    sid = sid,
                    pictureWidth = pictureWidth,
                    pictureHeight = pictureHeight,
                    positioning = positioning,
                    positioningWarning = positioningWarning,
                ),
            )
        }

        return PageSearchResult(
            sid = sid,
            subject = subject,
            pictureWidth = pictureWidth,
            pictureHeight = pictureHeight,
            positioningAvailable = positioning,
            positioningWarning = positioningWarning,
            blocks = blocks,
        )
    }

    // ------------------------------------------------------------------ 单个题块

    private fun parseBlock(
        index: Int,
        mainPageInfo: JsonArray,
        tids: JsonArray?,
        locs: JsonArray?,
        angles: JsonArray?,
        encode: Int,
        encryption: Int,
        gzip: Boolean,
        responseKey: String?,
        subject: String,
        sid: String,
        pictureWidth: Int,
        pictureHeight: Int,
        positioning: Boolean,
        positioningWarning: String,
    ): PageQuestionBlock {
        var warning = ""

        // ---- 候选答案 ----
        val candidates = ArrayList<AnswerItem>()
        val rawAnswer = mainPageInfo.stringAt(index)
        if (rawAnswer.isNotEmpty()) {
            try {
                val decoded = AnswerDecoder.decode(
                    raw = rawAnswer,
                    tid = tids.stringAt(index),
                    encode = encode,
                    encryption = encryption,
                    gzip = gzip,
                    responseKey = responseKey,
                )
                candidates.addAll(candidatesOf(decoded, subject, sid))
            } catch (e: ProtocolException) {
                warning = e.message ?: PageWarnings.NO_ANSWER
            }
        }
        if (candidates.isEmpty() && warning.isEmpty()) warning = PageWarnings.NO_ANSWER

        // ---- 题框 ----
        var location: QuestionQuad? = null
        if (positioning) {
            // 注意区分「数组里没有这一项」和「有但不可用」，两者的提示不同。
            val rawLocElement = locs.stringAt(index)
            if (rawLocElement.isEmpty()) {
                if (warning.isEmpty()) warning = PageWarnings.NO_LOCATION
            } else {
                val rawLoc = decodeAux(
                    raw = rawLocElement,
                    looksPlain = { it.contains(ProtocolProfile.LOC_SEPARATOR) },
                    tids = tids,
                    index = index,
                    encode = encode,
                    encryption = encryption,
                    gzip = gzip,
                    responseKey = responseKey,
                )
                location = QuestionQuad.parse(rawLoc, pictureWidth, pictureHeight)
                if (location == null && warning.isEmpty()) {
                    warning = PageWarnings.LOCATION_INVALID
                }
            }
        } else if (warning.isEmpty()) {
            warning = positioningWarning
        }

        // ---- 题框角度 ----
        var angle = 0
        val rawAngleElement = angles.stringAt(index)
        if (rawAngleElement.isNotEmpty()) {
            val rawAngle = decodeAux(
                raw = rawAngleElement,
                looksPlain = { it.trim().toIntOrNull() != null },
                tids = tids,
                index = index,
                encode = encode,
                encryption = encryption,
                gzip = gzip,
                responseKey = responseKey,
            )
            val parsed = rawAngle.trim().toIntOrNull()
            if (parsed == null ||
                parsed < ProtocolProfile.QUAD_ANGLE_MIN ||
                parsed > ProtocolProfile.QUAD_ANGLE_MAX
            ) {
                if (warning.isEmpty()) warning = PageWarnings.ANGLE_INVALID
            } else {
                angle = parsed
            }
        }

        return PageQuestionBlock(
            serviceIndex = index,
            candidates = candidates,
            location = location,
            angle = angle,
            warning = warning,
        )
    }

    /**
     * 解码后的内容可能是三种形态：单个答案 JSON、完整 HTML、或候选数组。
     * 数组时每项独立解析成一个候选。
     */
    private fun candidatesOf(decoded: String, subject: String, sid: String): List<AnswerItem> {
        val trimmed = decoded.trim()
        if (trimmed.startsWith("[")) {
            val array = Json.tryParseArray(trimmed)
            if (array != null) {
                val parsed = ArrayList<AnswerItem>(array.size())
                for (i in 0 until array.size()) {
                    val text = array.stringAt(i)
                    if (text.isEmpty()) continue
                    val item = runCatching {
                        AnswerParser.parse(text, parsed.size + 1, subject, sid)
                    }.getOrNull() ?: continue
                    parsed.add(item)
                }
                if (parsed.isNotEmpty()) return parsed
            }
        }
        return listOf(AnswerParser.parse(trimmed, 1, subject, sid))
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 定位可用性判定，顺序对齐交接文档 §4.3。
     *
     * @return 不可用原因；可用时返回空串
     */
    private fun positioningWarning(
        pictureWidth: Int,
        pictureHeight: Int,
        uploadWidth: Int,
        uploadHeight: Int,
        rotateAngle: Int,
        dealInfo: JsonObject?,
    ): String = when {
        pictureWidth !in 1..ProtocolProfile.LOC_MAX_COORDINATE ||
            pictureHeight !in 1..ProtocolProfile.LOC_MAX_COORDINATE -> PageWarnings.SIZE_UNKNOWN

        uploadWidth < 1 || uploadHeight < 1 -> PageWarnings.SIZE_UNKNOWN

        pictureWidth != uploadWidth || pictureHeight != uploadHeight ->
            PageWarnings.SIZE_MISMATCH

        rotateAngle != 0 -> PageWarnings.CROPPED_OR_ROTATED

        dealInfo != null && (
            dealInfo.intOr("isDeal", 0) != 0 ||
                dealInfo.intOr("isCorrect", 0) != 0 ||
                dealInfo.intOr("direction", 0) != 0
            ) -> PageWarnings.CROPPED_OR_ROTATED

        else -> ""
    }

    /** `locs` / `angles` 可能也经过 Base64/RC4，按内容形态判断是否需要解码。 */
    private fun decodeAux(
        raw: String,
        looksPlain: (String) -> Boolean,
        tids: JsonArray?,
        index: Int,
        encode: Int,
        encryption: Int,
        gzip: Boolean,
        responseKey: String?,
    ): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (looksPlain(trimmed)) return trimmed
        return runCatching {
            AnswerDecoder.decode(
                raw = trimmed,
                tid = tids.stringAt(index),
                encode = encode,
                encryption = encryption,
                gzip = gzip,
                responseKey = responseKey,
            )
        }.getOrDefault("")
    }

    private fun JsonObject?.arrayOrNull(name: String): JsonArray? {
        val element = this?.get(name) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }

    /** 数组第 [index] 项转字符串；越界、null、对象一律返回空串。 */
    private fun JsonArray?.stringAt(index: Int): String {
        val array = this ?: return ""
        if (index < 0 || index >= array.size()) return ""
        val element: JsonElement = array.get(index)
        if (element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        return element.toString()
    }
}
