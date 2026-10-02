package com.heikeji.phonesearch.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 网络连通性监测。
 *
 * 用 `registerDefaultNetworkCallback` 而不是轮询：断网/恢复都能立刻拿到回调，
 * 界面据此弹出或关闭全屏提示页。
 *
 * 判定标准是「有活跃网络且该网络声明了 INTERNET 能力」。不额外要求 VALIDATED——
 * 刚连上 WiFi 时 VALIDATED 往往要等一两秒才置位，那会造成误报。
 */
class NetworkMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)

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
            cm.registerDefaultNetworkCallback(callback)
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
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
