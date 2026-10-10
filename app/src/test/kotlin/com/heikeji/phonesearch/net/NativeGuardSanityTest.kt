package com.heikeji.phonesearch.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 原生 SDK 调用守卫的静态回归测试。
 *
 * 背景（线上事故）：官方 `libbaseutil.so` / `libdpsdk.so` 会按调用方包名去
 * `getPackageInfo("com.kuaiduizuoye.scan")`。设备没装官方客户端时该查询抛
 * `NameNotFoundException`，原生代码没清异常就继续 `GetObjectClass` →
 * **JNI abort（SIGABRT）直接杀进程**，Java 侧 try/catch 拦不住。
 * v1.35.1 上线后 23 台词典笔因此闪退。
 *
 * 所以每个原生调用点之前都必须满足：库已加载 + 官方客户端已安装 +
 * 未被崩溃守卫停用，并且用 `beginNativeCall()` 落盘 in-flight 标记
 * （下次启动据此永久停用本机原生）。
 *
 * 这类不变量编译期抓不到，只能靠静态检查钉住 —— 与 LayoutSanityTest /
 * ManifestSanityTest 同一思路。
 */
class NativeGuardSanityTest {

    private val source: String by lazy {
        val candidates = listOf(
            File("src/main/kotlin/com/heikeji/phonesearch/net/PhoneNativeSdk.kt"),
            File("app/src/main/kotlin/com/heikeji/phonesearch/net/PhoneNativeSdk.kt"),
            File("../app/src/main/kotlin/com/heikeji/phonesearch/net/PhoneNativeSdk.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 PhoneNativeSdk.kt，工作目录=${File("").absolutePath}")
        file.readText()
    }

    @Test
    fun `native guard pieces exist`() {
        assertTrue("缺少前置检查 nativeAllowed", source.contains("private fun nativeAllowed("))
        assertTrue("缺少 in-flight 标记", source.contains("private fun beginNativeCall("))
        assertTrue("缺少标记清除", source.contains("private fun endNativeCall("))
        assertTrue("缺少官方客户端安装检查", source.contains("private fun isOfficialAppInstalled("))
        assertTrue("缺少崩溃守卫初始化", source.contains("fun init(context: Context)"))
    }

    @Test
    fun `setToken checks the guard before touching native`() {
        val fnStart = source.indexOf("fun setToken(")
        assertTrue("找不到 setToken", fnStart > 0)
        val allowed = source.indexOf("nativeAllowed(ctx", fnStart)
        val nativeCall = source.indexOf("NativeHelper.nativeSetToken", fnStart)
        assertTrue("setToken 里找不到原生调用", nativeCall > 0)
        assertTrue("nativeSetToken 之前必须先过 nativeAllowed", allowed in (fnStart + 1) until nativeCall)
        val inFlight = source.indexOf("beginNativeCall()", fnStart)
        assertTrue("nativeSetToken 之前必须落 in-flight 标记", inFlight in (fnStart + 1) until nativeCall)
    }

    @Test
    fun `dp init checks the guard before touching native`() {
        val fnStart = source.indexOf("private fun ensureDpInit(")
        assertTrue("找不到 ensureDpInit", fnStart > 0)
        val allowed = source.indexOf("nativeAllowed(ctx", fnStart)
        val nativeCall = source.indexOf("DpSdk.init(", fnStart)
        assertTrue("ensureDpInit 里找不到 DpSdk.init", nativeCall > 0)
        assertTrue("DpSdk.init 之前必须先过 nativeAllowed", allowed in (fnStart + 1) until nativeCall)
    }

    @Test
    fun `ticket path is wrapped in the guard`() {
        val fnStart = source.indexOf("fun dpTicket(")
        assertTrue("找不到 dpTicket", fnStart > 0)
        val inFlight = source.indexOf("beginNativeCall()", fnStart)
        val ticketCall = source.indexOf("DpSdk.getTicket()", fnStart)
        assertTrue("getTicket 之前必须落 in-flight 标记", inFlight in (fnStart + 1) until ticketCall)
    }

    @Test
    fun `status does not probe native just for display`() {
        // 状态展示曾经调用 nativeGetRandom 探测加载情况 —— 那也是一次真实原生调用，
        // 在坏设备上同样可能 abort。现在只看加载标记。
        assertTrue("status 不应调用原生探测", !source.contains("nativeGetRandom()"))
    }
}
