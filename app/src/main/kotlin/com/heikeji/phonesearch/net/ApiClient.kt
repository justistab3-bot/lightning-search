package com.heikeji.phonesearch.net

import android.util.Base64
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.protocol.ProtocolException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.protocol.crypto.Digests
import com.heikeji.phonesearch.protocol.crypto.Rc4
import com.heikeji.phonesearch.protocol.model.AccountSession
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.protocol.sign.RequestSigner
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** 实名校验结果。 */
data class IdentityResult(val age: Int, val pass: Int)

/**
 * 业务 API 客户端（原 P0.c）。
 *
 * 负责参数合并、签名、multipart、Cookie、响应外壳解析、登录与用户资料。
 * 答案的解码与解析不在这里，见 `search.SearchRepository`。
 */
class ApiClient(
    private val identity: DeviceIdentity,
    private val protocol: ProtocolContext,
    private val sessions: SessionRepository,
    private val transport: HttpTransport,
) {

    // ------------------------------------------------------------------ 登录

    fun sendSmsCode(phone: String) {
        val params = LinkedHashMap<String, String?>()
        params["phone"] = normalizePhone(phone)
        postEncrypted(ProtocolProfile.HOST_PASSPORT, ProtocolProfile.PATH_SMS_SEND, params)
    }

    fun loginWithSmsCode(phone: String, tokenCode: String): AccountSession {
        val code = tokenCode.trim()
        if (!code.matches(Regex("[0-9]{4,8}"))) throw ApiException("请输入短信验证码")
        val params = LinkedHashMap<String, String?>()
        params["phone"] = normalizePhone(phone)
        params["tokenCode"] = code
        params["inviteCode"] = ""
        params["idfa"] = ""
        params["yongsterStatus"] = "0"
        val data = postEncrypted(ProtocolProfile.HOST_KDDZY, ProtocolProfile.PATH_SMS_LOGIN, params)
        return completeLogin(data)
    }

    fun loginWithPassword(phone: String, password: String): AccountSession {
        if (password.isEmpty()) throw ApiException("请输入密码")
        val params = LinkedHashMap<String, String?>()
        params["phone"] = normalizePhone(phone)
        params["password"] = password
        params["idfa"] = ""
        params["yongsterStatus"] = "0"
        val data = postEncrypted(ProtocolProfile.HOST_KDDZY, ProtocolProfile.PATH_PASSWORD_LOGIN, params)
        return completeLogin(data)
    }

    /** 恢复会话时重新拉取用户资料，回填 grade / identityIdV2 / occupationType。 */
    fun refreshUserInfo(): AccountSession {
        val current = sessions.current() ?: throw SessionExpiredException()
        val info = fetchUserInfo(current.kduss)
        val session = applyUserInfo(current.kduss, info, current.userName, current.uid)
        sessions.updateInMemory(session)
        return session
    }

    // ------------------------------------------------------------------ 搜题

    /**
     * 发起图片搜题，返回响应 data 对象。
     *
     * 三种模式（原 `O0.l.f686a`）：
     * - [SearchMode.SINGLE]：普通单题，`referer=1`，`pageExtraInfo` 为空
     * - [SearchMode.PAGE]：整页搜题，`referer=home`，`imgCorrection=0`，**不发送 pageExtraInfo**
     * - [SearchMode.CROP_SINGLE]：框选重搜，`referer=3`，携带 `{wholeSearchSid,index,loc}`
     *
     * 若 data 含非空 validatedInfo，抛 [SearchChallengeException]，由上层走官方验证页后重试。
     */
    fun searchRaw(
        jpeg: ByteArray,
        grade: Int,
        mode: SearchMode,
        pageExtraInfo: String = "",
    ): JSONObject {
        if (jpeg.size < 4) throw ApiException("请选择或拍摄一张清晰题目图片")
        if ((jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) {
            throw ApiException("上传图片必须先转换为 JPEG")
        }

        val params = LinkedHashMap<String, String?>()
        params["picMD5"] = Digests.md5Upper(jpeg)
        params["shumei"] = ProtocolProfile.SEARCH_SHUMEI
        params["ref"] = ProtocolProfile.SEARCH_REF
        when (mode) {
            SearchMode.PAGE -> {
                params["referer"] = ProtocolProfile.SEARCH_REFERER_PAGE
                params["imgCorrection"] = ProtocolProfile.SEARCH_IMG_CORRECTION
            }

            SearchMode.CROP_SINGLE -> {
                params["pageExtraInfo"] = pageExtraInfo
                params["referer"] = ProtocolProfile.SEARCH_REFERER_CROP
            }

            SearchMode.SINGLE -> {
                params["pageExtraInfo"] = ""
                params["referer"] = ProtocolProfile.SEARCH_REFERER_SINGLE
            }
        }
        params["isStudentMode"] = ProtocolProfile.SEARCH_IS_STUDENT_MODE
        params["grade"] = grade.toString()
        params["from"] = ProtocolProfile.SEARCH_FROM

        val path = if (mode == SearchMode.PAGE) {
            ProtocolProfile.PATH_PAGE_SEARCH
        } else {
            ProtocolProfile.PATH_SEARCH
        }
        val data = post(ProtocolProfile.HOST_KDDZY, path, params, jpeg, null)
        val validatedInfo = data.optString("validatedInfo", "")
        if (validatedInfo.isNotEmpty()) {
            throw SearchChallengeException(validatedInfo, data.optString("sid", ""))
        }
        return data
    }

    // ------------------------------------------------------------------ 实名

    fun checkIdentity(name: String, id: String): IdentityResult {
        if (sessions.current() == null) throw SessionExpiredException()
        if (name.trim().isEmpty() || name.length > 80 || !id.matches(Regex("[0-9]{17}[0-9Xx]"))) {
            throw ApiException("请检查姓名和身份证号")
        }
        val key = protocol.responseKey()
        val params = LinkedHashMap<String, String?>()
        params["name"] = Base64.encodeToString(
            Rc4.apply(name.toByteArray(Charsets.UTF_8), key), Base64.NO_WRAP,
        )
        params["id"] = Base64.encodeToString(
            Rc4.apply(id.toByteArray(Charsets.UTF_8), key), Base64.NO_WRAP,
        )
        val data = post(ProtocolProfile.HOST_RESOURCE, ProtocolProfile.PATH_CHECK_IDENTITY, params)
        return try {
            val json = JSONObject(decryptString(data.optString("data", "")))
            IdentityResult(age = json.getInt("age"), pass = json.getInt("pass"))
        } catch (e: Exception) {
            throw ApiException("身份验证响应无法识别", 0, e)
        }
    }

    // ------------------------------------------------------------------ 内部

    /** 登录类接口的内层加密（原 P0.c.b）。 */
    private fun postEncrypted(
        host: String,
        path: String,
        innerParams: LinkedHashMap<String, String?>,
    ): JSONObject {
        protocol.ensureInitialized()
        val plain = "&" + UrlForm.encodeForm(innerParams)
        val cipher = Rc4.apply(plain.toByteArray(Charsets.UTF_8), protocol.responseKey())

        val outer = LinkedHashMap<String, String?>()
        outer["data"] = Base64.encodeToString(cipher, Base64.NO_WRAP)

        val data = post(host, path, outer)
        val dataString = data.optString("data", "")
        if (dataString.isEmpty()) return JSONObject()
        return try {
            JSONObject(decryptString(dataString))
        } catch (e: JSONException) {
            throw ApiException("登录响应解密格式错误", 0, e)
        }
    }

    /** 原 P0.c.a：RC4 解密 Base64 字符串。 */
    private fun decryptString(value: String): String {
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

    private fun completeLogin(loginData: JSONObject): AccountSession {
        val kduss = loginData.optString("kduss", "")
        if (kduss.isEmpty()) throw ApiException("服务端未返回登录会话")

        val info = fetchUserInfo(kduss)
        val userName = info.optString("uname", "")
        val uid = info.optString("uid", "")
        val session = applyUserInfo(kduss, info, userName, uid)
        sessions.update(session)
        return session
    }

    private fun fetchUserInfo(kdussOverride: String?): JSONObject {
        val params = LinkedHashMap<String, String?>()
        params["getAchievement"] = "0"
        params["isHitCoupon"] = "0"
        params["scenePage"] = "other"
        return post(
            ProtocolProfile.HOST_KDDZY,
            ProtocolProfile.PATH_USER_INFO,
            params,
            image = null,
            kdussOverride = kdussOverride,
        )
    }

    /**
     * 解密用户资料字段并组装会话（原 P0.c.c）。
     *
     * 原实现还会解密 phone / school 并写回 JSON，但之后从未读取；
     * 这里不解密它们，减少敏感数据的处理面。
     */
    private fun applyUserInfo(
        kduss: String,
        info: JSONObject,
        fallbackName: String,
        fallbackUid: String,
    ): AccountSession {
        val userName = decryptIfPresent(info, "uname").ifEmpty { fallbackName }
        val uid = info.optString("uid", "").ifEmpty { fallbackUid }

        return AccountSession(
            kduss = kduss,
            userName = userName,
            uid = uid,
            grade = decryptInt(info, "grade"),
            identityIdV2 = decryptInt(info, "identityIdV2"),
            occupationType = decryptInt(info, "occupationType"),
        )
    }

    /** `xxxEncrypt` 优先，缺失时回退到 `xxx`。 */
    private fun decryptInt(info: JSONObject, field: String): Int {
        val encrypted = info.optString("${field}Encrypt", "")
        if (encrypted.isEmpty()) return info.optInt(field, 0)
        return decryptString(encrypted).trim().toIntOrNull()
            ?: throw ApiException("用户资料数值解密失败")
    }

    private fun decryptIfPresent(info: JSONObject, field: String): String {
        val value = info.optString(field, "")
        if (value.isEmpty()) return ""
        // 服务端在部分场景返回明文，解密失败时回退到原值。
        return try {
            decryptString(value)
        } catch (e: ApiException) {
            value
        }
    }

    /**
     * 通用 POST：合并公共参数 -> 强制覆盖 identityIdV2/occupationType/nt -> 签名 -> 发送 -> 解外壳。
     */
    private fun post(
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
        merged["identityIdV2"] = (session?.identityIdV2 ?: 0).toString()
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
            contentType = ProtocolProfile.FORM_CONTENT_TYPE
            body = UrlForm.encodeForm(merged).toByteArray(Charsets.UTF_8)
        } else {
            val boundary = ProtocolProfile.MULTIPART_BOUNDARY_PREFIX +
                UUID.randomUUID().toString().replace("-", "")
            contentType = "multipart/form-data; boundary=$boundary"
            body = buildMultipart(boundary, image, merged)
        }

        val kduss = kdussOverride ?: sessions.kduss()
        val cookie = if (host in ProtocolProfile.COOKIE_HOSTS) {
            buildString {
                append("cuid=").append(UrlForm.encode(identity.cuid))
                if (kduss.isNotEmpty()) append("; KDUSS=").append(UrlForm.encode(kduss))
            }
        } else {
            null
        }
        val hadKduss = kduss.isNotEmpty() && host in ProtocolProfile.COOKIE_HOSTS

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

    private fun buildMultipart(
        boundary: String,
        image: ByteArray,
        params: LinkedHashMap<String, String?>,
    ): ByteArray = Multipart.build(boundary, image, params = params)

    private fun normalizePhone(phone: String): String {
        val normalized = phone.replace(" ", "").trim()
        if (!normalized.matches(Regex("1[0-9]{10}"))) throw ApiException("请输入11位手机号")
        return normalized
    }
}
