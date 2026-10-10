package com.heikeji.phonesearch.net

import android.content.Context
import android.content.SharedPreferences
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
 * ## 崩溃防护（v1.35.2，线上事故修复）
 *
 * 原生库在内部按**调用方包名**去 `getPackageInfo("com.kuaiduizuoye.scan")`：
 * 设备上没装官方客户端时，这个查询抛 `NameNotFoundException`，而原生代码没有
 * 清异常就继续 `GetObjectClass` → ART 直接 **JNI abort（SIGABRT）**。
 * abort 发生在原生层，Java 侧 `try/catch` 拦不住，**整个进程被杀**——
 * v1.35.1 上线后 23 台词典笔（未装官方 APP）因此闪退。
 *
 * 因此原生调用前必须同时满足三条：
 * 1. 原生库加载成功（32 位设备没有对应 .so）；
 * 2. 官方客户端已安装（包可见性见 manifest `<queries>`）；
 * 3. 本机没有被"崩溃守卫"停用。
 *
 * 崩溃守卫：进入原生调用前把 `call_in_flight` 落盘，调用返回后清掉。
 * 若下次启动发现标记还在，说明上次进程死在原生调用里（abort 无法捕获）——
 * 于是**跳过接下来 [SKIP_LAUNCHES] 次启动的原生调用**（把闪退循环压成偶发），
 * 之后自动重试一次；纯 Java 密钥链在此期间照常工作。
 *
 * 任何一步失败都静默降级：密钥回退旧的 Java 推导，票据头留空 ——
 * 与官方 `com.dprotect.a.a()` 在未初始化时返回空串的行为一致。
 */
object PhoneNativeSdk {

    private const val PREFS_NAME = "native_sdk_guard"
    private const val KEY_IN_FLIGHT = "call_in_flight"
    private const val KEY_SKIP_LAUNCHES = "skip_launches"

    /**
     * 原生调用崩溃后跳过的启动次数。
     *
     * 不永久停用：万一是被系统低内存杀掉（标记没来得及清）造成的误判，
     * 跳够次数后还能自动恢复原生能力；真会崩的设备则把崩溃频率压到
     * 「每 [SKIP_LAUNCHES] 次启动最多一次」，不再形成闪退循环。
     */
    private const val SKIP_LAUNCHES = 30

    private var appContext: Context? = null
    private var guardPrefs: SharedPreferences? = null

    @Volatile
    private var guardReady = false

    /** 崩溃守卫是否已停用本机原生 SDK。 */
    @Volatile
    var nativeDisabled = false
        private set

    @Volatile
    private var tokenReady = false

    private var dpInitState = 0 // 0=未试 1=成功 2=失败

    /** 诊断信息（dpsdk/baseutil 的加载与初始化失败原因）。 */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 文件日志：每次原生调用前后写一行，用于定位原生 abort（进程崩溃无 logcat 时）。
     * 见 [DiagLog]。
     */
    private fun log(line: String) {
        DiagLog.append(line)
    }

    /**
     * 初始化崩溃守卫（进程启动时调用一次）。
     *
     * 检测上一次进程是否死在原生调用里：是则跳过接下来若干次启动的原生调用。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (guardReady) return
        guardReady = true
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        guardPrefs = prefs
        if (prefs.getBoolean(KEY_IN_FLIGHT, false)) {
            // 上次原生调用没有正常返回 → 进程被原生 abort 杀掉（abort 无法捕获）。
            prefs.edit()
                .putInt(KEY_SKIP_LAUNCHES, SKIP_LAUNCHES)
                .putBoolean(KEY_IN_FLIGHT, false)
                .commit()
            log("崩溃守卫：检测到上次原生调用未返回，跳过接下来 $SKIP_LAUNCHES 次启动的原生调用")
        }
        val skip = prefs.getInt(KEY_SKIP_LAUNCHES, 0)
        if (skip > 0) {
            prefs.edit().putInt(KEY_SKIP_LAUNCHES, skip - 1).commit()
            nativeDisabled = true
            lastError = "原生 SDK 暂时停用（上次调用崩溃，还剩 ${skip - 1} 次启动后重试）"
        }
    }

    /** 原生调用前的三项前置条件。不满足时说明原因并返回 false。 */
    private fun nativeAllowed(ctx: Context, tag: String): Boolean {
        if (nativeDisabled) {
            lastError = "$tag：本机已停用原生 SDK"
            log("$tag 跳过：崩溃守卫已停用")
            return false
        }
        if (!NativeHelper.loaded) {
            lastError = "$tag：原生库未加载（缺少对应 ABI）"
            log("$tag 跳过：原生库未加载")
            return false
        }
        if (!isOfficialAppInstalled(ctx)) {
            lastError = "$tag：设备未安装官方客户端"
            log("$tag 跳过：设备未安装官方客户端（原生会 JNI abort）")
            return false
        }
        return true
    }

    /**
     * 标记"即将进入原生调用"（同步落盘）。
     *
     * 必须用 `commit()`：进程 abort 时不会有第二次写盘机会，
     * 这个标记就是下次启动判断"上次死于原生"的唯一依据。
     */
    private fun beginNativeCall(): Boolean {
        if (nativeDisabled) return false
        val prefs = guardPrefs ?: return false
        return prefs.edit().putBoolean(KEY_IN_FLIGHT, true).commit()
    }

