package com.heikeji.phonesearch.protocol.sign

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.crypto.DesCodec
import com.heikeji.phonesearch.protocol.crypto.Digests
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 通用请求签名（原 P0.f.e）。
 *
 * 输入是**尚未 URL 编码**的 `key=value` 列表。追加 `_t_`（Unix 秒）与
 * `kakorrhaphiophobia`（ProtocolContext 初始化时的 elapsedRealtime 毫秒）后，
 * 字典序排序、无分隔拼接、Base64(NO_WRAP)，最后取 MD5 小写 hex。
 */
object RequestSigner {

    private val SIGN_REGEX = Regex("[0-9a-f]{32}")

    /**
     * @param items 业务参数与公共参数合并后的 `key=value` 列表（原始值，未编码）
     * @param deviceSecretDigest md5Lower(deviceSecret)
     * @param tSeconds Unix 秒
     * @param uptimeMillis ProtocolContext 初始化时记录的 elapsedRealtime
     */
    fun sign(
        items: List<String>,
        deviceSecretDigest: String,
        tSeconds: Long,
        uptimeMillis: Long,
    ): String {
        for (item in items) {
            if (item.indexOf('\u0000') >= 0) {
                throw ProtocolException("签名参数包含无效字符串")
            }
            if (item.startsWith("sign=") ||
                item.startsWith("_t_=") ||
                item.startsWith("kakorrhaphiophobia=")
            ) {
                throw ProtocolException("签名参数包含重复的保留字段")
            }
        }

        val all = ArrayList<String>(items.size + 2)
        all.addAll(items)
        all.add("_t_=$tSeconds")
        all.add("kakorrhaphiophobia=$uptimeMillis")
        all.sort()

        val canonical = all.joinToString(separator = "")
        val encoded = Base64.getEncoder()
            .encodeToString(canonical.toByteArray(StandardCharsets.UTF_8))
        DesCodec.requirePrintableAscii(encoded, requireNonEmpty = false)

        val sign = Digests.md5Lower(
            ProtocolProfile.SIGN_PREFIX + deviceSecretDigest + "]@" + encoded,
        )
        if (!SIGN_REGEX.matches(sign)) {
            throw ProtocolException("设备签名算法返回无效签名")
        }
        return sign
    }

    /**
     * 原 P0.c.f 在发送前的健全性检查：签名不能为空、不能是错误哨兵、不能含 '&'。
     */
    fun isUsable(sign: String): Boolean =
        sign.isNotEmpty() &&
            !sign.startsWith("error") &&
            sign != "init_error" &&
            sign != "so_error" &&
            !sign.contains('&')

    /** 把参数表转成 `key=value` 列表（保持插入顺序，不做 URL 编码）。 */
    fun toItems(params: Map<String, String?>): List<String> =
        params.entries.map { (key, value) -> "$key=${value ?: ""}" }
}
