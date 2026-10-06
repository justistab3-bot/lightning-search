package com.heikeji.phonesearch.protocol.search.parse

import com.heikeji.phonesearch.protocol.core.NetConfig
import java.net.URI
import java.util.Locale

/**
 * 图片 pid -> https URL（原 f1.b.Y 中的地址推导部分）。
 *
 * 规则：
 * 1. 以 `//` 开头的协议相对地址补 `http:`，若主机属于 `(?:img[0-9]*|testimg).zuoyebang.cc`
 *    且端口为空或 80，则改写成 `https://` 形式。
 * 2. 既不以 `//` 开头、也不匹配 `(?i)^(https?://|data:).*|.*\.(jpe?g|png|gif|bmp)$` 的裸 pid，
 *    按前缀映射后补 `.jpg`：
 *    - `zyb_`   -> `https://img.zuoyebang.cc/`
 *    - `qa10_`  -> `https://img10.zuoyebang.cc/`
 *    - `qaN_`   -> `https://testimg.zuoyebang.cc/`
 *    - `zybN_`  -> `https://imgN.zuoyebang.cc/`
 *    - 其他     -> `https://img.zuoyebang.cc/tk_`
 *
 * 与原文的一处差异：原实现直接 `URI.create(...)`，遇到非法字符会抛 IllegalArgumentException
 * 并中断整个解析。这里改为容错跳过升级步骤（pid 仍会被正常映射），不把崩溃带进新实现。
 */
object ImagePidResolver {

    private val HTTPS_HOST = Regex(NetConfig.IMAGE_HTTPS_HOST_REGEX)
    private val ABSOLUTE_OR_IMAGE = Regex("(?i)^(https?://|data:).*|.*\\.(jpe?g|png|gif|bmp)$")
    private val QA_NUMBERED = Regex("^qa[0-9]*_.*")
    private val ZYB_NUMBERED = Regex("^zyb[0-9]+_.*")

    /** @return 解析后的地址；pid 为空时返回 null。 */
    fun resolve(pid: String): String? {
        if (pid.isEmpty()) return null
        var value = pid

        val candidate = if (value.startsWith("//")) "http:$value" else value
        upgradeToHttps(candidate)?.let { value = it }

        if (!value.startsWith("//") && !ABSOLUTE_OR_IMAGE.matches(value)) {
            value = prefixFor(value) + value + ".jpg"
        }
        return value
    }

    /** 批量解析 picList，跳过空 pid。 */
    fun resolveAll(pids: List<String>): List<String> = pids.mapNotNull(::resolve)

    private fun prefixFor(pid: String): String = when {
        pid.startsWith("zyb_") -> "https://img.zuoyebang.cc/"
        pid.startsWith("qa10_") -> "https://img10.zuoyebang.cc/"
        QA_NUMBERED.matches(pid) -> "https://testimg.zuoyebang.cc/"
        ZYB_NUMBERED.matches(pid) -> {
            val end = pid.indexOf('_')
            "https://img${pid.substring(3, end)}.zuoyebang.cc/"
        }
        else -> "https://img.zuoyebang.cc/tk_"
    }

    /** http -> https，仅限允许的图片域名且端口为默认。 */
    private fun upgradeToHttps(value: String): String? {
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            return null
        }
        if (!"http".equals(uri.scheme, ignoreCase = true)) return null
        if (uri.userInfo != null) return null
        val host = uri.host ?: return null
        if (uri.port != -1 && uri.port != 80) return null
        val lowerHost = host.lowercase(Locale.ROOT)
        if (!HTTPS_HOST.matches(lowerHost)) return null

        val sb = StringBuilder("https://").append(lowerHost)
        uri.rawPath?.let { sb.append(it) }
        uri.rawQuery?.let { sb.append('?').append(it) }
        uri.rawFragment?.let { sb.append('#').append(it) }
        return sb.toString()
    }
}
