package com.heikeji.phonesearch.net

import com.heikeji.phonesearch.protocol.core.crypto.Digests
import com.heikeji.phonesearch.protocol.identity.Getdid
import com.heikeji.phonesearch.protocol.identity.KdapiDeviceGetDigGrade
import com.heikeji.phonesearch.protocol.search.PicPageSearch
import com.heikeji.phonesearch.protocol.search.PicSingleSearch
import com.heikeji.phonesearch.protocol.search.model.SearchMode
import org.json.JSONObject

/**
 * 搜题域 API，对应官方 `com.kuaiduizuoye.scan.helper.search`
 * （SingleSearchHelper / PageSearchHelper）+ 官方搜题前置的设备身份链路。
 *
 * 官方标准：请求描述在 `protocol.search.PicSingleSearch/PicPageSearch.Input`、
 * 设备身份在 `protocol.identity.*`，本类只做编排与前置上报。
 */
class SearchApi(
    private val net: Net,
    private val identity: DeviceIdentity,
) {

    /**
     * 发起图片搜题，返回响应 data 对象。
     *
     * 三种模式（原 `O0.l.f686a`）：
     * - [SearchMode.SINGLE]：普通单题搜题（`referer=1`）
     * - [SearchMode.CROP_SINGLE]：整页题块框选精搜（`referer=3` + `pageExtraInfo`）
     * - [SearchMode.PAGE]：整页搜题（`referer=""` + `imgCorrection`）
     *
     * 若 data 含非空 validatedInfo **且没有可用答案**，抛 [SearchChallengeException]，
     * 由上层走官方验证页后重试。
     *
     * 注意 validatedInfo 单独出现时**不算**被拦 —— 服务器会在成功响应里一并下发它，
     * 此时答案已经拿到了，应当正常展示（官方 APP 就是这么做的）。
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

        // 官方标准：业务参数由 PicSingleSearch.Input / PicPageSearch.Input 构建
        // （对应官方 helper.search.SingleSearchHelper/PageSearchHelper + Input.buildInput）。
        val picMd5 = Digests.md5Upper(jpeg)
        val gradeText = grade.toString()
        val input = when (mode) {
            SearchMode.PAGE -> PicPageSearch.Input.buildInput(
                picMd5 = picMd5,
                grade = gradeText,
            )

            SearchMode.CROP_SINGLE -> PicSingleSearch.Input.buildInput(
                picMd5 = picMd5,
                grade = gradeText,
                pageExtraInfo = pageExtraInfo,
                referer = PicSingleSearch.Input.REFERER_CROP,
            )

            SearchMode.SINGLE -> PicSingleSearch.Input.buildInput(
                picMd5 = picMd5,
                grade = gradeText,
            )
        }

        // 官方链路：搜题前先完成设备 ID（getdid）与学段（getdiggrade）上报，
        // 服务器记住后才会给真答案内容。
        ensureDid()
        ensureDigGrade(grade)

        val data = try {
            DiagLog.append("searchRaw 开始 mode=$mode grade=$grade")
            net.post(input, jpeg)
        } catch (e: Exception) {
            DiagLog.append("searchRaw 请求异常：${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        val searchInfo = data.optJSONObject("searchInfo")
        val answersObj = data.optJSONObject("answers")
        DiagLog.append(
            "searchRaw 完成 subject=${searchInfo?.optString("subjectName")} " +
                "count=${answersObj?.optInt("count", -1)} " +
                "validatedInfo=${data.optString("validatedInfo").isNotEmpty()}",
        )
        // validatedInfo 只是「本次命中了风控规则」的**提示**，不代表搜题失败。
        // 只有**确实没有答案**时才算真被拦（判定见 [SearchAnswers]）。
        val validatedInfo = data.optString("validatedInfo", "")
        if (validatedInfo.isNotEmpty() && !hasUsableAnswer(data)) {
            throw SearchChallengeException(validatedInfo, data.optString("sid", ""))
        }
        return data
    }

    /** 响应里有没有可用答案。判定逻辑见 [SearchAnswers.hasUsableAnswer]。 */
    private fun hasUsableAnswer(data: JSONObject): Boolean =
        SearchAnswers.hasUsableAnswer(data)

    // ------------------------------------------------------------------ 设备身份前置（官方搜题链路）

    @Volatile
    private var didAttempted = false

    /**
     * 官方 7.7.0 的设备 ID 链路：上报设备信息（RC4 加密）换服务器下发的 did。
     *
     * 官方标准：参数与端点由 `protocol.identity.Getdid.Input` 声明（官方 Getdid 模型）。
     * did 会进入公共参数与 `zyb-did` 头。只尝试一次，失败静默。
     */
    fun ensureDid() {
        if (identity.did.isNotEmpty() || didAttempted) return
        didAttempted = true
        DiagLog.append("ensureDid 开始")
        try {
            val input = Getdid.Input.buildInput(identity.didPayload())
            val data = net.post(input)
            val newDid = data.optString("did", "")
            DiagLog.append("ensureDid 返回 did=${newDid.length} 字符")
            identity.updateDid(newDid)
        } catch (e: Exception) {
            DiagLog.append("ensureDid 异常：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Volatile
    private var digGradeAttempted = false

    /**
     * 官方 7.7.0 的搜题前置：`/kdapi/device/getdiggrade` 上报学段。
     *
     * 官方标准：参数与端点由 `protocol.identity.KdapiDeviceGetDigGrade.Input` 声明。
     * 响应 `{"data":{"digGrade":6}}` 回填到后续所有请求的公共参数。
     * 只尝试一次；失败静默（保留默认学段，不阻塞搜题）。
     */
    fun ensureDigGrade(grade: Int) {
        if (digGradeAttempted) return
        digGradeAttempted = true
        DiagLog.append("ensureDigGrade 开始 grade=$grade")
        try {
            val input = KdapiDeviceGetDigGrade.Input.buildInput(grade)
            val data = net.post(input)
            val dg = data.optString("digGrade", "")
            DiagLog.append("ensureDigGrade 返回 digGrade=$dg")
            identity.updateDigGrade(dg)
        } catch (e: Exception) {
            DiagLog.append("ensureDigGrade 异常：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

/**
 * 「这次搜题到底有没有拿到答案」的判定。
 *
 * 单独放在对象里是为了能被单元测试直接覆盖 —— 这条规则踩过真实的坑：
 * 服务器在**成功响应**里既返回完整答案、又带上 `validatedInfo`，
 * 而旧逻辑「只要 validatedInfo 非空就当被拦」会把答案扔掉、跳去验证页。
 *
 * 判据是**有没有答案**，不是有没有 validatedInfo。
 */
internal object SearchAnswers {

    /**
     * 单题与整页的载荷结构不同（单题 answers.count + tids；整页还有 locs/locInfo），
     * 但都以 `answers.count > 0` 为准；另外兜一层题块数组。
     */
    fun hasUsableAnswer(data: JSONObject): Boolean {
        val answers = data.optJSONObject("answers")
        if (answers != null && answers.optInt("count", 0) > 0) return true
        val blocks = data.optJSONArray("blocks") ?: data.optJSONArray("blockList")
        if (blocks != null && blocks.length() > 0) return true
        // 整页搜题把结果放在 wholeSearchInfo / pageInfo 里
        val pageInfo = data.optJSONObject("wholeSearchInfo") ?: data.optJSONObject("pageInfo")
        if (pageInfo != null && pageInfo.length() > 0) return true
        return false
    }
}
