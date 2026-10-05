package com.heikeji.phonesearch.protocol.probe

import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.aiwriting.SseParser
import com.heikeji.phonesearch.protocol.chat.ChatEventParser
import com.heikeji.phonesearch.protocol.chat.model.ChatEvent
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.protocol.crypto.Digests
import com.heikeji.phonesearch.protocol.crypto.ResponseKey
import com.heikeji.phonesearch.protocol.envelope.Envelope
import com.heikeji.phonesearch.protocol.sign.RequestSigner
import com.heikeji.phonesearch.protocol.sign.SignA
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPInputStream

/**
 * 抓不到 SSE 响应体时的**自救探针**。
 *
 * 背景：ProxyPin 对 `text/event-stream` 不缓冲 body，脚本规则也不在 SSE 上执行，
 * 所以 `/kdchat/api/ask`、`/aiwriting/.../instantWriting` 这类接口的原始帧一直拿不到。
 *
 * 这里在 JVM 上复现整条签名链路（`:protocol` 里的加密全是纯 JVM 的），
 * 自己造一个干净身份，直接发请求读流，把原始帧打出来。
 *
 * **默认跳过**，只在显式指定时运行：
 * ```
 * .\gradlew.bat :protocol:test --tests "*ChatProbeTest*" -DchatProbe=1 -i
 * ```
 * 注意：需要联网，且用的是全新 cuid（不影响任何已登录设备）。
 */
class ChatProbeTest {

    private val host = ProtocolProfile.HOST_KDDZY

    @Test
    fun `probe kdchat ask raw sse frames`() {
        assumeTrue("需要 -DchatProbe=1 才运行", System.getProperty("chatProbe") == "1")

        val cuid = UUID.randomUUID().toString().replace("-", "").uppercase() + "|0"
        println("=== 探针身份 cuid=$cuid ===")

        // 1. 自造签名材料
        val signA = SignA.build(cuid, SignA.random10())
        val signB = bootstrap(cuid, signA)
        println("signB 长度=${signB.length}")

        val deviceSecret = SignA.parseDeviceSecret(cuid, signA, signB)
        val digest = Digests.md5Lower(deviceSecret)
        val responseKey = ResponseKey.derive(deviceSecret)
        println("deviceSecret 长度=${deviceSecret.length}  responseKey 长度=${responseKey.length}")

        // 2. 建会话
        val sessionBody = post(
            path = "/kdchat/api/create",
            params = common(cuid) + mapOf(
                "appId" to "scancode",
                "grade" to "6",
                "scene" to "",
                "from" to "",
                "feVc" to "211",
            ),
            cuid = cuid,
            digest = digest,
        )
        println("=== /kdchat/api/create ===")
        println(sessionBody)

        val sessionId = Regex("\"sessionId\"\\s*:\\s*\"?(\\d+)")
            .find(sessionBody)?.groupValues?.get(1)
        println("sessionId=$sessionId")
        if (sessionId == null) {
            println("!! 没拿到 sessionId，后面没法继续")
            return
        }

        // 3. 提问，读 SSE
        println("\n=== /kdchat/api/ask 原始帧 ===")
        val frames = ask(
            cuid = cuid,
            digest = digest,
            params = common(cuid) + mapOf(
                "subjectId" to "",
                "sid" to "",
                "agentId" to "",
                "searchEnabled" to "0",
                "thinkEnabled" to "1",
                "isSugContent" to "0",
                "sugType" to "0",
                "grade" to "6",
                "content" to "你好",
                "feVc" to "211",
                "toolType" to "normal",
                "sessionId" to sessionId,
                "isHitQueryRewrite" to "1",
                "inputType" to "1",
                "referInfo" to "",
                "from" to "home",
                "scene" to "",
                "isKeyPointContent" to "0",
                "context" to "[]",
            ),
        )
        println("共收到 ${frames.size} 帧")
        frames.forEachIndexed { i, f -> println("[$i] $f") }
        java.io.File("build/chat-probe-frames.txt").writeText(frames.joinToString("\n"))

        assertTrue("应当至少收到一帧", frames.isNotEmpty())
    }

