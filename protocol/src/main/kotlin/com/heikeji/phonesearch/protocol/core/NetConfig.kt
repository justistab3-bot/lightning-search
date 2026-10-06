package com.heikeji.phonesearch.protocol.core

/**
 * 主机与协议常量，对应官方 `com.baidu.homework.base.NetConfig`。
 *
 * 官方按 `__pid` 选主机（`getHost(pid)`），默认主机承载业务接口，
 * `resource` 指向资源/设备身份主机。端点路径不在本类 —— 按官方标准
 * 各接口的 `Input.URL` 常量自带（见 `search/PicSingleSearch` 等）。
 *
 * 常量说明：签名材料与公共参数是**版本绑定**值，来自官方快对作业 7.7.0
 * （vc=1810）抓包与反编译对齐；服务端升级时必须整体更新。
 */
object NetConfig {

    // ---- 主机（官方 getHost(pid)）----

    /** 默认主机选择器（官方 `__pid = ""`）。 */
    const val PID_DEFAULT = ""

    /** 资源主机选择器（官方 Getdid 等设备身份接口的 `__pid`）。 */
    const val PID_RESOURCE = "resource"

    /** 登录主机选择器（短信验证码等登录类接口）。 */
    const val PID_PASSPORT = "passport"

    const val HOST_KDDZY = "https://www.kuaiduizuoye.com"
    const val HOST_PASSPORT = "https://passport.kuaiduizuoye.com"
    const val HOST_RESOURCE = "https://resourceserver.zybang.com"

    /** AI 作文走的是独立域名 api.kuaiduizuoye.com。 */
    const val HOST_API_KDDZY = "https://api.kuaiduizuoye.com"

    /** 官方安全验证页所在主机。 */
    const val HOST_VERIFY = "https://paisou.zuoyebang.com"

    private val HOST_BY_PID: Map<String, String> = mapOf(
        PID_DEFAULT to HOST_KDDZY,
        PID_RESOURCE to HOST_RESOURCE,
        PID_PASSPORT to HOST_PASSPORT,
    )

    /** 官方 `getHost(pid)`：按接口声明的主机选择器取主机。 */
    fun hostForPid(pid: String): String = HOST_BY_PID[pid] ?: HOST_KDDZY

    /** 官方 `getHost()`：默认业务主机。 */
    fun host(): String = HOST_KDDZY

    /** 允许发起 API 请求的主机白名单。 */
    val API_HOSTS = setOf(HOST_KDDZY, HOST_PASSPORT, HOST_RESOURCE, HOST_API_KDDZY)

    /** 需要附加 cuid/KDUSS Cookie 的主机。 */
    val COOKIE_HOSTS = setOf(HOST_KDDZY, HOST_RESOURCE)

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
    //
    // 手机版模拟（官方 7.7.0 抓包对齐）：channel/版本号/运营商/学段均按官方手机客户端。
    // vc 同时是答案密钥的派生入参（nativeGetKey(vc)），**必须**和发送的 vc 一致。
    const val CHANNEL = "xiaomi"
    const val TOKEN = "1_XPXQH3c5HRPtFHkSwi3sCCURmT25QfxM"
    const val VC = "1810"
    const val VC_NAME = "7.7.0"

    /**
     * 原生密钥不可用时的整体回退（老手表版参数 + 老 Java 密钥推导）。
     * 服务器按版本号加密答案内容，参数与密钥必须成对切换，不能混搭。
     */
    const val VC_LEGACY = "1170"
    const val VC_NAME_LEGACY = "6.49.0"
    const val OS = "android"
    const val OPERATOR_ID = "46000"
    const val PKG_NAME = "com.kuaiduizuoye.scan"
    const val APP_ID = "scancode"
    const val IS_PAD = "0"

    /** 默认学段；官方会在选年级后经 getdiggrade 回填真实值。 */
    const val DIG_GRADE = "6"

    /** 请求签名前缀。 */
    const val SIGN_PREFIX = "8&%d*["

    // ---- 端点 ----
    const val PATH_ANTISPAM = "/napi/user/antispam"
    const val PATH_SMS_SEND = "/session/submit/tokengettokenv2"
    const val PATH_SMS_LOGIN = "/session/submit/tokenloginv2"
    const val PATH_PASSWORD_LOGIN = "/session/submit/loginv2"
    const val PATH_USER_INFO = "/kdcore/user/userinfov3"
    const val PATH_SEARCH = "/picsearch/submit/singlesearch"

