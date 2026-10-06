package com.heikeji.phonesearch.protocol.probe

import com.google.gson.JsonParser
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.protocol.crypto.Digests
import com.heikeji.phonesearch.protocol.crypto.ResponseKey
import com.heikeji.phonesearch.protocol.decode.AnswerDecoder
import com.heikeji.phonesearch.protocol.sign.RequestSigner
import com.heikeji.phonesearch.protocol.sign.SignA
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 内容门定位 · 官方材料重放。
 *
 * 用抓包里官方客户端的全套材料（cuid/signA/signB 与 Dp-Ticket/adid/did）重放搜题，
 * 逐个关掉可疑字段，解密 mainPageInfo 看哪种组合能拿到真答案。
 *
 * 场景：
 * - R1 完整官方：Dp-Ticket + adid + did
 * - R2 无 Dp-Ticket：adid + did
 * - R3 无 adid：Dp-Ticket + did
 * - R4 仅 did
 *
 * Dp-Ticket 从 build/replay-dp-ticket.txt 读取（抓包值，可能有时效）。
 */
class ReplayProbeTest {

    private val host = ProtocolProfile.HOST_KDDZY

    // 官方客户端的材料（用户设备抓包）
    private val cuid = "960A6AAFD2BF38F81789216A4D3EE1EA|0"
    private val signA =
        "0d0004050f050701040e070b050601020b08060d0309010c070a05020f09090f0102090e00000e0f0b0800" +
            "0f0b030b00070f0f080306060b00000f0d04070d0d02070b0f020a01010c0d030a09080e090b040f02080c0b040c0e0e060c0d" +
            "020109090e0e030d0f0709060d0c0e070402010b0d03080c090e090e0a0f0a0e060c07070d040d080d0f0c0f0f080b0f0403050a" +
            "07060c04070c03040e02050e0b0e0a040e0c030b090e0d0202050e070709"
    private val signB = "0a090d04030d0c05050f020d0a080e0208070d0f000b0c080b080d040b0201050a0a040900080609020d0103000f0504"
    private val adid = "f8511a64916fa48147fa809ebf3b6efe91ff5085"
    private val did = "04fd32b282000002844d0200400000ed"
    private val ua =
        "Mozilla/5.0 (Linux; Android 16; 25067PYE3C Build/BP2A.250605.031.A3; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36"

    @Test
    fun `replay official request to find the content gate`() {
        assumeTrue("需要 -DcontentProbe3=1 才运行", System.getProperty("contentProbe3") == "1")

        val dpTicket = File("build/replay-dp-ticket.txt").readText().trim()
        println("Dp-Ticket 长度 ${dpTicket.length}")

        val secret = SignA.parseDeviceSecret(cuid, signA, signB)
        val digest = Digests.md5Lower(secret)
        val responseKey = ResponseKey.derive(secret)
        println("签名材料就绪，responseKey 长度=${responseKey.length}")

        val jpeg = drawQuestion("2 + 2 = ?")
        println("测试图 ${jpeg.size} 字节")

        val scenarios = listOf(
            Triple("R1 完整官方(Dp+adid+did)", dpTicket, adid),
            Triple("R2 无Dp(adid+did)", "", adid),
            Triple("R3 无adid(Dp+did)", dpTicket, ""),
            Triple("R4 仅did", "", ""),
        )
        for ((name, dp, ad) in scenarios) {
            println("\n========== $name ==========")
            runScenario(name, dp, ad, digest, responseKey, jpeg)
        }
    }

    private fun runScenario(
        name: String,
        dpTicket: String,
        scenarioAdid: String,
        digest: String,
        responseKey: String,
        jpeg: ByteArray,
    ) {
        val params = linkedMapOf(
            "picMD5" to Digests.md5Upper(jpeg),
            "shumei" to "",
            "ref" to "0",
            "referer" to "",
            "isStudentMode" to "1",
            "grade" to "6",
            "from" to "otherPage",
            "imgCorrection" to "0",
            "abtest" to "{}",
            "city" to "",
            "channel" to "xiaomi",
            "appBit" to "64",
            "occupationType" to "0",
            "phoneDevice" to "luming",
            "adid" to scenarioAdid,
            "province" to "",
            "osVersion" to "16",
            "pkgName" to ProtocolProfile.PKG_NAME,
            "appId" to "scancode",
            "brand" to "Xiaomi",
            "identityIdV2" to "1",
            "area" to "",
            "cuid" to cuid,
            "os" to "android",
            "abis" to "1",
            "vc" to "1810",
            "token" to ProtocolProfile.TOKEN,
            "digGrade" to "6",
            "isPad" to "0",
            "vcname" to "7.7.0",
            "sdk" to "36",
            "device" to "25067PYE3C",
            "operatorid" to "46000",
            "did" to did,
            "nt" to "mobile",
        )
        val merged = LinkedHashMap<String, String?>(params)
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

        val boundary = ProtocolProfile.MULTIPART_BOUNDARY_PREFIX +
            UUID.randomUUID().toString().replace("-", "")
        val body = multipart(boundary, jpeg, merged)

        val connection = open("$host${ProtocolProfile.PATH_PAGE_SEARCH}")
        val resp = try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty(
                "Content-Type",
                "multipart/form-data; boundary=$boundary",
            )
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
            if (dpTicket.isNotEmpty()) {
                connection.setRequestProperty("Dp-Ticket", dpTicket)
            }
            connection.setRequestProperty("zyb-cuid", cuid)
            connection.setRequestProperty("zyb-did", did)
            if (scenarioAdid.isNotEmpty()) {
                connection.setRequestProperty("zyb-adid", scenarioAdid)
            }
            connection.setRequestProperty("na__kf_source__", "scancode")
            connection.setRequestProperty(
                "X-Zyb-Trace-Id",
                "aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb:0:1",
            )
            connection.setRequestProperty("X-Zyb-Trace-T", System.currentTimeMillis().toString())
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            println("HTTP ${connection.responseCode}，响应 ${text.length} 字符")
            if (text.length < 400) println("响应原文：$text")
            text
        } finally {
            connection.disconnect()
        }

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
        val decoded = try {
            AnswerDecoder.decode(raw, tid, encode, encryption, gzip, responseKey)
        } catch (e: Exception) {
            println("解密失败：${e.message}")
            return
        }
        val head = decoded.take(160).replace("\n", "⏎")
        println("解密成功（${decoded.length} 字符）：$head")
        when {
            decoded.contains("登录提醒") || decoded.contains("个人中心") ->
                println(">> 仍是登录提醒占位 —— 这个场景缺的字段就是关键")
            else -> println(">> ★★★ 疑似真内容！")
        }
        File("build/replay-${name.take(2)}.json").writeText(decoded)
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

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
        }
}
