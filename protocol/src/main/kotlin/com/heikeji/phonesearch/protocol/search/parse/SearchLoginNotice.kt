package com.heikeji.phonesearch.protocol.search.parse

import com.heikeji.phonesearch.protocol.search.model.AnswerItem
import org.jsoup.Jsoup

/** A service login notice is a request for authentication, rather than an answer. */
object SearchLoginNotice {
    private val separators = Regex("[\\s\\p{P}\\p{Z}]")
    private val directive = Regex("(?:请先在|先在|请在|请去|请到|请前往|前往)个人中心(?:进行)?登录")

    fun isRequired(vararg html: String): Boolean {
        val text = html.joinToString("") { Jsoup.parse(it).text() }.replace(separators, "")
        return text.contains("个人中心") &&
            (text.contains("登录提醒") || directive.containsMatchIn(text))
    }

    fun isRequired(item: AnswerItem): Boolean = isRequired(
        item.questionHtml, item.answerHtml, item.analysisHtml, item.rawHtml.orEmpty(),
    )

    fun check(vararg html: String) {
        if (isRequired(*html)) throw SearchLoginRequiredException()
    }
}

/** Does not invalidate or delete a locally stored account. */
class SearchLoginRequiredException : Exception("搜题服务提示：请前往个人中心登录，登录后可继续搜索原题。")
