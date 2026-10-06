package com.heikeji.phonesearch.protocol.probe

import com.google.gson.JsonParser
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.protocol.crypto.Digests
import com.heikeji.phonesearch.protocol.crypto.ResponseKey
import com.heikeji.phonesearch.protocol.decode.AnswerDecoder
import com.heikeji.phonesearch.protocol.envelope.Envelope
import com.heikeji.phonesearch.protocol.sign.RequestSigner
import com.heikeji.phonesearch.protocol.sign.SignA
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
 * 答案内容门探针 · 第二轮：逐参数开关实验。
 *
 * 第一轮结论：把官方参数（vc=1810、identityIdV2=1、digGrade=6、ref=0…）一次性全换后，
 * 响应里的答案**用老方案解不开**（可能是版本号把加密方案也切了，也可能是缺 Dp-Ticket）。
 *
 * 这一轮反过来：**保持老版本参数**（vc=1170 等），只逐个打开可疑开关，
 * 用 App 同款 AnswerDecoder 解密。目标是找出「哪个参数能让老方案解出真答案」。
 *
 * 场景：
 * - S1：老参数 + identityIdV2=1 + digGrade=6（先 getdiggrade）—— 最可能的组合
 * - S2：老参数 + 仅 identityIdV2=1
 * - S3：老参数 + 仅 digGrade=6（先 getdiggrade）
 *
 * 判据：解密出的内容里有没有【登录提醒】。
 */
class ContentGateParamProbeTest {

    private val host = ProtocolProfile.HOST_KDDZY

    @Test
    fun `probe which param unlocks real content`() {
        assumeTrue("需要 -DcontentProbe2=1 才运行", System.getProperty("contentProbe2") == "1")

        // S1：identityIdV2=1 + digGrade=6
        runScenario("S1 identityIdV2=1 + digGrade=6", getdiggrade = true, identity = true)

        // S2：仅 identityIdV2=1
        runScenario("S2 仅 identityIdV2=1", getdiggrade = false, identity = true)

        // S3：仅 digGrade=6
        runScenario("S3 仅 digGrade=6", getdiggrade = true, identity = false)
    }

    private fun runScenario(name: String, getdiggrade: Boolean, identity: Boolean) {
        println("\n========== $name ==========")
        val cuid = UUID.randomUUID().toString().replace("-", "").uppercase() + "|0"
        println("cuid=$cuid")

        val signA = SignA.build(cuid, SignA.random10())
        val signB = bootstrap(cuid, signA)
        val deviceSecret = SignA.parseDeviceSecret(cuid, signA, signB)
        val digest = Digests.md5Lower(deviceSecret)
        val responseKey = ResponseKey.derive(deviceSecret)

        if (getdiggrade) {
            val gg = formPost(
                path = "/kdapi/device/getdiggrade",
                params = oldCommon(cuid, identity) + mapOf(
                    "grade" to "6",
                    "identityIdV2" to if (identity) "1" else "0",
                ),
                cuid = cuid,
                digest = digest,
            )
            println("getdiggrade 响应: ${gg.take(120)}")
        }

        val jpeg = drawQuestion("1 + 1 = ?")
        val extra = LinkedHashMap<String, String>()
        extra["picMD5"] = Digests.md5Upper(jpeg)
        extra["shumei"] = ""
        extra["ref"] = "0"
        extra["referer"] = ""
        extra["isStudentMode"] = "1"
        extra["grade"] = "6"
        extra["from"] = "otherPage"
        extra["imgCorrection"] = "0"
        extra["abtest"] = "{}"
        extra["identityIdV2"] = if (identity) "1" else "0"
        extra["digGrade"] = if (getdiggrade) "6" else "0"

        val resp = multipartSearch(cuid, digest, jpeg, ProtocolProfile.PATH_PAGE_SEARCH, extra)
        val dumpFile = java.io.File("build/content-probe-${name.take(2)}.json")
        dumpFile.writeText(resp)
        println("响应已存 ${dumpFile.name}（${resp.length} 字符）")

        decodeAndJudge(resp, responseKey)
    }

