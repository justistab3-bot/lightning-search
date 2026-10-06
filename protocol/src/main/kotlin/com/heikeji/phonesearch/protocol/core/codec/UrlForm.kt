package com.heikeji.phonesearch.protocol.core.codec

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 表单与单值 URL 编码（原 f1.b.E / f1.b.F）。
 *
 * 注意：编码后空格会变成 '+'，这是原实现的行为，保持不变。
 */
object UrlForm {

    /** 单值编码，用于 Cookie 里的 cuid / KDUSS。 */
    fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /** `k=v&k=v` 表单体，保持传入顺序。 */
    fun encodeForm(params: Map<String, String?>): String =
        params.entries.joinToString(separator = "&") { (key, value) ->
            encode(key) + "=" + encode(value ?: "")
        }
}
