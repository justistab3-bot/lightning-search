package com.heikeji.phonesearch.net

import android.util.Base64
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.protocol.account.CheckIdentity
import com.heikeji.phonesearch.protocol.account.PasswordLogin
import com.heikeji.phonesearch.protocol.account.SmsLogin
import com.heikeji.phonesearch.protocol.account.SmsSend
import com.heikeji.phonesearch.protocol.account.UserInfo
import com.heikeji.phonesearch.protocol.core.crypto.Rc4
import com.heikeji.phonesearch.protocol.model.AccountSession
import org.json.JSONObject

/** 实名校验结果。 */
data class IdentityResult(val age: Int, val pass: Int)

/**
 * 账号域 API（原 P0.c 的登录/用户资料/实名校验部分）。
 *
 * 官方标准：端点与参数由 `protocol.account.*` 的 Input 声明，
 * 执行器是 [Net]（统一签名/信封/外壳）；本类只做账号域编排。
 */
class AccountApi(
    private val net: Net,
    private val protocol: ProtocolContext,
    private val sessions: SessionRepository,
) {

    // ------------------------------------------------------------------ 登录

    fun sendSmsCode(phone: String) {
        val normalized = normalizePhone(phone)
        val inner = LinkedHashMap<String, String?>()
        inner["phone"] = normalized
        net.postEncrypted(SmsSend.Input.buildInput(normalized), inner)
    }

    fun loginWithSmsCode(phone: String, tokenCode: String): AccountSession {
        val code = tokenCode.trim()
        if (!code.matches(Regex("[0-9]{4,8}"))) throw ApiException("请输入短信验证码")
        val inner = LinkedHashMap<String, String?>()
        inner["phone"] = normalizePhone(phone)
        inner["tokenCode"] = code
        inner["inviteCode"] = ""
        inner["idfa"] = ""
        inner["yongsterStatus"] = "0"
        val data = net.postEncrypted(SmsLogin.Input.buildInput(normalizePhone(phone), code), inner)
        return completeLogin(data)
    }

    fun loginWithPassword(phone: String, password: String): AccountSession {
        if (password.isEmpty()) throw ApiException("请输入密码")
        val inner = LinkedHashMap<String, String?>()
        inner["phone"] = normalizePhone(phone)
        inner["password"] = password
        inner["idfa"] = ""
        inner["yongsterStatus"] = "0"
        val data = net.postEncrypted(
            PasswordLogin.Input.buildInput(normalizePhone(phone), password),
            inner,
        )
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

    // ------------------------------------------------------------------ 实名

    fun checkIdentity(name: String, id: String): IdentityResult {
        if (sessions.current() == null) throw SessionExpiredException()
        if (name.trim().isEmpty() || name.length > 80 || !id.matches(Regex("[0-9]{17}[0-9Xx]"))) {
            throw ApiException("请检查姓名和身份证号")
        }
        val key = protocol.responseKey()
        val nameEncrypted = Base64.encodeToString(
            Rc4.apply(name.toByteArray(Charsets.UTF_8), key), Base64.NO_WRAP,
        )
        val idEncrypted = Base64.encodeToString(
            Rc4.apply(id.toByteArray(Charsets.UTF_8), key), Base64.NO_WRAP,
        )
        val input = CheckIdentity.Input.buildInput(nameEncrypted, idEncrypted)
        val data = net.post(input)
        return try {
            val json = JSONObject(net.decryptString(data.optString("data", "")))
            IdentityResult(age = json.getInt("age"), pass = json.getInt("pass"))
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("身份验证响应无法识别", 0, e)
        }
    }

    // ------------------------------------------------------------------ 内部

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

    private fun fetchUserInfo(kdussOverride: String?): JSONObject =
        net.post(UserInfo.Input, kdussOverride = kdussOverride)

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
        return net.decryptString(encrypted).trim().toIntOrNull()
            ?: throw ApiException("用户资料数值解密失败")
    }

    private fun decryptIfPresent(info: JSONObject, field: String): String {
        val value = info.optString(field, "")
        if (value.isEmpty()) return ""
        // 服务端在部分场景返回明文，解密失败时回退到原值。
        return try {
            net.decryptString(value)
        } catch (e: ApiException) {
            value
        }
    }

    private fun normalizePhone(phone: String): String {
        val normalized = phone.replace(" ", "").trim()
        if (!normalized.matches(Regex("1[0-9]{10}"))) throw ApiException("请输入11位手机号")
        return normalized
    }
}