    private fun decodeAndJudge(resp: String, responseKey: String) {
        val data = JsonParser.parseString(resp).asJsonObject
            .getAsJsonObject("data") ?: return println("(无 data)")
        val answers = data.getAsJsonObject("answers") ?: return println("(无 answers)")
        val mpi = answers.getAsJsonArray("mainPageInfo") ?: return println("(无 mainPageInfo)")
        val tids = answers.getAsJsonArray("tids")
        val encode = data.get("encode")?.asInt ?: 0
        val encryption = answers.get("encryption")?.asInt ?: 0
        val gzip = answers.get("gzip")?.asInt == 1
        val raw = mpi.get(0).asString
        val tid = if (tids != null && tids.size() > 0) tids.get(0).asString else ""
        println("形态: encode=$encode encryption=$encryption gzip=$gzip 条目数=${mpi.size()}")
        val result = try {
            AnswerDecoder.decode(raw, tid, encode, encryption, gzip, responseKey)
        } catch (e: Exception) {
            println("老方案解密失败：${e.message}")
            return
        }
        println("老方案解密成功（${result.length} 字符）：${result.take(200).replace("\n", "⏎")}")
        when {
            result.contains("登录提醒") || result.contains("个人中心") ->
                println(">> 仍是登录提醒占位 —— 这个开关不是关键")
            result.contains("<html") || result.contains("答案") || result.contains("题目") ->
                println(">> ★★★ 疑似真答案！这个开关有效！")
            else -> println(">> 无法判断，看存盘文件")
        }
    }

    /** 老版本参数（我们的当前值），identity 由参数控制。 */
    private fun oldCommon(cuid: String, identity: Boolean): Map<String, String> = linkedMapOf(
        "city" to "",
        "channel" to ProtocolProfile.CHANNEL,
        "appBit" to "64",
        "occupationType" to "0",
        "phoneDevice" to "luming",
        "adid" to "",
        "province" to "",
        "osVersion" to "16",
        "pkgName" to ProtocolProfile.PKG_NAME,
        "appId" to "scancode",
        "brand" to "Xiaomi",
        "identityIdV2" to if (identity) "1" else "0",
        "area" to "",
        "cuid" to cuid,
        "os" to ProtocolProfile.OS,
        "abis" to "1",
        "vc" to ProtocolProfile.VC,
        "token" to ProtocolProfile.TOKEN,
        "digGrade" to "0",
        "isPad" to ProtocolProfile.IS_PAD,
        "vcname" to ProtocolProfile.VC_NAME,
        "sdk" to "36",
        "device" to "luming",
        "operatorid" to "0",
        "did" to "",
        "nt" to "mobile",
    )

    private fun formPost(path: String, params: Map<String, String>, cuid: String, digest: String): String {
        val merged = LinkedHashMap<String, String?>()
        merged.putAll(params)
        val tSeconds = System.currentTimeMillis() / 1000
        val uptime = 7715998L
        merged["sign"] = RequestSigner.sign(
            items = RequestSigner.toItems(merged),
            deviceSecretDigest = digest,
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        merged["_t_"] = tSeconds.toString()
        merged["kakorrhaphiophobia"] = uptime.toString()
        val connection = open("$host$path")
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Content-Type", ProtocolProfile.FORM_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", ProtocolProfile.USER_AGENT)
            connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
            val bytes = UrlForm.encodeForm(merged).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            val text = readAll(stream, connection.contentEncoding)
            println("HTTP ${connection.responseCode}")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun multipartSearch(
        cuid: String,
        digest: String,
        jpeg: ByteArray,
        path: String,
        extra: Map<String, String>,
    ): String {
        val params = LinkedHashMap<String, String?>()
        params.putAll(oldCommon(cuid, extra["identityIdV2"] == "1"))
        params.putAll(extra)
        val tSeconds = System.currentTimeMillis() / 1000
        val uptime = 7715998L
        params["sign"] = RequestSigner.sign(
            items = RequestSigner.toItems(params),
            deviceSecretDigest = digest,
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        params["_t_"] = tSeconds.toString()
        params["kakorrhaphiophobia"] = uptime.toString()
        val boundary = ProtocolProfile.MULTIPART_BOUNDARY_PREFIX +
            UUID.randomUUID().toString().replace("-", "")
        val body = multipart(boundary, jpeg, params)
        val connection = open("$host$path")
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("User-Agent", ProtocolProfile.USER_AGENT)
            connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            val text = readAll(stream, connection.contentEncoding)
            println("HTTP ${connection.responseCode}")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun multipart(boundary: String, image: ByteArray, params: Map<String, String?>): ByteArray {
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

    private fun bootstrap(cuid: String, signA: String): String {
        val params = LinkedHashMap<String, String?>()
        params["data"] = signA
        for ((k, v) in oldCommon(cuid, false)) if (!params.containsKey(k)) params[k] = v
        val connection = open("$host${ProtocolProfile.PATH_ANTISPAM}")
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("Content-Type", ProtocolProfile.FORM_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", ProtocolProfile.USER_AGENT)
            val bytes = UrlForm.encodeForm(params).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            val text = readAll(stream, connection.contentEncoding)
            println("bootstrap HTTP ${connection.responseCode}")
            Envelope.extractInnerData(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun drawQuestion(text: String): ByteArray {
        val w = 800
        val h = 400
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 96)
        g.drawString(text, 80, 220)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
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
