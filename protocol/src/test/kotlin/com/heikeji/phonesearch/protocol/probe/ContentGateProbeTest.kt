package com.heikeji.phonesearch.protocol.probe

import com.google.gson.JsonParser
import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.protocol.core.codec.Base64NoWrap
import com.heikeji.phonesearch.protocol.core.codec.UrlForm
import com.heikeji.phonesearch.protocol.core.crypto.Digests
import com.heikeji.phonesearch.protocol.core.crypto.Rc4
import com.heikeji.phonesearch.protocol.core.crypto.ResponseKey
import com.heikeji.phonesearch.protocol.search.decode.AnswerDecoder
import com.heikeji.phonesearch.protocol.core.envelope.Envelope
import com.heikeji.phonesearch.protocol.core.sign.RequestSigner
import com.heikeji.phonesearch.protocol.core.sign.SignA
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 答案内容门探针。
 *
 * 背景（抓包定位）：搜题接口对匿名用户是通的（errNo:0、科目识别正确、answers.count>0），
 * 但 `mainPageInfo` 解密后不是真答案，而是一段【登录提醒】占位文字。
 * 官方客户端**不登录**却能拿到真答案。
 *
 * 逐字段对比官方与我们的请求后，差异集中在：
 * - `identityIdV2`：官方 1，我们 0（官方在 antispam 之后、getdiggrade 之前就置 1）
 * - `digGrade`：官方 6，我们 0 —— 官方先调 `/kdapi/device/getdiggrade`（grade=6）拿到
 * - `ref=0` / `referer=""` / `from=otherPage` / `abtest={}`：搜索专属参数
 * - `channel=xiaomi` / `vc=1810` / `vcname=7.7.0`：应用版本
 * - `Dp-Ticket` / `zyb-did` / `zyb-adid` / `na__kf_source__` 等请求头
 *
 * 本探针在全新 cuid 上复刻官方链路（先 getdiggrade 再按官方参数搜题），
 * 然后**用与 App 相同的 AnswerDecoder 解密 mainPageInfo**，
 * 直接看内容是不是真答案。这是判据，不用猜。
 *
 * 默认跳过，只在显式指定时运行：
 * ```
 * .\gradlew.bat :protocol:test --tests "*ContentGateProbeTest*" -DcontentProbe=1 -i
 * ```
 * 注意：需要联网，使用全新 cuid。
 */
class ContentGateProbeTest {

    private val host = NetConfig.HOST_KDDZY

    @Test
    fun `probe answer content with official params`() {
        assumeTrue("需要 -DcontentProbe=1 才运行", System.getProperty("contentProbe") == "1")

        val cuid = UUID.randomUUID().toString().replace("-", "").uppercase() + "|0"
        println("=== 探针身份 cuid=$cuid（全新，无 KDUSS）===")

        val signA = SignA.build(cuid, SignA.random10())
        val signB = bootstrap(cuid, signA)
        val deviceSecret = SignA.parseDeviceSecret(cuid, signA, signB)
        val digest = Digests.md5Lower(deviceSecret)
        val responseKey = ResponseKey.derive(deviceSecret)
        println("签名链路就绪，responseKey 长度=${responseKey.length}")

        val jpeg = drawQuestion("1 + 1 = ?")
        println("测试图 ${jpeg.size} 字节")

        // ---- 官方第 1 步：上报年级（identityIdV2=1 此时就带上了）----
        println("\n========== /kdapi/device/getdiggrade（官方链路第 1 步）==========")
        val gg = formPost(
            path = "/kdapi/device/getdiggrade",
            params = officialCommon(cuid) + mapOf(
                "grade" to "6",
                "identityIdV2" to "1",
                "digGrade" to "0",
            ),
            cuid = cuid,
            digest = digest,
        )
        println("响应：$gg")

        // ---- 官方第 2 步：按官方参数搜题 ----
        println("\n========== pagesearch（官方参数）==========")
        val resp = multipartSearch(
            cuid = cuid,
            digest = digest,
            jpeg = jpeg,
            path = NetConfig.PATH_PAGE_SEARCH,
            extra = mapOf(
                "picMD5" to Digests.md5Upper(jpeg),
                "shumei" to "",
                "ref" to "0",
                "referer" to "",
                "isStudentMode" to "1",
                "grade" to "6",
                "from" to "otherPage",
                "imgCorrection" to "0",
                "abtest" to "{}",
                "identityIdV2" to "1",
                "digGrade" to "6",
            ),
        )
        println("响应信封（前 800 字）：")
        println(resp.take(800))

        // ---- 解密 mainPageInfo：先存盘，再试多种变体 ----
        val dumpFile = java.io.File("build/content-probe-response.json")
        dumpFile.parentFile?.mkdirs()
        dumpFile.writeText(resp)
        println("\n完整响应已存到 ${dumpFile.absolutePath}（${resp.length} 字符）")

        println("\n========== 解密变体实验 ==========")
        experiment(resp, responseKey)
    }

