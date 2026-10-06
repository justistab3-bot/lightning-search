package com.heikeji.phonesearch.net

import android.content.Context
import com.dprotect.DpSdk
import com.zuoyebang.baseutil.NativeHelper
import java.util.UUID

/**
 * 官方手机客户端原生 SDK 的统一入口（手机版模拟）。
 *
 * 对应官方两套原生能力：
 * - `libbaseutil.so`（NativeHelper）：antispam 材料就绪后 `nativeSetToken`，
 *   之后 `nativeGetKey(versionCode)` 给出**当前版本的答案 RC4 密钥**。
 * - `libdpsdk.so`（DpSdk）：每请求现算的 `Dp-Ticket` 设备保护票据。
 *
 * 任何一步失败都静默降级：密钥回退旧的 Java 推导，票据头留空 ——
 * 与官方 `com.dprotect.a.a()` 在未初始化时返回空串的行为一致。
 */
object PhoneNativeSdk {

    private var appContext: Context? = null

    @Volatile
    private var tokenReady = false

    private var dpInitState = 0 // 0=未试 1=成功 2=失败

    /** 诊断信息（dpsdk/baseutil 的加载与初始化失败原因）。 */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * antispam 之后调用（与官方 `baseutil.a.f()` 的 nativeSetToken 同一步）。
     *
     * 返回 true 表示后续 `nativeGetKey` 可用。
     */
    fun setToken(context: Context, cuid: String, signA: String, signB: String): Boolean {
        appContext = context.applicationContext
        if (tokenReady) return true
        tokenReady = runCatching {
            // 官方身份包装：dpsdk/baseutil 白名单只认官方包名。
            NativeHelper.nativeSetToken(OfficialIdentityContext(context), cuid, signA, signB)
        }.onFailure { lastError = "baseutil setToken: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault(false)
        return tokenReady
    }

    /** 当前版本的答案解密密钥；原生不可用时返回 null（回退 Java 密钥）。 */
    fun responseKey(versionCode: String): String? {
        if (!tokenReady) return null
        val key = runCatching { NativeHelper.nativeGetKey(versionCode) }
            .onFailure { lastError = "baseutil getKey: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrNull()
        return key?.takeIf { it.isNotEmpty() && !it.startsWith("ERROR") }
    }

    /** 每请求的 Dp-Ticket；未就绪/失败时返回空串（请求方会跳过空头）。 */
    fun dpTicket(): String {
        if (!ensureDpInit()) return ""
        return runCatching { DpSdk.getTicket() }
            .onFailure { lastError = "dpsdk getTicket: ${it.javaClass.simpleName}: ${it.message}" }
            .getOrDefault("")
    }

    /** 后台预热（与官方在启动时异步 init 一致），让首搜时票据已就绪。 */
    fun preInit(context: Context) {
        appContext = context.applicationContext
        Thread { ensureDpInit() }.start()
    }

    /** 官方 X-Zyb-Trace-Id 的格式：`<hex16>:<hex16>:0:1`。 */
    fun traceId(): String {
        val hex = UUID.randomUUID().toString().replace("-", "").take(16)
        return "$hex:$hex:0:1"
    }

    /** 诊断信息（设置页/日志用）。 */
    fun status(): String {
        val baseutil = when {
            !baseutilLoaded -> "baseutil 未加载"
            !tokenReady -> "baseutil 已加载，setToken 失败"
            else -> "baseutil 就绪"
        }
        val dp = when (dpInitState) {
            1 -> "dpsdk 就绪"
            2 -> "dpsdk 初始化失败"
            else -> "dpsdk 未初始化"
        }
        return "$baseutil；$dp" + if (lastError.isNotEmpty()) "；$lastError" else ""
    }

    private fun ensureDpInit(): Boolean {
        if (dpInitState == 1) return true
        if (dpInitState == 2) return false
        synchronized(this) {
            if (dpInitState == 0) {
                val ctx = appContext
                dpInitState = if (ctx != null) {
                    runCatching {
                        // 官方身份包装：白名单校验调用方包名。
                        DpSdk.init(OfficialIdentityContext(ctx))
                    }.fold(
                        onSuccess = { 1 },
                        onFailure = { e ->
                            lastError = "dpsdk init: ${e.javaClass.simpleName}: ${e.message}"
                            2
                        },
                    )
                } else {
                    2
                }
            }
        }
        return dpInitState == 1
    }

    private val baseutilLoaded: Boolean =
        runCatching { NativeHelper.nativeGetRandom() }.fold(
            onSuccess = { true },
            onFailure = { e ->
                lastError = "baseutil load: ${e.javaClass.simpleName}: ${e.message}"
                false
            },
        )
}
