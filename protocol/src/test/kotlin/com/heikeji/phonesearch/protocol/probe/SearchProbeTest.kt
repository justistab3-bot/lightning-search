package com.heikeji.phonesearch.protocol.probe

import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.core.codec.UrlForm
import com.heikeji.phonesearch.protocol.core.crypto.Digests
import com.heikeji.phonesearch.protocol.core.envelope.Envelope
import com.heikeji.phonesearch.protocol.core.sign.RequestSigner
import com.heikeji.phonesearch.protocol.core.sign.SignA
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 搜题接口的**匿名可用性探针**。
 *
 * 问题：官方 APP 不登录也能拍题搜题，那本应用是不是白加了一道登录墙？
 *
 * 静态分析已经确认两件事：
 * 1. `ApiClient.searchRaw` **没有**会话检查（对比 `checkIdentity` 里明确有
 *    `sessions.current() == null` 就抛异常）；
 * 2. `ApiClient.post` 拼 Cookie 时是 `if (kduss.isNotEmpty())` —— 空就不加，
 *    不会因此失败。
 *
 * 所以唯一的门是 `HomeActivity` 里那句主动跳登录页。**但服务器认不认**只能实测。
 *
 * 这个探针自己造一个干净身份（全新 cuid，不碰任何已登录设备），
 * 走完整签名链路后**不带 KDUSS** 直接发搜题请求，把原始响应打出来。
 * 判据很简单：回的是业务响应（题目/未识别）还是鉴权错误。
 *
 * 默认跳过，只在显式指定时运行：
 * ```
 * .\gradlew.bat :protocol:test --tests "*SearchProbeTest*" -DsearchProbe=1 -i
 * ```
 * 注意：需要联网。
 */
class SearchProbeTest {

    private val host = NetConfig.HOST_KDDZY

    @Test
    fun `probe search without login`() {
        assumeTrue("需要 -DsearchProbe=1 才运行", System.getProperty("searchProbe") == "1")

        val cuid = UUID.randomUUID().toString().replace("-", "").uppercase() + "|0"
        println("=== 探针身份 cuid=$cuid（全新，无 KDUSS）===")

        val signA = SignA.build(cuid, SignA.random10())
        val signB = bootstrap(cuid, signA)
        val deviceSecret = SignA.parseDeviceSecret(cuid, signA, signB)
        val digest = Digests.md5Lower(deviceSecret)
        println("签名链路就绪，deviceSecret 长度=${deviceSecret.length}")

        val jpeg = drawQuestion("1 + 1 = ?")
        println("测试图 ${jpeg.size} 字节, JPEG 头=${jpeg[0].toInt() and 0xFF},${jpeg[1].toInt() and 0xFF}")

        // ---------- 单题搜题，不带 KDUSS ----------
        println("\n========== /picsearch/submit/singlesearch （无 KDUSS）==========")
        val single = search(
            cuid = cuid,
            digest = digest,
            jpeg = jpeg,
            path = NetConfig.PATH_SEARCH,
            extra = linkedMapOf(
                "referer" to NetConfig.SEARCH_REFERER_SINGLE,
                "pageExtraInfo" to "",
            ),
        )
        report(single)

        // ---------- 整页搜题，不带 KDUSS ----------
        println("\n========== /picsearch/submit/pagesearch （无 KDUSS）==========")
        val page = search(
            cuid = cuid,
            digest = digest,
            jpeg = jpeg,
            path = NetConfig.PATH_PAGE_SEARCH,
            extra = linkedMapOf(
                "referer" to NetConfig.SEARCH_REFERER_PAGE,
                "imgCorrection" to NetConfig.SEARCH_IMG_CORRECTION,
            ),
        )
        report(page)

        // ---------- 对照：故意带一个假的 KDUSS ----------
        println("\n========== 对照：带伪造 KDUSS ==========")
        val bogus = search(
            cuid = cuid,
            digest = digest,
            jpeg = jpeg,
            path = NetConfig.PATH_SEARCH,
            extra = linkedMapOf(
                "referer" to NetConfig.SEARCH_REFERER_SINGLE,
                "pageExtraInfo" to "",
            ),
            kduss = "THIS_IS_NOT_A_VALID_SESSION",
        )
        report(bogus)
    }

