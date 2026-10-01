package com.heikeji.phonesearch.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.databinding.ActivityLoginBinding
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.displayMessage
import com.heikeji.phonesearch.ui.common.showMessage
import com.heikeji.phonesearch.ui.home.HomeActivity
import com.heikeji.phonesearch.ui.notice.UsageNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录页：短信 / 密码两种方式。
 *
 * 网络与签名初始化全部在 IO 线程完成（ProtocolContext 会拒绝在主线程初始化）。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val container by lazy { appContainer }
    private var countdownJob: Job? = null

    private val returnToCaller: Boolean
        get() = intent.getBooleanExtra(EXTRA_RETURN_TO_CALLER, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        // 首次启动先给出使用边界说明，未接受就不进入应用。
        UsageNotice.ensureAccepted(
            activity = this,
            onAccepted = { startLoginFlow() },
            onDeclined = { finish() },
        )
    }

    private fun startLoginFlow() {
        if (container.sessions.current() != null) {
            leaveAfterLogin()
            return
        }

        setupTabs()
        binding.sendCodeButton.setOnClickListener { sendCode() }
        binding.loginButton.setOnClickListener { login() }
    }

    private fun setupTabs() {
        binding.modeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val sms = tab.position == 0
                binding.codeRow.visibility = if (sms) View.VISIBLE else View.GONE
                binding.passwordBox.visibility = if (sms) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun sendCode() {
        val phone = binding.phoneInput.text?.toString().orEmpty()
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { container.apiClient.sendSmsCode(phone) }
                binding.root.showMessage("验证码已发送")
                startCountdown()
            } catch (e: Exception) {
                binding.root.showMessage(e.displayMessage("验证码发送失败"))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (remaining in COUNTDOWN_SECONDS downTo 1) {
                binding.sendCodeButton.isEnabled = false
                binding.sendCodeButton.text =
                    getString(R.string.action_send_code_countdown, remaining)
                delay(1000L)
            }
            binding.sendCodeButton.isEnabled = true
            binding.sendCodeButton.setText(R.string.action_send_code)
        }
    }

    private fun login() {
        val phone = binding.phoneInput.text?.toString().orEmpty()
        val smsMode = binding.modeTabs.selectedTabPosition == 0
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (smsMode) {
                        container.apiClient.loginWithSmsCode(
                            phone,
                            binding.codeInput.text?.toString().orEmpty(),
                        )
                    } else {
                        container.apiClient.loginWithPassword(
                            phone,
                            binding.passwordInput.text?.toString().orEmpty(),
                        )
                    }
                }
                binding.root.showMessage("登录成功")
                leaveAfterLogin()
            } catch (e: Exception) {
                binding.root.showMessage(e.displayMessage("登录失败"))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun leaveAfterLogin() {
        if (returnToCaller) {
            finish()
        } else {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !busy
        binding.sendCodeButton.isEnabled = !busy && countdownJob?.isActive != true
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RETURN_TO_CALLER = "return_to_caller"
        private const val COUNTDOWN_SECONDS = 60

        /** 从结果页跳来时，登录成功只需返回调用方，不要新开首页。 */
        fun reloginIntent(context: android.content.Context): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_RETURN_TO_CALLER, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
