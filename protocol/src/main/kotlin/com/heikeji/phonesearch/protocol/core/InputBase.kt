package com.heikeji.phonesearch.protocol.core

/**
 * 请求描述基类，对应官方 `com.baidu.homework.common.net.model.v1.common.InputBase`。
 *
 * 官方标准：每个接口一个模型类，内嵌一个 `Input`（继承本基类）——
 * `URL` 常量 + `buildInput(...)` 工厂 + `getParams()` 业务参数。
 * 统一执行器 [Net]（本工程为 app 侧 `Net`）读 Input 完成公共参数、签名、
 * 信封与传输，业务侧只关心参数与响应。
 *
 * 与官方字段的对应（`__` 前缀是官方反混淆保留下来的命名）：
 * - [modelClass] ~ `__aClass`（响应反序列化目标类）
 * - [url] ~ `__url`；[pid] ~ `__pid`（主机选择器，见 [NetConfig]）
 * - [method] ~ `__method`（1 = POST）
 * - [jsonBody] ~ `__jsonBody`（JSON 型接口的请求体）
 * - [extraHeaders] ~ `addHeader`/`getExtHeaders`（接口级附加请求头）
 */
abstract class InputBase {

    /** 响应模型类（官方 `__aClass`）。当前工程响应走 org.json 解析，此字段用于契约标注。 */
    abstract val modelClass: Class<*>

    /** 端点路径（官方 `__url`），如 `/picsearch/submit/singlesearch`。 */
    abstract val url: String

    /** 主机选择器（官方 `__pid`）："" = 默认 API 主机，"resource" = 资源主机。 */
    abstract val pid: String

    /** HTTP 方法（官方 `__method`），默认 1 = POST。 */
    open val method: Int = METHOD_POST

    /** JSON 请求体（官方 `__jsonBody`），仅 JSON 型接口使用。 */
    open val jsonBody: String = "{}"

    /** 接口级附加请求头（官方 `addHeader`）。 */
    open val extraHeaders: Map<String, String> = emptyMap()

    /** 业务参数（官方 `getParams`），不含公共参数与签名。 */
    abstract fun params(): Map<String, Any?>

    /** 本接口的完整主机地址（官方 `NetConfig.getHost(__pid)` + URL）。 */
    fun hostUrl(): String = NetConfig.hostForPid(pid) + url

    companion object {
        const val METHOD_POST = 1
        const val METHOD_GET = 0
    }
}
