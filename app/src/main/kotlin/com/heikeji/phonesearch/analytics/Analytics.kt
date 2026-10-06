package com.heikeji.phonesearch.analytics

import android.content.Context
import com.heikeji.phonesearch.BuildConfig
import com.umeng.commonsdk.UMConfigure
import com.umeng.umcrash.UMCrash

/**
 * 友盟+ 统计与崩溃分析的接入层。
 *
 * **合规是这个类存在的首要原因**，不是「包一层好看」。工信部要求：
 *
 * 1. `preInit()` 可以在 `Application.onCreate` 里无条件调用 —— 它**不采集任何设备信息**，
 *    只是把 SDK 准备好，这样首次启动的日活统计才准。
 * 2. `init()` **必须在用户点了「同意」之后**才能调。调用它才真正开始采集设备信息并上报。
 * 3. 用户不同意就不能初始化；用户事后关掉开关，要调
 *    [UMConfigure.submitPolicyGrantResult] 把结果同步给 SDK。
 *
 * 另外：AppKey 从 `local.properties` 注入（见 `build.gradle.kts`）。开源项目里
 * 把 AppKey 写死在源码里，别人 fork 之后数据会打进同一个友盟账号。
 * 读不到就整个跳过，功能不受影响。
 */
object Analytics {

    private const val PREFS = "consent"
    private const val KEY_AGREED = "privacyAgreed"
    private const val KEY_ENABLED = "analyticsEnabled"

    /** 渠道名，用来在友盟后台区分分发来源。 */
    private const val CHANNEL = "official"

    /** AppKey 是否配置了。没配就整个功能静默关闭。 */
    val configured: Boolean get() = BuildConfig.UMENG_APPKEY.isNotEmpty()

    private var initialized = false

    // ------------------------------------------------------------------ 同意状态

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用户是否同意过隐私政策。 */
    fun hasConsent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGREED, false)

    /** 是否允许匿名统计（同意之后才有效，默认开）。 */
    fun isEnabled(context: Context): Boolean =
        hasConsent(context) && prefs(context).getBoolean(KEY_ENABLED, true)

    /**
     * 记录用户的选择并据此初始化或关闭 SDK。
     *
     * @param granted 是否同意隐私政策
     */
    fun setConsent(context: Context, granted: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_AGREED, granted)
            .putBoolean(KEY_ENABLED, granted)
            .apply()
        if (granted) {
            initIfAllowed(context)
        } else {
            // 不同意：明确告诉 SDK 不要采集
            runCatching { UMConfigure.submitPolicyGrantResult(context.applicationContext, false) }
        }
    }

    /**
     * 开关匿名统计。
     *
     * 关掉只是不再上报，**不会删除已经上报的数据** —— 隐私政策里要写清楚这点。
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            runCatching { UMConfigure.submitPolicyGrantResult(context.applicationContext, false) }
        } else if (hasConsent(context)) {
            initIfAllowed(context)
        }
    }

    // ------------------------------------------------------------------ 初始化

    /**
     * 预初始化。**不采集任何信息**，可以在用户同意之前调用。
     *
     * 必须放在 `Application.onCreate`，否则首次启动的日活统计会漏。
     */
    fun preInit(context: Context) {
        if (!configured) return
        runCatching {
            UMConfigure.setLogEnabled(BuildConfig.DEBUG)
            UMConfigure.preInit(context.applicationContext, BuildConfig.UMENG_APPKEY, CHANNEL)
        }
    }

    /**
     * 正式初始化。**只有用户同意且开关打开时才会真正执行。**
     *
     * 重复调用是安全的（内部有 [initialized] 兜底）。
     */
    fun initIfAllowed(context: Context) {
        if (!configured || initialized) return
        if (!isEnabled(context)) return

        val app = context.applicationContext
        runCatching {
            UMConfigure.init(
                app,
                BuildConfig.UMENG_APPKEY,
                CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                "", // 不接推送
            )
            UMConfigure.submitPolicyGrantResult(app, true)
            // 崩溃 / ANR 采集。原生崩溃和 ANR 都开，这两个才是线上最需要看到的。
            UMCrash.init(app, BuildConfig.UMENG_APPKEY, CHANNEL)
            UMCrash.enableANRLog(true)
            initialized = true
        }
    }
}
