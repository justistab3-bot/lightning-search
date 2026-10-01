package com.heikeji.phonesearch.protocol.decode

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.codec.Base64NoWrap
import com.heikeji.phonesearch.protocol.crypto.Rc4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * 答案解码（原 f1.b.A）。
 *
 * 三条路径，取决于响应的 `encode` 与 `answers.encryption`：
 *
 * | encode | encryption | 处理 |
 * |---|---:|---|
 * | 1 | - | 只做 Base64 解码 |
 * | 0 | 0 | RC4(base64decode(mainPageInfo), responseKey) |
 * | 0 | 1 | tidKey = RC4(base64decode(tid), responseKey)<br>stage = RC4(base64decode(mainPageInfo), tidKey)<br>结果 = RC4(base64decode(stage), responseKey) |
 *
 * 明文直通：内容以 `{`、`<!doctype html` 或 `<html`（大小写不敏感）开头时原样返回。
 * `gzip` 为 1 时先解压，解压后上限 8 MiB。
 */
object AnswerDecoder {

    /**
     * @param raw `answers.mainPageInfo[i]` 原始字符串
     * @param tid `answers.tids[i]`，仅 encryption == 1 时使用
     * @param encode 顶层 `encode`
     * @param encryption `answers.encryption`
     * @param gzip `answers.gzip == 1`
     * @param responseKey ProtocolContext 派生出的 128 字符密钥；明文直通时可为 null
     */
    fun decode(
        raw: String,
        tid: String = "",
        encode: Int = 0,
        encryption: Int = 0,
        gzip: Boolean = false,
        responseKey: String? = null,
    ): String {
        // 原实现由调用方 O0.a 校验取值范围，这里前移以保持同样的可观察行为。
        if (encode != 0 && encode != 1) {
            throw ProtocolException("暂不支持服务返回的答案编码类型：$encode")
        }
        if (encryption != 0 && encryption != 1) {
            throw ProtocolException("暂不支持服务返回的答案加密类型：$encryption")
        }

        val trimmed = raw.trim()
        if (trimmed.startsWith(ProtocolProfile.ANSWER_PLAINTEXT_JSON_PREFIX)) return trimmed

        // 整页响应可能直接返回候选答案数组（1.1.1 新增形态）。
        if (trimmed.startsWith(ProtocolProfile.ANSWER_PLAINTEXT_JSON_ARRAY_PREFIX)) return trimmed

        val lower = trimmed.lowercase()
        if (lower.startsWith(ProtocolProfile.ANSWER_PLAINTEXT_HTML_PREFIX) ||
            lower.startsWith(ProtocolProfile.ANSWER_PLAINTEXT_HTML2_PREFIX)
        ) {
            return trimmed
        }

        var bytes: ByteArray = when {
            encode == 1 -> Base64NoWrap.decode(trimmed)

            responseKey.isNullOrEmpty() -> throw ProtocolException("答案已加密，但解密密钥不可用")

            encryption != 1 -> Rc4.apply(Base64NoWrap.decode(trimmed), responseKey)

            else -> {
                if (tid.isEmpty()) throw ProtocolException("加密答案缺少对应题目编号")
                val tidKey = String(
                    Rc4.apply(Base64NoWrap.decode(tid), responseKey),
                    StandardCharsets.UTF_8,
                )
                if (tidKey.isEmpty()) throw ProtocolException("题目编号解码失败")
                val stage = String(
                    Rc4.apply(Base64NoWrap.decode(trimmed), tidKey),
                    StandardCharsets.UTF_8,
                )
                Rc4.apply(Base64NoWrap.decode(stage), responseKey)
            }
        }

        if (gzip) bytes = gunzip(bytes)

        if (bytes.size > ProtocolProfile.MAX_ANSWER_BYTES) {
            throw ProtocolException("答案内容过大，无法在此设备显示")
        }
        return String(bytes, StandardCharsets.UTF_8).trim()
    }

    /**
     * 原实现把所有 gzip 相关的 IOException（含"答案解压后过大"）统一包装成
     * "答案压缩数据无效或过大"，这里保持一致。
     */
    private fun gunzip(data: ByteArray): ByteArray {
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (out.size() + read > ProtocolProfile.MAX_ANSWER_BYTES) {
                        throw IOException("答案解压后过大")
                    }
                    out.write(buffer, 0, read)
                }
                return out.toByteArray()
            }
        } catch (e: IOException) {
            throw ProtocolException("答案压缩数据无效或过大", e)
        }
    }
}
