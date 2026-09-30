package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.protocol.envelope.Envelope

/**
 * antispam 初始化请求（原 f1.b.H）。
 *
 * 与普通 API 的区别：
 * - 表单由 `data=signA` 打头，后接公共参数（putIfAbsent）；
 * - **不加**通用 `sign` / `_t_` / `kakorrhaphiophobia`；
 * - 不带 Cookie；
 * - `Accept-Encoding: gzip`，read timeout 20s，响应上限 1 MiB。
 */
class BootstrapClient(
    private val identity: DeviceIdentity,
    private val transport: HttpTransport,
) {

    /**
     * @param onDate 收到响应后回传 Date 头（毫秒，0 表示缺失）用于校时
     * @return 最内层的 data 字符串，即 signB
     */
    fun fetchSignB(signA: String, onDate: (Long) -> Unit): String {
        val params = LinkedHashMap<String, String?>()
        params["data"] = signA
        for ((key, value) in identity.publicParams()) {
            if (!params.containsKey(key)) params[key] = value
        }

        val result = transport.post(
            host = ProtocolProfile.HOST_KDDZY,
            path = ProtocolProfile.PATH_ANTISPAM,
            body = UrlForm.encodeForm(params).toByteArray(Charsets.UTF_8),
            contentType = ProtocolProfile.FORM_CONTENT_TYPE,
            cookie = null,
            acceptGzip = true,
            readTimeoutMs = ProtocolProfile.ANTISPAM_READ_TIMEOUT_MS,
            maxBytes = ProtocolProfile.MAX_ANTISPAM_RESPONSE_BYTES,
        )
        onDate(result.dateMillis)

        if (result.statusCode !in 200..299) {
            throw ProtocolException("设备签名初始化服务返回 HTTP ${result.statusCode}")
        }
        return Envelope.extractInnerData(result.body)
    }
}
