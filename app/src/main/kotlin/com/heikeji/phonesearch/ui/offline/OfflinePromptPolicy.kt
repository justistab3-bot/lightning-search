package com.heikeji.phonesearch.ui.offline

/**
 * 每次断网只自动提示一次。状态仅在当前进程内保存，由主线程访问。
 *
 * 返回原页面、旋转屏幕或从网络设置返回，都不能再次弹出同一次断网的提示；
 * 恢复网络后才允许为下一次断网提示。启动失败不消耗提示机会。
 */
internal class OfflinePromptPolicy {
    private var shownForCurrentOutage = false

    fun onNetworkChanged(online: Boolean) {
        if (online) shownForCurrentOutage = false
    }

    fun tryShow(online: Boolean): Boolean {
        onNetworkChanged(online)
        if (online || shownForCurrentOutage) return false
        shownForCurrentOutage = true
        return true
    }

    fun onLaunchFailed() {
        shownForCurrentOutage = false
    }
}