    /**
     * 多轮上下文验证。
     *
     * `context` 的拼法是从 H5 代码推的（只带已回答过的用户提问），没实测过。
     * 这里先告诉它一个名字，再问「我叫什么」——答对就说明上下文生效。
     */
    @Test
    fun `probe multi-turn context`() {
        assumeTrue("需要 -DchatProbe=1 才运行", System.getProperty("chatProbe") == "1")

        val cuid = UUID.randomUUID().toString().replace("-", "").uppercase() + "|0"
        val signA = SignA.build(cuid, SignA.random10())
        val signB = bootstrap(cuid, signA)
        val digest = Digests.md5Lower(SignA.parseDeviceSecret(cuid, signA, signB))

        val sessionId = Regex("\"sessionId\"\\s*:\\s*\"?(\\d+)")
            .find(post("/kdchat/api/create", common(cuid) + mapOf("appId" to "scancode", "grade" to "6"), cuid, digest))
            ?.groupValues?.get(1)
        println("=== 多轮测试 sessionId=$sessionId ===")
        if (sessionId == null) return

        val base = common(cuid) + mapOf(
            "subjectId" to "", "sid" to "", "agentId" to "",
            "searchEnabled" to "0", "thinkEnabled" to "0", "isSugContent" to "0", "sugType" to "0",
            "grade" to "6", "feVc" to "211", "toolType" to "normal", "sessionId" to sessionId,
            "isHitQueryRewrite" to "1", "inputType" to "1", "referInfo" to "", "from" to "home",
            "scene" to "", "isKeyPointContent" to "0",
        )

        // 第一轮：告知名字
        val first = ask(cuid, digest, base + mapOf("content" to "我叫小明，请记住", "context" to "[]"))
        println("第一轮回答：${textOf(first)}")
        println("--- 第一轮原始帧（前 14 行）---")
        first.take(14).forEach { println("  $it") }
        println("--- 第一轮 event: 分布 ---")
        first.filter { it.startsWith("event:") }.groupingBy { it }.eachCount().forEach { (k, v) -> println("  $k x$v") }
        println("--- 第一轮 source 分布 ---")
        first.filter { it.startsWith("data:") }.groupingBy { Regex("\"source\":\"([^\"]*)\"").find(it)?.groupValues?.get(1) ?: "?" }
            .eachCount().forEach { (k, v) -> println("  $k x$v") }

        // 第二轮：带上一轮的问题做 context
        val contextJson = """[{"toolType":"normal","role":"user","content":"我叫小明，请记住","time":${System.currentTimeMillis() / 1000},"intent":[],"isCard":"0"}]"""
        val second = ask(cuid, digest, base + mapOf("content" to "我叫什么名字？", "context" to contextJson))
        val secondText = textOf(second)
        println("第二轮回答：$secondText")
        println(if (secondText.contains("小明")) ">>> 上下文生效 ✅" else ">>> 上下文可能没生效 ⚠️")
    }

    /** 用**真正的解析器**跑一遍，顺便端到端验证实现。 */
    private fun textOf(frames: List<String>): String {
        val parser = SseParser()
        val out = StringBuilder()
        fun absorb(event: com.heikeji.phonesearch.protocol.aiwriting.SseEvent?) {
            if (event == null) return
            for (parsed in ChatEventParser.parse(event)) {
                when (parsed) {
                    is ChatEvent.Delta -> out.append(parsed.text)
                    is ChatEvent.Command -> out.append(parsed.text)
                    else -> Unit
                }
            }
        }
        for (line in frames) absorb(parser.feed(line))
        absorb(parser.finish())
        return out.toString()
    }

    // ------------------------------------------------------------------ 内部

