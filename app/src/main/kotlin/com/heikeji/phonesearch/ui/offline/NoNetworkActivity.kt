package com.heikeji.phonesearch.ui.offline

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.databinding.ActivityNoNetworkBinding
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.showMessage
import kotlinx.coroutines.launch

/**
 * 断网时的全屏提示页。
 *
 * 由 [com.heikeji.phonesearch.SearchApp] 在检测到无网络时拉起；
 * 网络恢复后自己关掉，用户不需要手动返回。
 */
class NoNetworkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoNetworkBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoNetworkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        binding.retryButton.setOnClickListener {
            if (appContainer.network.isOnline()) {
                finish()
            } else {
                binding.root.showMessage(getString(R.string.no_network_still_offline))
            }
        }
        binding.settingsButton.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        // 网络一恢复就自动关闭，回到用户原来的页面
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.network.online.collect { online ->
                    if (online) finish()
                }
            }
        }
    }
}