    /** 打印响应的关键字段，并对「是否要求登录」给出判断。 */
    private fun report(raw: String) {
        println("原始响应（前 900 字）：")
        println(raw.take(900))

        val errNo = Regex("\"errNo\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)
        val errStr = Regex("\"errStr(?:ing)?\"\\s*:\\s*\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
        println("---- 判定 ----")
        println("errNo=$errNo  errStr=$errStr")

        val loginWords = listOf("登录", "登陆", "login", "未授权", "unauthorized", "token", "会话")
        val looksLikeAuth = loginWords.any { raw.contains(it, ignoreCase = true) }
        val hasBusiness = raw.contains("questionId") || raw.contains("sid") ||
            raw.contains("imgCorrection") || raw.contains("wholeSearchSid") ||
            raw.contains("blockList") || raw.contains("noResult") || raw.contains("未识别")

        when {
            looksLikeAuth -> println(">> 疑似**要求登录**")
            hasBusiness -> println(">> 疑似**业务响应**（匿名可用）")
            else -> println(">> 无法判断，看原文")
        }
    }

    // ------------------------------------------------------------------ 请求

    private fun search(
        cuid: String,
        digest: String,
        jpeg: ByteArray,
        path: String,
        extra: Map<String, String>,
        kduss: String? = null,
    ): String {
        val params = LinkedHashMap<String, String?>()
        params.putAll(common(cuid))
        params["picMD5"] = Digests.md5Upper(jpeg)
        params["shumei"] = NetConfig.SEARCH_SHUMEI
        params["ref"] = NetConfig.SEARCH_REF
        params.putAll(extra)
        params["isStudentMode"] = NetConfig.SEARCH_IS_STUDENT_MODE
        params["grade"] = "6"
        params["from"] = NetConfig.SEARCH_FROM
        params["identityIdV2"] = "0"
        params["occupationType"] = "0"

        val tSeconds = System.currentTimeMillis() / 1000
        val uptime = 7715998L
        val sign = RequestSigner.sign(
            items = RequestSigner.toItems(params),
            deviceSecretDigest = digest,
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        params["sign"] = sign
        params["_t_"] = tSeconds.toString()
        params["kakorrhaphiophobia"] = uptime.toString()

        val boundary = NetConfig.MULTIPART_BOUNDARY_PREFIX +
            UUID.randomUUID().toString().replace("-", "")
        val body = multipart(boundary, jpeg, params)

        val connection = open("$host$path")
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary",
            )
            connection.setRequestProperty("User-Agent", NetConfig.USER_AGENT)
            // 关键：Cookie 里**只有 cuid**，没有 KDUSS
            val cookie = buildString {
                append("cuid=").append(UrlForm.encode(cuid))
                if (kduss != null) append("; KDUSS=").append(UrlForm.encode(kduss))
            }
            connection.setRequestProperty("Cookie", cookie)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val text = readAll(stream, connection.contentEncoding)
            println("HTTP ${connection.responseCode}  ${connection.contentType}")
            println("Cookie 发送内容: ${cookie.take(60)}...")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun multipart(
        boundary: String,
        image: ByteArray,
        params: Map<String, String?>,
    ): ByteArray {
        val out = ByteArrayOutputStream(image.size + 8192)
        out.write(
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"image\"; filename=\"image\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        out.write(image)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        for ((key, value) in params) {
            out.write(
                (
                    "--$boundary\r\n" +
                        "Content-Disposition: form-data; name=\"$key\"\r\n" +
                        "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8),
            )
            out.write((value ?: "").toByteArray(Charsets.UTF_8))
            out.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    /** 造一张带题目的白底黑字 JPEG。 */
    private fun drawQuestion(text: String): ByteArray {
        val w = 800
        val h = 400
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
        g.drawString(text, 80, 220)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun common(cuid: String): Map<String, String> = linkedMapOf(
        "city" to "",
        "channel" to NetConfig.CHANNEL,
        "appBit" to "64",
        "occupationType" to "0",
        "phoneDevice" to "luming",
        "adid" to "",
        "province" to "",
        "osVersion" to "16",
        "pkgName" to NetConfig.PKG_NAME,
        "appId" to "scancode",
        "brand" to "Xiaomi",
        "identityIdV2" to "0",
        "area" to "",
        "cuid" to cuid,
        "os" to NetConfig.OS,
        "abis" to "1",
        "vc" to NetConfig.VC,
        "token" to NetConfig.TOKEN,
        "digGrade" to "6",
        "isPad" to NetConfig.IS_PAD,
        "vcname" to NetConfig.VC_NAME,
        "sdk" to "36",
        "device" to "luming",
        "operatorid" to NetConfig.OPERATOR_ID,
        "did" to "",
        "nt" to "mobile",
    )

    /** antispam 初始化：`data=signA` 打头，不签名、不带 Cookie。 */
    private fun bootstrap(cuid: String, signA: String): String {
        val params = LinkedHashMap<String, String?>()
        params["data"] = signA
        for ((k, v) in common(cuid)) if (!params.containsKey(k)) params[k] = v

        val connection = open("$host${NetConfig.PATH_ANTISPAM}")
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Content-Type", NetConfig.FORM_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", NetConfig.USER_AGENT)
            val bytes = UrlForm.encodeForm(params).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val text = readAll(stream, connection.contentEncoding)
            println("bootstrap HTTP ${connection.responseCode}")
            Envelope.extractInnerData(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
        }

    private fun readAll(stream: java.io.InputStream?, encoding: String?): String {
        if (stream == null) return ""
        val source = if (encoding != null && encoding.contains("gzip", ignoreCase = true)) {
            java.util.zip.GZIPInputStream(stream)
        } else {
            stream
        }
        return source.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
