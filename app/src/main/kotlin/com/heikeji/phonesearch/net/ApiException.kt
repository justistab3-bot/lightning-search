package com.heikeji.phonesearch.net

/**
 * 业务/HTTP 失败（对应原 P0.b）。
 *
 * [code] 为服务端 errNo 或 HTTP 状态码；3 表示会话失效。
 */
open class ApiException(
    message: String,
    val code: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 登录态失效，需要重新登录。 */
class SessionExpiredException(
    message: String = "登录已失效，请重新登录",
) : ApiException(message, code = 3)

/** 服务端返回 validatedInfo，必须先完成官方安全验证再用同一张图重试。 */
class SearchChallengeException(
    val validatedInfo: String,
    val sid: String,
) : Exception("需要完成安全验证") {
    override fun toString(): String = "SearchChallengeException(sid=$sid, validatedInfo=<redacted>)"
}