    /** 用 App 同款 AnswerDecoder 与若干变体解码，看哪种能解出真内容。 */
    private fun experiment(resp: String, responseKey: String) {
        val data = JsonParser.parseString(resp).asJsonObject
            .getAsJsonObject("data") ?: return println("(响应里没有 data)")
        val answers = data.getAsJsonObject("answers") ?: return println("(响应里没有 answers)")
        val mpi = answers.getAsJsonArray("mainPageInfo") ?: return println("(没有 mainPageInfo)")
        val tids = answers.getAsJsonArray("tids")
        val encode = data.get("encode")?.asInt ?: 0
        val encryption = answers.get("encryption")?.asInt ?: 0
        val gzip = answers.get("gzip")?.asInt == 1

        val raw = mpi.get(0).asString
        val tid = if (tids != null && tids.size() > 0) tids.get(0).asString else ""
        println("形态: encode=$encode encryption=$encryption gzip=$gzip 条目数=${mpi.size()}")
        println("tid (${tid.length} 字符): ${tid.take(60)}...")
        println("mainPageInfo[0] (${raw.length} 字符): ${raw.take(60)}...")

        val rk = responseKey

        // 变体 A：App 现在的老方案
        tryVariant("A 老方案(encryption=1)", { AnswerDecoder.decode(raw, tid, encode, encryption, gzip, rk) })

        // 变体 B：tid 不经过 RC4，直接 base64 解码当 tidKey
        tryVariant("B tid 明文当 key", {
            val tidKey = String(Base64NoWrap.decode(tid), StandardCharsets.UTF_8)
            val stage = String(Rc4.apply(Base64NoWrap.decode(raw), tidKey), StandardCharsets.UTF_8)
            AnswerDecoder.decode(stage, "", encode, 0, gzip, rk)
        })

        // 变体 C：mainPageInfo 直接用 responseKey 解（当作 encryption=0）
        tryVariant("C 单层 responseKey", { AnswerDecoder.decode(raw, "", encode, 0, gzip, rk) })

        // 变体 D：两层就停 —— stage 本身是结果
        tryVariant("D 两层即结果", {
            val tidKey = String(Rc4.apply(Base64NoWrap.decode(tid), rk), StandardCharsets.UTF_8)
            String(Rc4.apply(Base64NoWrap.decode(raw), tidKey), StandardCharsets.UTF_8)
        })

        // 变体 E：两层即结果，但 tid 明文当 key
        tryVariant("E tid明文两层即结果", {
            val tidKey = String(Base64NoWrap.decode(tid), StandardCharsets.UTF_8)
            String(Rc4.apply(Base64NoWrap.decode(raw), tidKey), StandardCharsets.UTF_8)
        })

        // 变体 F：raw 直接 base64 解码（可能根本没加密）
        tryVariant("F 直接 base64", {
            String(Base64NoWrap.decode(raw), StandardCharsets.UTF_8)
        })

        // ---- 上一轮 D/E/F 解出了 4258 字符的「乱码」，但那其实是没解压的 gzip ----
        val gunzip = { bytes: ByteArray ->
            val out = ByteArrayOutputStream()
            java.util.zip.GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
            }
            String(out.toByteArray(), StandardCharsets.UTF_8)
        }

