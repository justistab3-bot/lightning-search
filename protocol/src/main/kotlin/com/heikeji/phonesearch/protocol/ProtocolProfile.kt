package com.heikeji.phonesearch.protocol

/**
 * 协议常量集中处。UI 与 Activity 不得出现任何协议字面量。
 *
 * 这些是**版本绑定**值，来自样本 com.heikeji.watchsearch 1.0.1（vc=1170 / vcname=6.49.0）。
 * 服务端升级时必须整体更新本文件。
 */
object ProtocolProfile {

    // ---- 设备签名材料 ----
    const val CERTIFICATE_DIGEST = "2fb53de6d38eff7109f19d68e047123b"

    /** signA 的 DES 密钥。 */
    const val SIGN_A_KEY = "@fG2SuLA"

    /** signB 的 DES 密钥后缀：random10.substring(0, 5) + 该后缀。 */
    const val SIGN_B_KEY_SUFFIX = "#G4"

    /** signA 明文前缀。 */
    const val SIGN_A_PLAIN_PREFIX = "8&%d*##"

    /** responseKey 派生时使用的固定盐。 */
    const val RC4_KEY_SALT = "@#AIjd83#@6B"

    /** signA 明文中 random10 的字符集。 */
    const val RANDOM10_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    const val RANDOM10_LENGTH = 10

    /** 期望的 deviceSecret 长度。 */
    const val DEVICE_SECRET_LENGTH = 10

    /** 期望的 responseKey 长度（小写十六进制字符数）。 */
    const val RESPONSE_KEY_LENGTH = 128

    // ---- 公共参数（设备与协议身份）----
    const val CHANNEL = "vivo"
    const val TOKEN = "1_XPXQH3c5HRPtFHkSwi3sCCURmT25QfxM"
    const val VC = "1170"
    const val VC_NAME = "6.49.0"
    const val OS = "android"
    const val OPERATOR_ID = "0"
    const val PKG_NAME = "com.kuaiduizuoye.scan"
    const val APP_ID = "scancode"
    const val IS_PAD = "0"
    const val DIG_GRADE = "0"

    /** 请求签名前缀。 */
    const val SIGN_PREFIX = "8&%d*["

    // ---- 域名 ----
    const val HOST_KDDZY = "https://www.kuaiduizuoye.com"
    const val HOST_PASSPORT = "https://passport.kuaiduizuoye.com"
    const val HOST_RESOURCE = "https://resourceserver.zybang.com"

    /** 官方安全验证页所在主机。 */
    const val HOST_VERIFY = "https://paisou.zuoyebang.com"

    /** 允许发起 API 请求的主机白名单。 */
    val API_HOSTS = setOf(HOST_KDDZY, HOST_PASSPORT, HOST_RESOURCE)

    /** 需要附加 cuid/KDUSS Cookie 的主机。 */
    val COOKIE_HOSTS = setOf(HOST_KDDZY, HOST_RESOURCE)

    // ---- 端点 ----
    const val PATH_ANTISPAM = "/napi/user/antispam"
    const val PATH_SMS_SEND = "/session/submit/tokengettokenv2"
    const val PATH_SMS_LOGIN = "/session/submit/tokenloginv2"
    const val PATH_PASSWORD_LOGIN = "/session/submit/loginv2"
    const val PATH_USER_INFO = "/kdcore/user/userinfov3"
    const val PATH_SEARCH = "/picsearch/submit/singlesearch"
    const val PATH_CHECK_IDENTITY = "/resourceserver/checkidentity"
    const val PATH_VERIFICATION =
        "/static/hy/fe-paisou-vue/anti-grabbing-verification.html"

    // ---- HTTP 行为 ----
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val ANTISPAM_READ_TIMEOUT_MS = 20_000
    const val USER_AGENT = "WatchSearch/1.0 Android"
    const val FORM_CONTENT_TYPE =
        "application/x-www-form-urlencoded; charset=UTF-8"
    const val MULTIPART_BOUNDARY_PREFIX = "WatchSearch"

    /** 通用响应上限。 */
    const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    /** antispam 响应上限。 */
    const val MAX_ANTISPAM_RESPONSE_BYTES = 1024 * 1024

    /** 验证页 HTML 上限。 */
    const val MAX_VERIFICATION_HTML_BYTES = 2 * 1024 * 1024

    /** 验证页抓取的 read timeout。 */
    const val VERIFICATION_READ_TIMEOUT_MS = 20_000

    /** 答案解压后上限。 */
    const val MAX_ANSWER_BYTES = 8 * 1024 * 1024

    // ---- 图片 ----
    const val IMAGE_MAX_INPUT_BYTES = 24 * 1024 * 1024
    const val IMAGE_DECODE_MAX_EDGE = 2048
    const val IMAGE_OUTPUT_MAX_EDGE = 1600
    const val IMAGE_JPEG_QUALITY = 88

    /** 允许从 http 升级到 https 的图片域名。 */
    const val IMAGE_HTTPS_HOST_REGEX =
        "(?:img[0-9]*|testimg)\\.zuoyebang\\.cc"

    // ---- 答案解码 ----
    const val ANSWER_PLAINTEXT_JSON_PREFIX = "{"
    const val ANSWER_PLAINTEXT_HTML_PREFIX = "<!doctype html"
    const val ANSWER_PLAINTEXT_HTML2_PREFIX = "<html"
}
