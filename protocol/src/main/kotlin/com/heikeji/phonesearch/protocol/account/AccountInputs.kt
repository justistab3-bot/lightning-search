package com.heikeji.phonesearch.protocol.account

import com.heikeji.phonesearch.protocol.core.InputBase
import com.heikeji.phonesearch.protocol.core.NetConfig

/**
 * 短信验证码接口，对应官方 `POST /session/submit/tokengettokenv2`（登录主机）。
 */
object SmsSend {

    /** 端点。 */
    const val URL = "/session/submit/tokengettokenv2"

    /** Input。 */
    class Input private constructor(private val phone: String) : InputBase() {
        override val modelClass: Class<*> = SmsSend::class.java
        override val url: String = URL
        override val pid: String = NetConfig.PID_PASSPORT
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf("phone" to phone)

        companion object {
            /** buildInput（phone 已规范化）。 */
            fun buildInput(phone: String): Input = Input(phone)
        }
    }
}

/**
 * 短信验证码登录，对应官方 `POST /session/submit/tokenloginv2`。
 */
object SmsLogin {

    /** 端点。 */
    const val URL = "/session/submit/tokenloginv2"

    /** Input。 */
    class Input private constructor(
        private val phone: String,
        private val tokenCode: String,
    ) : InputBase() {
        override val modelClass: Class<*> = SmsLogin::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "phone" to phone,
            "tokenCode" to tokenCode,
            "inviteCode" to "",
            "idfa" to "",
            "yongsterStatus" to "0",
        )

        companion object {
            /** buildInput。 */
            fun buildInput(phone: String, tokenCode: String): Input =
                Input(phone, tokenCode)
        }
    }
}

/**
 * 密码登录，对应官方 `POST /session/submit/loginv2`。
 */
object PasswordLogin {

    /** 端点。 */
    const val URL = "/session/submit/loginv2"

    /** Input。 */
    class Input private constructor(
        private val phone: String,
        private val password: String,
    ) : InputBase() {
        override val modelClass: Class<*> = PasswordLogin::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "phone" to phone,
            "password" to password,
            "idfa" to "",
            "yongsterStatus" to "0",
        )

        companion object {
            /** buildInput。 */
            fun buildInput(phone: String, password: String): Input =
                Input(phone, password)
        }
    }
}

/**
 * 用户资料，对应官方 `POST /kdcore/user/userinfov3`。
 */
object UserInfo {

    /** 端点。 */
    const val URL = "/kdcore/user/userinfov3"

    /** Input。 */
    object Input : InputBase() {
        override val modelClass: Class<*> = UserInfo::class.java
        override val url: String = URL
        override val pid: String = ""
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "getAchievement" to "0",
            "isHitCoupon" to "0",
            "scenePage" to "other",
        )
    }
}

/**
 * 实名校验，对应官方 `POST /resourceserver/checkidentity`（资源主机）。
 *
 * `name`/`id` 由调用方用 responseKey 做 RC4+Base64 加密后传入。
 */
object CheckIdentity {

    /** 端点。 */
    const val URL = "/resourceserver/checkidentity"

    /** Input。 */
    class Input private constructor(
        private val nameEncrypted: String,
        private val idEncrypted: String,
    ) : InputBase() {
        override val modelClass: Class<*> = CheckIdentity::class.java
        override val url: String = URL
        override val pid: String = NetConfig.PID_RESOURCE
        override val method: Int = METHOD_POST

        override fun params(): Map<String, Any?> = linkedMapOf(
            "name" to nameEncrypted,
            "id" to idEncrypted,
        )

        companion object {
            /** buildInput（入参已是 RC4+Base64 密文）。 */
            fun buildInput(nameEncrypted: String, idEncrypted: String): Input =
                Input(nameEncrypted, idEncrypted)
        }
    }
}