        // 变体 G：两层 RC4（tid 用 responseKey 解）→ gunzip
        tryVariant("G 两层RC4+gunzip", {
            val tidKey = String(Rc4.apply(Base64NoWrap.decode(tid), rk), StandardCharsets.UTF_8)
            val stage = Rc4.apply(Base64NoWrap.decode(raw), tidKey)
            gunzip(stage)
        })

        // 变体 H：无加密，base64 直接 → gunzip
        tryVariant("H 裸base64+gunzip", {
            gunzip(Base64NoWrap.decode(raw))
        })

        // 变体 I：两层 RC4（tid 明文当 key）→ gunzip
        tryVariant("I tid明文两层+gunzip", {
            val tidKey = String(Base64NoWrap.decode(tid), StandardCharsets.UTF_8)
            val stage = Rc4.apply(Base64NoWrap.decode(raw), tidKey)
            gunzip(stage)
        })
    }

    /** 打印一种解码变体的结果；抛异常就记下来，不中断其余实验。 */
    private fun tryVariant(name: String, block: () -> String) {
        val result = try {
            block()
        } catch (e: Exception) {
            println("[$name] 失败：${e.message}")
            return
        }
        val head = result.take(220).replace("\n", "⏎")
        println("[$name] 成功（${result.length} 字符）：$head")
        println("    ${if (result.contains("登录提醒") || result.contains("个人中心")) "→ 仍是登录提醒占位" else if (result.contains("<html") || result.contains("答案") || result.contains("{")) "→ 疑似真内容！" else "→ 无法判断"}")
    }

    // ------------------------------------------------------------------ 请求

    /** 官方版本参数（覆盖我们旧的手表版参数）。 */
    private fun officialCommon(cuid: String): Map<String, String> = linkedMapOf(
        "city" to "",
        "channel" to "xiaomi",
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
        "vc" to "1810",
        "token" to NetConfig.TOKEN,
        "digGrade" to "0",
        "isPad" to NetConfig.IS_PAD,
        "vcname" to "7.7.0",
        "sdk" to "36",
        "device" to "luming",
        "operatorid" to "46000",
        "did" to "",
        "nt" to "mobile",
    )

    /** 签名表单 POST。 */
    private fun formPost(
        path: String,
        params: Map<String, String>,
        cuid: String,
        digest: String,
    ): String {
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
            connection.setRequestProperty("Content-Type", NetConfig.FORM_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", NetConfig.USER_AGENT)
            connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
            val bytes = UrlForm.encodeForm(merged).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val text = readAll(stream, connection.contentEncoding)
            println("HTTP ${connection.responseCode}")
            text
        } finally {
            connection.disconnect()
        }
    }

    /** 带图片的签名 multipart POST。 */
    private fun multipartSearch(
        cuid: String,
        digest: String,
        jpeg: ByteArray,
        path: String,
        extra: Map<String, String>,
    ): String {
        val params = LinkedHashMap<String, String?>()
        params.putAll(officialCommon(cuid))
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
            connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val text = readAll(stream, connection.contentEncoding)
            println("HTTP ${connection.responseCode}")
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

    /** antispam 初始化：`data=signA` 打头，不签名、不带 Cookie。 */
    private fun bootstrap(cuid: String, signA: String): String {
        val params = LinkedHashMap<String, String?>()
        params["data"] = signA
        for ((k, v) in officialCommon(cuid)) if (!params.containsKey(k)) params[k] = v

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
