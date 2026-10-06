package com.heikeji.phonesearch

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.heikeji.phonesearch.account.SecureSessionStore
import com.heikeji.phonesearch.account.SessionRepository
import com.heikeji.phonesearch.data.HistoryStore
import com.heikeji.phonesearch.data.StorageCleaner
import com.heikeji.phonesearch.net.ApiClient
import com.heikeji.phonesearch.net.AiWritingClient
import com.heikeji.phonesearch.net.ChatClient
import com.heikeji.phonesearch.net.DeviceIdentity
import com.heikeji.phonesearch.net.HttpTransport
import com.heikeji.phonesearch.net.NetworkMonitor
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
        container = AppContainer(this)
        container.network.start()

        // 磁盘回收：清掉陈旧安装包与相机临时图（后台做，不挡启动）
        scope.launch(Dispatchers.IO) { StorageCleaner.sweep(this@SearchApp) }

        registerActivityLifecycleCallbacks(ActivityTracker())
        observeConnectivity()
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
    val transport = HttpTransport()
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
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SearchApp).container
