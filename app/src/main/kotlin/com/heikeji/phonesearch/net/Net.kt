package com.heikeji.phonesearch.net

import android.util.Base64
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.core.InputBase
import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.core.codec.UrlForm
import com.heikeji.phonesearch.protocol.core.crypto.Rc4
import com.heikeji.phonesearch.protocol.core.sign.RequestSigner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * 统一请求执行器，对应官方 `com.baidu.homework.common.net.Net`。
 *
 * 官方标准：业务侧只构造 [InputBase]（端点 + 业务参数），执行器统一完成
 * 公共参数合并、身份覆盖（identityIdV2/occupationType/nt）、签名、
 * 信封（表单 / multipart）、传输与响应外壳解析。
 *
 * 本类由原 `ApiClient.post`（原 P0.c 的核心）平移而来，行为不变。
 */
class Net(
    private val identity: DeviceIdentity,
    private val protocol: ProtocolContext,
    private val sessions: SessionRepository,
    private val transport: HttpTransport,
) {

    /**
     * 官方 `Net.post`：发送一个 Input 并返回解壳后的 `data` 对象。
     *
     * @param image 非空时走 multipart（图片为第一部分），否则普通表单
     */
    fun post(
        input: InputBase,
        image: ByteArray? = null,
        kdussOverride: String? = null,
    ): JSONObject {
        val params = LinkedHashMap<String, String?>()
        for ((key, value) in input.params()) params[key] = value?.toString()
        return postInternal(
            host = NetConfig.hostForPid(input.pid),
            path = input.url,
            params = params,
            image = image,
            kdussOverride = kdussOverride,
        )
    }

    /**
     * 登录类接口的内层加密（原 P0.c.b）：内层参数拼表单 -> RC4(responseKey) ->
     * Base64 放进外层 `data`，再走通用执行链路。
     */
    fun postEncrypted(
        input: InputBase,
        innerParams: LinkedHashMap<String, String?>,
    ): JSONObject {
        protocol.ensureInitialized()
        val plain = "&" + UrlForm.encodeForm(innerParams)
        val cipher = Rc4.apply(plain.toByteArray(Charsets.UTF_8), protocol.responseKey())

        val outer = LinkedHashMap<String, String?>()
        outer["data"] = Base64.encodeToString(cipher, Base64.NO_WRAP)

        val data = postInternal(
            host = NetConfig.hostForPid(input.pid),
            path = input.url,
            params = outer,
        )
        val dataString = data.optString("data", "")
        if (dataString.isEmpty()) return JSONObject()
        return try {
            JSONObject(decryptString(dataString))
        } catch (e: JSONException) {
            throw ApiException("登录响应解密格式错误", 0, e)
        }
    }

    /** 原 P0.c.a：RC4 解密 Base64 字符串。 */
    fun decryptString(value: String): String {
        if (value.isEmpty()) return ""
        return try {
            String(
                Rc4.apply(Base64.decode(value, Base64.NO_WRAP), protocol.responseKey()),
                Charsets.UTF_8,
            )
        } catch (e: IllegalArgumentException) {
            throw ApiException("响应解密失败", 0, e)
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 通用 POST：合并公共参数 -> 强制覆盖 identityIdV2/occupationType/nt ->
     * 签名 -> 信封 -> 发送 -> 解外壳（原 ApiClient.post）。
     */
    private fun postInternal(
        host: String,
        path: String,
        params: LinkedHashMap<String, String?>,
        image: ByteArray? = null,
        kdussOverride: String? = null,
    ): JSONObject {
        protocol.ensureInitialized()

        val merged = LinkedHashMap<String, String?>()
        merged.putAll(params)
        for ((key, value) in identity.publicParams()) {
            if (!merged.containsKey(key)) merged[key] = value
        }
        val session = sessions.current()
        // 官方手机客户端匿名状态也发 identityIdV2=1（身份已初始化）；
        // 登录后以会话里的值为准。
        merged["identityIdV2"] = (session?.identityIdV2 ?: 1).toString()
        merged["occupationType"] = (session?.occupationType ?: 0).toString()
        merged["nt"] = identity.networkType()

        val tSeconds = protocol.nowSeconds()
        val uptime = protocol.uptimeMillis()
        val sign = RequestSigner.sign(
            items = RequestSigner.toItems(merged),
            deviceSecretDigest = protocol.deviceSecretDigest(),
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        if (!RequestSigner.isUsable(sign)) throw ProtocolException("签名初始化失败，未发送请求")
        merged["sign"] = sign
        merged["_t_"] = tSeconds.toString()
        merged["kakorrhaphiophobia"] = uptime.toString()

        val contentType: String
        val body: ByteArray
        if (image == null) {
            contentType = NetConfig.FORM_CONTENT_TYPE
            body = UrlForm.encodeForm(merged).toByteArray(Charsets.UTF_8)
        } else {
            val boundary = NetConfig.MULTIPART_BOUNDARY_PREFIX +
                UUID.randomUUID().toString().replace("-", "")
            contentType = "multipart/form-data; boundary=$boundary"
            body = Multipart.build(boundary, image, params = merged)
        }

        val kduss = kdussOverride ?: sessions.kduss()
        val cookie = if (host in NetConfig.COOKIE_HOSTS) {
            buildString {
                append("cuid=").append(UrlForm.encode(identity.cuid))
                if (kduss.isNotEmpty()) append("; KDUSS=").append(UrlForm.encode(kduss))
            }
        } else {
            null
        }
        val hadKduss = kduss.isNotEmpty() && host in NetConfig.COOKIE_HOSTS

        val result = transport.post(host, path, body, contentType, cookie)
        protocol.calibrate(result.dateMillis)

        if (result.statusCode in 200..299) return unwrap(result.body, kduss)

        if (result.statusCode == 401 && hadKduss) sessions.clearIfCurrent(kduss)
        val message = if (result.statusCode == 401 && hadKduss) {
            "登录已失效，请重新登录"
        } else {
            "网络请求失败（HTTP ${result.statusCode}）"
        }
        throw ApiException(message, result.statusCode)
    }

    /** 响应外壳（原 P0.c.h）。 */
    private fun unwrap(body: String, kduss: String): JSONObject {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw ApiException("服务器响应格式无法识别", 0, e)
        }
        val errNo = if (json.has("errNo")) {
            json.optInt("errNo", -1)
        } else {
            json.optInt("errno", -1)
        }
        if (errNo != 0) {
            if (errNo == 3) sessions.clearIfCurrent(kduss)
            throw ApiException(json.optString("errstr", "服务器拒绝请求（$errNo）"), errNo)
        }
        val data = json.opt("data")
        return when {
            data is JSONObject -> data
            data == null || data === JSONObject.NULL || data is JSONArray -> JSONObject()
            else -> {
                val text = data.toString().trim()
                if (text.isNotEmpty() && !text.startsWith("[")) {
                    try {
                        JSONObject(text)
                    } catch (e: JSONException) {
                        JSONObject()
                    }
                } else {
                    JSONObject()
                }
            }
        }
    }
}