    /** 整页搜题。 */
    const val PATH_PAGE_SEARCH = "/picsearch/submit/pagesearch"
    const val PATH_CHECK_IDENTITY = "/resourceserver/checkidentity"

    /** 学段上报（官方 7.7.0 搜题前置）。 */
    const val PATH_DIG_GRADE = "/kdapi/device/getdiggrade"

    /** 设备 ID 上报（官方 DeviceIdHelper，did 由服务器下发）。 */
    const val PATH_GETDID = "/userident/user/getdid"

    const val PATH_VERIFICATION =
        "/static/hy/fe-paisou-vue/anti-grabbing-verification.html"

    // ---- 搜题业务参数（官方 7.7.0 抓包对齐）----
    /** 普通单题首次搜索。 */
    const val SEARCH_REFERER_SINGLE = "1"

    /** 由整页题块框选触发的精搜。 */
    const val SEARCH_REFERER_CROP = "3"

    /** 整页搜题：官方 7.7.0 传空串。 */
    const val SEARCH_REFERER_PAGE = ""
    const val SEARCH_SHUMEI = ""
    const val SEARCH_REF = "0"
    const val SEARCH_IMG_CORRECTION = "0"
    const val SEARCH_IS_STUDENT_MODE = "1"
    const val SEARCH_FROM = "otherPage"

    /** 官方 7.7.0 的搜题参数里带有空的 abtest 占位。 */
    const val SEARCH_ABTEST = "{}"

    // ---- HTTP 行为 ----
    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
    const val ANTISPAM_READ_TIMEOUT_MS = 20_000
    const val USER_AGENT = "WatchSearch/1.0 Android"

    /**
     * AI 作文走的是 H5 接口，用普通浏览器 UA。
     *
     * 实测这个头不影响结果（空值会失败），没必要伪装成 WebView。
     */
    const val AI_WRITING_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
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

    /** 普通单题：最长边 1600 / 质量 88。 */
    const val IMAGE_OUTPUT_MAX_EDGE = 1600
    const val IMAGE_JPEG_QUALITY = 88

    /** 整页搜题：保留更多分辨率，便于服务端返回可靠题框。 */
    const val IMAGE_PAGE_OUTPUT_MAX_EDGE = 2400
    const val IMAGE_PAGE_JPEG_QUALITY = 92

    /** 框选裁剪后精搜。 */
    const val IMAGE_CROP_OUTPUT_MAX_EDGE = 1600
    const val IMAGE_CROP_JPEG_QUALITY = 92

    /** 原图预览最长边。 */
    const val IMAGE_PREVIEW_MAX_EDGE = 900

    // ---- 整页题框 ----
    const val LOC_SEPARATOR = "@"

    /** `locs[i]` 必须是 8 个坐标。 */
    const val LOC_POINT_COUNT = 8

    /** 图片宽高与坐标的合法上界。 */
    const val LOC_MAX_COORDINATE = 100_000

    /** `angles[i]` 的闭区间。 */
    const val QUAD_ANGLE_MIN = -360
    const val QUAD_ANGLE_MAX = 360

    /** 没有服务端题框时，框选的默认归一化区域（面向用户所见图片）。 */
    val QUAD_DEFAULT_RECT = floatArrayOf(0.08f, 0.25f, 0.92f, 0.75f)

    /** 允许从 http 升级到 https 的图片域名。 */
    const val IMAGE_HTTPS_HOST_REGEX =
        "(?:img[0-9]*|testimg)\\.zuoyebang\\.cc"

    // ---- 答案解码 ----
    const val ANSWER_PLAINTEXT_JSON_PREFIX = "{"

    /**
     * 整页响应里 `mainPageInfo[i]` 解码后可能是候选答案数组。
     * Base64 字母表不含 `[`，所以把它当明文标记是安全的。
     */
    const val ANSWER_PLAINTEXT_JSON_ARRAY_PREFIX = "["
    const val ANSWER_PLAINTEXT_HTML_PREFIX = "<!doctype html"
    const val ANSWER_PLAINTEXT_HTML2_PREFIX = "<html"
}
