package com.heikeji.phonesearch.protocol.book

import com.google.gson.JsonObject
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.book.model.BookAnswerPage
import com.heikeji.phonesearch.protocol.book.model.BookSearchResult
import com.heikeji.phonesearch.protocol.book.model.RelatedBookInfo
import com.heikeji.phonesearch.protocol.codec.Base64NoWrap
import com.heikeji.phonesearch.protocol.crypto.Rc4
import com.heikeji.phonesearch.protocol.json.Json
import com.heikeji.phonesearch.protocol.json.arrOrNull
import com.heikeji.phonesearch.protocol.json.intOr
import com.heikeji.phonesearch.protocol.json.objOrNull
import com.heikeji.phonesearch.protocol.json.strOrEmpty

/**
 * 「查看整本答案」响应解析。
 *
 * 对应原生 `SearchBookSearch`。答案页有两处来源，优先用信息更全的 `answerList`：
 * - `answerList[]` -> `{origin, thumbnail, w, h, isHD}`
 * - `answers[]` / `oriAnswers[]` -> 纯 URL 列表（原生会把 answerList 摊平成这两个）
 */
object BookSearchParser {

    /**
     * @param responseKey 用于解 `name` / `cover` 这类单独加密的字段；为 null 时跳过解密
     */
    fun parseJson(dataJson: String, responseKey: String? = null): BookSearchResult =
        parse(Json.parseObject(dataJson, "教材答案格式无法识别"), responseKey)

    fun parse(root: JsonObject, responseKey: String? = null): BookSearchResult {
        val pages = pagesOf(root)

        return BookSearchResult(
            bookId = root.strOrEmpty("bookId"),
            name = decryptIfNeeded(root.strOrEmpty("name"), responseKey),
            subject = root.strOrEmpty("subject"),
            grade = root.strOrEmpty("grade"),
            term = root.strOrEmpty("term"),
            version = root.strOrEmpty("version"),
            cover = decryptIfNeeded(root.strOrEmpty("cover"), responseKey),
            hasAnswer = root.intOr("hasAnswer", 0) != 0 || pages.isNotEmpty(),
            pages = pages,
        )
    }

    /** 没有答案页时抛异常，便于调用方直接走错误分支。 */
    fun requirePages(root: JsonObject, responseKey: String? = null): BookSearchResult {
        val result = parse(root, responseKey)
        if (result.pages.isEmpty()) throw ProtocolException("这本书暂时没有可查看的答案")
        return result
    }

    // ------------------------------------------------------------------ 内部

    private fun pagesOf(root: JsonObject): List<BookAnswerPage> {
        val fromList = ArrayList<BookAnswerPage>()
        val answerList = root.arrOrNull("answerList")
        if (answerList != null) {
            for (i in 0 until answerList.size()) {
                val element = answerList.get(i)
                if (!element.isJsonObject) continue
                val node = element.asJsonObject
                val origin = node.strOrEmpty("origin")
                val thumbnail = node.strOrEmpty("thumbnail")
                if (origin.isEmpty() && thumbnail.isEmpty()) continue
                fromList.add(
                    BookAnswerPage(
                        origin = origin,
                        thumbnail = thumbnail,
                        width = node.intOr("w", 0),
                        height = node.intOr("h", 0),
                        isHd = node.get("isHD")?.let { it.isJsonPrimitive && it.asBoolean } == true,
                    ),
                )
            }
        }
        if (fromList.isNotEmpty()) return fromList

        // 退路：answers（缩略图）+ oriAnswers（原图），按下标对齐
        val thumbs = stringList(root, "answers")
        val origins = stringList(root, "oriAnswers")
        val count = maxOf(thumbs.size, origins.size)
        val fallback = ArrayList<BookAnswerPage>(count)
        for (i in 0 until count) {
            val thumbnail = thumbs.getOrNull(i).orEmpty()
            val origin = origins.getOrNull(i).orEmpty()
            if (origin.isEmpty() && thumbnail.isEmpty()) continue
            fallback.add(
                BookAnswerPage(
                    origin = origin,
                    thumbnail = thumbnail,
                    width = 0,
                    height = 0,
                    isHd = false,
                ),
            )
        }
        return fallback
    }