    /** 公共参数。照抄抓包里的字段名，值用本应用自己的。 */
    private fun common(cuid: String): Map<String, String> = linkedMapOf(
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
        "identityIdV2" to "0",
        "area" to "",
        "cuid" to cuid,
        "os" to ProtocolProfile.OS,
        "abis" to "1",
        "vc" to ProtocolProfile.VC,
        "token" to ProtocolProfile.TOKEN,
        "digGrade" to "6",
        "isPad" to ProtocolProfile.IS_PAD,
        "vcname" to ProtocolProfile.VC_NAME,
        "sdk" to "36",
        "device" to "luming",
        "operatorid" to ProtocolProfile.OPERATOR_ID,
        "did" to "",
        "nt" to "mobile",
    )

    /** antispam 初始化：`data=signA` 打头，**不签名**、不带 Cookie。 */
    private fun bootstrap(cuid: String, signA: String): String {
        val params = LinkedHashMap<String, String?>()
        params["data"] = signA
        for ((k, v) in common(cuid)) if (!params.containsKey(k)) params[k] = v

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

            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            val text = readAll(stream, connection.contentEncoding)
            println("bootstrap HTTP ${connection.responseCode} ${connection.contentType}")
            println("bootstrap 原文（前 600 字）：")
            println(text.take(600))
            Envelope.extractInnerData(text)
        } finally {
            connection.disconnect()
        }
    }

    /** 普通 POST：合并公共参数 -> 签名 -> 发送。 */
    private fun post(
        path: String,
        params: Map<String, String>,
        cuid: String,
        digest: String,
    ): String {
        val merged = LinkedHashMap<String, String?>()
        merged.putAll(params)
        val tSeconds = System.currentTimeMillis() / 1000
        val uptime = 7715998L
        val sign = RequestSigner.sign(
            items = RequestSigner.toItems(merged),
            deviceSecretDigest = digest,
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        merged["sign"] = sign
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

            val stream = if (connection.responseCode >= 400) {
                connection.errorStream
            } else {
                connection.inputStream
            }
            readAll(stream, connection.contentEncoding)
        } finally {
            connection.disconnect()
        }
    }

    /** 读 SSE，把每一行原样收集起来。 */
    private fun ask(
        cuid: String,
        digest: String,
        params: Map<String, String>,
    ): List<String> {
        val merged = LinkedHashMap<String, String?>()
        merged.putAll(params)
        val tSeconds = System.currentTimeMillis() / 1000
        val uptime = 7715998L
        val sign = RequestSigner.sign(
            items = RequestSigner.toItems(merged),
            deviceSecretDigest = digest,
            tSeconds = tSeconds,
            uptimeMillis = uptime,
        )
        merged["sign"] = sign
        merged["_t_"] = tSeconds.toString()
        merged["kakorrhaphiophobia"] = uptime.toString()

        val connection = open("$host/kdchat/api/ask")
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            // SSE 是长连接，用单次读超时
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Content-Type", ProtocolProfile.FORM_CONTENT_TYPE)
            connection.setRequestProperty("User-Agent", ProtocolProfile.USER_AGENT)
            connection.setRequestProperty("Cookie", "cuid=" + UrlForm.encode(cuid))
            val bytes = UrlForm.encodeForm(merged).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            println("HTTP ${connection.responseCode}  ${connection.contentType}")
            val frames = ArrayList<String>()
            val reader: BufferedReader = connection.inputStream.bufferedReader()
            var emptyRun = 0
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    println("读取中断：${e.javaClass.simpleName} ${e.message}")
                    break
                } ?: break
                // 空行必须保留：SSE 靠它触发事件派发
                frames.add(line)
                if (line.isEmpty()) {
                    if (++emptyRun > 200) break
                } else {
                    emptyRun = 0
                }
                if (frames.size > 2000) break
            }
            frames
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        return connection
    }

    private fun readAll(stream: java.io.InputStream?, encoding: String?): String {
        if (stream == null) return ""
        val input = if ("gzip".equals(encoding, ignoreCase = true)) GZIPInputStream(stream) else stream
        return input.bufferedReader().use { it.readText() }
    }
}
