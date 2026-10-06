package com.heikeji.phonesearch.protocol.parse

import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.json.Json
import com.heikeji.phonesearch.protocol.json.arrOrNull
import com.heikeji.phonesearch.protocol.json.objOrNull
import com.heikeji.phonesearch.protocol.json.strOrEmpty
import com.heikeji.phonesearch.protocol.model.AnswerItem

/**
 * 解码后的答案文本 -> [AnswerItem]（原 f1.b.r0）。
 *
 * 兼容的字段：
 * ```
 * question.content / question.picList
 * answer[0].content / answer[0].picList
 * subjectAnalysis
 * analysis.subjectAnalysis.content
 * courseName / qid
 * ```
 * 整个结果是 HTML 时保存为 `rawHtml`。
 *
 * 说明：原实现在这里还调用了一个 2 参数方法 `m(html, cuid)` 预处理 HTML，但它在 JADX 合并类里
 * 无法按签名定位（同一类里混了多个混淆类的方法）。它对图片地址的规范化语义与渲染期的
 * [com.heikeji.phonesearch.protocol.render.AnswerHtmlSanitizer] 一致，因此这里不做预处理，
 * 统一交给渲染期处理——对最终展示结果等价。
 */
object AnswerParser {

    private val HTML_PREFIXES = listOf("<!doctype html", "<html")

    fun parse(
        decoded: String,
        index: Int,
        subjectName: String = "",
        sid: String = "",
        tid: String = "",
    ): AnswerItem {
        val title = "结果 $index"
        val lower = decoded.lowercase()
        if (HTML_PREFIXES.any { lower.startsWith(it) }) {
            return AnswerItem(
                index = index,
                title = title,
                questionHtml = "",
                questionImages = emptyList(),
                answerHtml = "",
                answerImages = emptyList(),
                analysisHtml = "",
                subject = subjectName,
                rawHtml = decoded,
                tid = tid,
            )
        }

        val root = Json.parseObject(decoded, "答案格式无法识别，未显示未解码数据")
        val question = root.objOrNull("question")
        val firstAnswer = root.arrOrNull("answer")?.let { array ->
            if (array.size() > 0 && array.get(0).isJsonObject) array.get(0).asJsonObject else null
        }

        val questionHtml = question.strOrEmpty("content")
        val answerHtml = firstAnswer.strOrEmpty("content")
        val questionImages = imagesOf(question)
        val answerImages = imagesOf(firstAnswer)

        var analysisHtml = root.strOrEmpty("subjectAnalysis")
        if (analysisHtml.isEmpty()) {
            analysisHtml = root.objOrNull("analysis")
                ?.objOrNull("subjectAnalysis")
                .strOrEmpty("content")
        }

        if (questionHtml.isEmpty() && answerHtml.isEmpty() &&
            questionImages.isEmpty() && answerImages.isEmpty() && analysisHtml.isEmpty()
        ) {
            throw ProtocolException("答案格式中没有可显示的题目、答案或解析")
        }

        val courseName = root.strOrEmpty("courseName")
        return AnswerItem(
            index = index,
            title = title,
            questionHtml = questionHtml,
            questionImages = questionImages,
            answerHtml = answerHtml,
            answerImages = answerImages,
            analysisHtml = analysisHtml,
            subject = courseName.ifEmpty { subjectName },
            rawHtml = null,
            tid = tid,
        )
    }

    /** 读取 picList 里的 pid 并解析成图片地址（原 f1.b.Y）。 */
    private fun imagesOf(container: JsonObject?): List<String> {
        val list = container.arrOrNull("picList") ?: return emptyList()
        val pids = ArrayList<String>(list.size())
        for (i in 0 until list.size()) {
            val element = list.get(i)
            if (!element.isJsonObject) continue
            val pid = element.asJsonObject.strOrEmpty("pid")
            if (pid.isNotEmpty()) pids.add(pid)
        }
        return ImagePidResolver.resolveAll(pids)
    }
}