    /** 原生调用安全返回，清掉标记。 */
    private fun endNativeCall() {
        guardPrefs?.edit()?.putBoolean(KEY_IN_FLIGHT, false)?.commit()
    }

    /**
     * antispam 之后调用（与官方 `baseutil.a.f()` 的 nativeSetToken 同一步）。
     *
     * 返回 true 表示后续 `nativeGetKey` 可用。
     */
    fun setToken(context: Context, cuid: String, signA: String, signB: String): Boolean {
        init(context)
        if (tokenReady) return true
        val ctx = appContext ?: return false
        if (!nativeAllowed(ctx, "baseutil")) return false
        log("setToken 开始（包名包装为官方）")
        if (!beginNativeCall()) return false
        tokenReady = try {
            // 官方身份包装：dpsdk/baseutil 白名单只认官方包名。
            NativeHelper.nativeSetToken(OfficialIdentityContext(ctx), cuid, signA, signB)
        } catch (t: Throwable) {
            lastError = "baseutil setToken: ${t.javaClass.simpleName}: ${t.message}"
            log("setToken 异常：${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            endNativeCall()
        }
        log("setToken 结束，结果=$tokenReady")
        return tokenReady
    }

    /** 当前版本的答案解密密钥；原生不可用时返回 null（回退 Java 密钥）。 */
    fun responseKey(versionCode: String): String? {
        if (!tokenReady) return null
        if (!beginNativeCall()) return null
        val key = try {
            NativeHelper.nativeGetKey(versionCode)
        } catch (t: Throwable) {
            lastError = "baseutil getKey: ${t.javaClass.simpleName}: ${t.message}"
            null
        } finally {
            endNativeCall()
        }
        return key?.takeIf { it.isNotEmpty() && !it.startsWith("ERROR") }
    }

    /**
     * 每请求的 Dp-Ticket；未就绪/失败时返回空串（请求方会跳过空头）。
     *
     * 整段（含首搜等待）包在崩溃守卫里：一次落盘标记覆盖本轮所有 getTicket 调用，
     * 万一原生在这条路径上 abort，下次启动就会永久停用原生。
     */
    fun dpTicket(): String {
        if (!ensureDpInit()) return ""
        if (!beginNativeCall()) return ""
        try {
            log("getTicket 开始")
            val ticket = try {
                DpSdk.getTicket()
            } catch (t: Throwable) {
                lastError = "dpsdk getTicket: ${t.javaClass.simpleName}: ${t.message}"
                log("getTicket 异常：${t.javaClass.simpleName}: ${t.message}")
                ""
            }
            if (ticket.isNotEmpty()) {
                log("getTicket 结束，票据长度=${ticket.length}")
                return ticket
            }
            // 官方在启动时异步初始化 dpsdk，票据要几秒后才可用（抓包证实：
            // antispam 无票、约 10 秒后的请求有票）。首搜时若票还没好，
            // 等一次（进程内只等这一次），避免冷启动后第一次搜题拿到占位内容。
            if (!waitedForTicket) {
                waitedForTicket = true
                log("票据暂未就绪，等待最多 5 秒（仅一次）")
                repeat(10) { i ->
                    if (i > 0) Thread.sleep(500)
                    val t = try {
                        DpSdk.getTicket()
                    } catch (e: Throwable) {
                        ""
                    }
                    if (t.isNotEmpty()) {
                        log("getTicket 结束，票据长度=${t.length}（等待后）")
                        return t
                    }
                }
            }
            log("getTicket 结束，票据长度=0")
            return ""
        } finally {
            endNativeCall()
        }
    }

    @Volatile
    private var waitedForTicket = false

    /** 后台预热（与官方在启动时异步 init 一致），让首搜时票据已就绪。 */
    fun preInit(context: Context) {
        init(context)
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
            nativeDisabled -> "baseutil 已停用（崩溃守卫）"
            !NativeHelper.loaded -> "baseutil 未加载"
            !tokenReady -> "baseutil 已加载，setToken 失败"
            else -> "baseutil 就绪"
        }
        val dp = when {
            nativeDisabled -> "dpsdk 已停用（崩溃守卫）"
            !DpSdk.loaded -> "dpsdk 未加载"
            dpInitState == 1 -> "dpsdk 就绪"
            dpInitState == 2 -> "dpsdk 初始化失败"
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
                dpInitState = if (ctx == null) {
                    2
                } else if (!nativeAllowed(ctx, "dpsdk")) {
                    2
                } else if (!beginNativeCall()) {
                    2
                } else {
                    log("DpSdk.init 开始（包名包装为官方）")
                    try {
                        // 官方身份包装：白名单校验调用方包名。
                        DpSdk.init(OfficialIdentityContext(ctx))
                        log("DpSdk.init 返回")
                        1
                    } catch (t: Throwable) {
                        lastError = "dpsdk init: ${t.javaClass.simpleName}: ${t.message}"
                        log("DpSdk.init 异常：${t.javaClass.simpleName}: ${t.message}")
                        2
                    } finally {
                        endNativeCall()
                    }
                }
            }
        }
        return dpInitState == 1
    }

    /**
     * 官方客户端是否安装（真实 PackageManager 查询）。
     *
     * manifest 已声明 `<queries>`，所以能可靠拿到结果；未安装时返回 false，
     * 调用方跳过原生 SDK，避免 NameNotFoundException 触发 JNI abort。
     */
    private fun isOfficialAppInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(OfficialIdentityContext.OFFICIAL_PACKAGE, 0)
    }.isSuccess
}
