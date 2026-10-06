package com.heikeji.phonesearch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络连通性监测。
 *
 * 用回调而不是轮询：断网/恢复都能立刻拿到通知，界面据此弹出或关闭全屏提示页。
 *
 * 判定标准是「有活跃网络且该网络声明了 INTERNET 能力」。不额外要求 VALIDATED——
 * 刚连上 WiFi 时 VALIDATED 往往要等一两秒才置位，那会造成误报。
 *
 * **兼容到 API 21**：
 * - `getSystemService(Class)`、`getActiveNetwork()`、`registerDefaultNetworkCallback()`
 *   都是 API 23/24 才有的，5.0 上要用字符串版 `getSystemService`、`activeNetworkInfo`
 *   和 `registerNetworkCallback(NetworkRequest, ...)`（后者 API 21 就有）。
 * - 两者的语义差别：`registerDefaultNetworkCallback` 只盯「默认网络」，
 *   而 `registerNetworkCallback` 会对所有匹配的网络回调。我们只关心「有没有网」，
 *   所以每次回调都重新问一遍 [isOnline]，不依赖回调本身的状态。
 */
class NetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)

    private val _online = MutableStateFlow(isOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = true
        }

        override fun onLost(network: Network) {
            // 可能还有其他网络可用，重新判定一次
            _online.value = isOnline()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun start() {
        if (registered) return
        val cm = manager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
            registered = true
        } catch (e: RuntimeException) {
            // 注册失败不影响其他功能，退化成启动时判定一次
            registered = false
        }
        _online.value = isOnline()
    }

    fun stop() {
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
    }

    fun isOnline(): Boolean {
        val cm = manager ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        // getActiveNetwork() 是 API 23 才有的
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return info.isConnected
    }
}
