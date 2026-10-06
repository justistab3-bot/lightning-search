package com.heikeji.phonesearch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.heikeji.phonesearch.account.SecureSessionStore
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.analytics.Analytics
import com.heikeji.phonesearch.data.HistoryStore
import com.heikeji.phonesearch.data.StorageCleaner
import com.heikeji.phonesearch.net.ApiClient
import com.heikeji.phonesearch.net.AiWritingClient
import com.heikeji.phonesearch.net.ChatClient
import com.heikeji.phonesearch.net.DiagLog
import com.heikeji.phonesearch.net.DeviceIdentity
import com.heikeji.phonesearch.net.HttpTransport
import com.heikeji.phonesearch.net.NetworkMonitor
import com.heikeji.phonesearch.net.PhoneNativeSdk
import com.heikeji.phonesearch.net.ProtocolContext
import com.heikeji.phonesearch.search.SearchChallengeStore
import com.heikeji.phonesearch.search.SearchRepository
import com.heikeji.phonesearch.ui.offline.NoNetworkActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class SearchApp : Application() {

    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resumedActivity = WeakReference<Activity>(null)

    override fun onCreate() {
        super.onCreate()
        enableTls12OnOldDevices()
        // 友盟预初始化：不采集任何信息，但必须在 Application.onCreate 里调，
        // 否则首次启动的日活会漏统。正式初始化要等用户同意隐私政策（见 Analytics）。
        Analytics.preInit(this)
        DiagLog.init(this)
        container = AppContainer(this)
        container.network.start()

        // 崩溃落盘：原生 abort 没有 logcat 时，靠这个文件定位
        // （Android/data/com.heikeji.phonesearch/files/protocol-log.txt）。
        installCrashFileLogger()

        // 磁盘回收：清掉陈旧安装包与相机临时图（后台做，不挡启动）
        scope.launch(Dispatchers.IO) { StorageCleaner.sweep(this@SearchApp) }

        registerActivityLifecycleCallbacks(ActivityTracker())
        observeConnectivity()
    }

    /** 未捕获异常追加到 protocol-log.txt，再交给原处理器（保留友盟上报链）。 */
    private fun installCrashFileLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagLog.append(
                "CRASH ${thread.name} ${throwable.javaClass.name}: ${throwable.message}",
            )
            throwable.stackTrace.take(10).forEach { DiagLog.append("  at $it") }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Android 5.x 上把 TLS 1.2 显式打开。
     *
     * 本应用所有接口都是 https，而老系统的 `HttpsURLConnection` 默认协议列表里
     * 不一定包含 TLS 1.2 —— 一旦服务端只收 TLS 1.2，表现就是「所有请求都失败」，
     * 而且报错很难懂。API 22 以后系统默认就带上了，所以只在前面对付。
     */
    private fun enableTls12OnOldDevices() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) return
        try {
            val context = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
            context.init(null, null, null)
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
        } catch (e: Exception) {
            // 拿不到就维持系统默认，至少不至于起不来
        }
    }

    /**
     * 断网时拉起全屏提示页。
     *
     * 两条触发路径：
     * 1. 每次有页面回到前台时检查一次（覆盖「打开应用时就没网」）
     * 2. 监听连通性变化（覆盖「用着用着断网了」）
     */
    private fun observeConnectivity() {
        scope.launch {
            container.network.online.collect { online ->
                if (!online) showOfflinePage()
            }
        }
    }

    private fun showOfflinePage() {
        val activity = resumedActivity.get() ?: return
        if (activity is NoNetworkActivity) return
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching {
            activity.startActivity(Intent(activity, NoNetworkActivity::class.java))
        }
    }

    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivity = WeakReference(activity)
            if (activity !is NoNetworkActivity && !container.network.isOnline()) {
                showOfflinePage()
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumedActivity.get() === activity) resumedActivity = WeakReference(null)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}

/** 简单的依赖容器，避免引入 DI 框架。 */
class AppContainer(context: Context) {

    val identity = DeviceIdentity(context)

    /**
     * 手机版模拟：对作业帮系主机附加官方 7.7.0 的请求头。
     * 票据与 Trace 都是按请求现算；原生 SDK 未就绪时自动留空（行为同官方）。
     */
    val transport = HttpTransport(phoneHeaders = {
        buildMap {
            PhoneNativeSdk.dpTicket().takeIf { it.isNotEmpty() }?.let { put("Dp-Ticket", it) }
            put("zyb-cuid", identity.cuid)
            identity.did.takeIf { it.isNotEmpty() }?.let { put("zyb-did", it) }
            put("na__kf_source__", "scancode")
            put("X-Zyb-Trace-Id", PhoneNativeSdk.traceId())
            put("X-Zyb-Trace-T", System.currentTimeMillis().toString())
        }
    })
    val protocol = ProtocolContext(context, identity, transport)
    val network = NetworkMonitor(context)

    private val sessionStore = SecureSessionStore(context)
    val sessions = SessionRepository(sessionStore)

    val apiClient = ApiClient(identity, protocol, sessions, transport)
    val challenges = SearchChallengeStore()
    val searchRepository = SearchRepository(apiClient, protocol, challenges)
    val history = HistoryStore(context)

    /** AI 作文（独立域名、无签名、SSE 流式）。 */
    val aiWriting = AiWritingClient()

    /** 快问 AI（同域名、走通用签名、SSE 流式）。 */
    val chat = ChatClient(identity, protocol, sessions, transport)

    init {
        sessions.restore()
        // 预热官方设备保护 SDK（后台异步，同官方启动流程）：
        // 票据要几秒后才可用，启动时先初始化，首搜就能拿到（v1.33.2 已修掉
        // 之前的 JNI abort —— manifest <queries> + 官方 APP 安装预检查）。
        PhoneNativeSdk.preInit(context)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SearchApp).container
