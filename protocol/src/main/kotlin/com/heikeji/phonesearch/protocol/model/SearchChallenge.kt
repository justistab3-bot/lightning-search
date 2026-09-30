package com.heikeji.phonesearch.protocol.model

/**
 * 服务端返回 validatedInfo 时的验证挑战。
 *
 * 仅保存在内存中，绝不落盘、绝不进日志。验证成功后必须逐项确认仍然匹配，
 * 再用**同一批图片字节**重新发起搜题。
 *
 * 与交接文档 §10.1 的差异：原实现 P0.k(validatedInfo, sid) 实际已把 sid 传进挑战，
 * 这里同样保存 sid 以便重试时使用。
 */
class SearchChallenge(
    /** 本次挑战的唯一标识，用于确认回调回来的是同一次挑战。 */
    val localToken: String,
    /** 服务端下发的 validatedInfo，仅内存持有。 */
    val validatedInfo: String,
    /** 服务端返回的 sid。 */
    val sid: String,
    /** 原始 JPEG 字节（用于验证后用同一张图重试）。 */
    val originalJpeg: ByteArray,
    /** 发起挑战时的 KDUSS 快照。 */
    val kdussSnapshot: String,
) {
    /** 验证回调后确认挑战未过期、图片未变、登录态未变。 */
    fun stillMatches(token: String, jpeg: ByteArray, kduss: String): Boolean =
        localToken == token &&
            originalJpeg.contentEquals(jpeg) &&
            kdussSnapshot == kduss

    override fun toString(): String =
        "SearchChallenge(token=$localToken, sid=$sid, jpegSize=${originalJpeg.size}, " +
            "validatedInfo=<redacted>)"
}