    private fun stringList(root: JsonObject, name: String): List<String> {
        val array = root.arrOrNull(name) ?: return emptyList()
        val out = ArrayList<String>(array.size())
        for (i in 0 until array.size()) {
            val element = array.get(i)
            if (element.isJsonPrimitive) out.add(element.asString)
        }
        return out
    }

    /**
     * `name` / `cover` 有时会被单独加密（`pagebookinfo` 就是这样）。
     * 解不出来就原样返回，不能因为一个字段把整页搞崩。
     */
    private fun decryptIfNeeded(value: String, responseKey: String?): String {
        if (value.isEmpty() || responseKey.isNullOrEmpty()) return value
        // 明文不会有这么长的纯 base64 特征
        if (value.length < 24 || value.contains(' ')) return value
        return try {
            val plain = String(Rc4.apply(Base64NoWrap.decode(value), responseKey), Charsets.UTF_8)
            if (plain.isEmpty() || plain.any { it.code < 0x20 && it != '\n' }) value else plain
        } catch (e: Exception) {
            value
        }
    }

    // ------------------------------------------------------------------ 整页搜题里的教材信息

    /**
     * 从任意一层 JSON 里挖出教材信息。
     *
     * 这个字段的位置在不同版本里飘过，实测已知有三处：
     * - 解码后的**单题答案** JSON 根层（H5 用的就是这里，`S.relatedBook`）
     * - 整页响应的 `data.relatedBook`
     * - 整页响应的 `data.answers.relatedBook`
     *
     * 所以按「答案 -> data -> answers」顺序都找一遍，找不到就返回 null（按钮不显示）。
     */
    fun relatedBookOf(data: JsonObject): RelatedBookInfo? {
        val scopes = ArrayList<JsonObject>(3)
        scopes.add(data)
        data.objOrNull("answers")?.let { scopes.add(it) }
        // 解码后的单题答案里 answer 是数组，字段也可能挂在第一条上
        data.arrOrNull("answer")?.let { array ->
            if (array.size() > 0 && array.get(0).isJsonObject) {
                scopes.add(array.get(0).asJsonObject)
            }
        }
        for (scope in scopes) {
            relatedBookIn(scope)?.let { return it }
        }
        return null
    }

    /** 诊断用：把各处找到的原始 bookId 原样报出来，不做校验。 */
    fun rawBookIds(data: JsonObject): String {
        val parts = ArrayList<String>(3)
        for (scope in listOf(data, data.objOrNull("answers"))) {
            if (scope == null) continue
            val nested = scope.objOrNull("relatedBook").strOrEmpty("bookId")
            parts.add("relatedBook.bookId='$nested'")
            parts.add("bookId='${scope.strOrEmpty("bookId")}'")
        }
        return parts.joinToString("  ")
    }

    private fun relatedBookIn(scope: JsonObject): RelatedBookInfo? {
        val related = scope.objOrNull("relatedBook")
        // 只认 relatedBook.bookId。
        // 顶层 bookId 在答案 JSON 里另有含义（不是教材 id），拿它兜底会让每道题都误报。
        val bookId = related.strOrEmpty("bookId")
        if (!isBookId(bookId)) return null
        return RelatedBookInfo(
            bookId = bookId.lowercase(),
            pageId = related.strOrEmpty("pageId").ifEmpty { scope.strOrEmpty("pageId") },
            bookName = related.strOrEmpty("bookName"),
            tid = related.strOrEmpty("tid").ifEmpty { scope.strOrEmpty("qid") },
        )
    }

    /** 教材 id 是 32 位十六进制。 */
    private fun isBookId(value: String): Boolean =
        value.length == 32 && value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}
