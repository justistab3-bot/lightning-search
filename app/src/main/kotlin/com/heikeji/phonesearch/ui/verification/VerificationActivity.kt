package com.heikeji.phonesearch.ui.verification

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.databinding.ActivityVerificationBinding
import com.heikeji.phonesearch.net.ApiException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.codec.UrlForm
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/**
 * 官方安全验证页（原 SearchVerificationActivity + O0.s + O0.y）。
 *
 * **不实现任何绕过逻辑**：只加载官方页面、注入受限 Bridge，并把页面的请求按白名单转发。
 *
 * 返回结果：
 * - [RESULT_VERIFIED]   验证完成，上层用同一张图重试搜题
 * - [RESULT_NEED_LOGIN] 页面要求重新登录
 * - RESULT_CANCELED     用户放弃或加载失败
 */
class VerificationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVerificationBinding
    private val container by lazy { appContainer }

    private lateinit var expectedUrl: String
    private lateinit var bridgeSecret: String
    private var kdussSnapshot: String = ""
    private var userAgent: String = ""
    private var finishedWithResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        val validatedInfo = intent.getStringExtra(EXTRA_VALIDATED_INFO).orEmpty()
        if (validatedInfo.isEmpty()) {
            finish()
            return
        }

        kdussSnapshot = container.sessions.kduss()
        bridgeSecret = "watch-verification:" + UUID.randomUUID()
        expectedUrl = buildExpectedUrl(validatedInfo)

        binding.toolbar.setNavigationOnClickListener { finishWith(RESULT_CANCELED) }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = finishWith(RESULT_CANCELED)
            },
        )

        setupWebView()
        installCookies()
        binding.webView.loadUrl(expectedUrl)
    }

    // ---------------------------------------------------------------- WebView 配置

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        settings.saveFormData = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        userAgent = settings.userAgentString + " " + userAgentSuffix()
        settings.userAgentString = userAgent

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = RestrictedClient()
        binding.webView.webChromeClient = BridgeChromeClient()
    }

    /** 原实现附加的 UA 后缀，用于让验证页识别为 App 内嵌环境。 */
    private fun userAgentSuffix(): String = listOf(
        "scancode_vc/${ProtocolProfile.VC}",
        "scancode_vcname/${ProtocolProfile.VC_NAME}",
        "scancode_cuid/${container.identity.cuid}",
        "HyAppName/${ProtocolProfile.APP_ID}",
        "zyb_jsBridge/1",
        "jsBridge_jsInterface/1",
        "WatchSearch/1.0.1",
    ).joinToString(" ")

    private fun installCookies() {
        val manager = CookieManager.getInstance()
        manager.setCookie(
            ProtocolProfile.HOST_VERIFY,
            "cuid=${UrlForm.encode(container.identity.cuid)}; Domain=zuoyebang.com; " +
                "Path=/; Secure; SameSite=None",
            null,
        )
        val kduss = container.sessions.kduss()
        if (kduss.isNotEmpty()) {
            manager.setCookie(
                ProtocolProfile.HOST_VERIFY,
                "KDUSS=${UrlForm.encode(kduss)}; Domain=zuoyebang.com; " +
                    "Path=/; Secure; SameSite=None; HttpOnly",
                null,
            )
        }
        manager.flush()
    }

    private fun buildExpectedUrl(rawValidatedInfo: String): String {
        val value = if (rawValidatedInfo.trimStart().startsWith("{")) {
            rawValidatedInfo
        } else {
            runCatching { URLDecoder.decode(rawValidatedInfo, "UTF-8") }.getOrDefault(rawValidatedInfo)
        }
        return ProtocolProfile.HOST_VERIFY + ProtocolProfile.PATH_VERIFICATION +
            "?validatedInfo=" + URLEncoder.encode(value, "UTF-8")
    }

    // ---------------------------------------------------------------- 受限 WebViewClient

    private inner class RestrictedClient : WebViewClient() {

        /** 主框架 HTML 由客户端自行抓取并注入 Bridge（原 O0.s.shouldInterceptRequest）。 */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (!request.isForMainFrame) return null
            if (request.url.toString() != expectedUrl) return null
            return try {
                val injected = VerificationBridge.inject(fetchHtml(), widthDp(), bridgeSecret)
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8)),
                )
            } catch (e: Exception) {
                runOnUiThread { showError("验证页加载失败，请检查网络后重试") }
                WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    ByteArrayInputStream(ByteArray(0)),
                )
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = if (request.isForMainFrame) {
            request.url.toString() != expectedUrl
        } else {
            !"https".equals(request.url.scheme, ignoreCase = true)
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            !"https".equals(Uri.parse(url).scheme, ignoreCase = true)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (url != expectedUrl) {
                view.stopLoading()
                showError("验证页面地址已变化，请重新加载")
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            binding.progress.visibility = View.GONE
            if (url == expectedUrl && !finishedWithResult) {
                view.evaluateJavascript("if(window.fePageResume)window.fePageResume();", null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) showError("验证页加载失败，请检查网络后重试")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse,
        ) {
            if (request.isForMainFrame) showError("验证服务暂时不可用，请稍后重试")
        }
    }

    // ---------------------------------------------------------------- Bridge

    private inner class BridgeChromeClient : WebChromeClient() {

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: JsPromptResult,
        ): Boolean {
            result.cancel()
            if (message != bridgeSecret) return true
            if (url != expectedUrl || view.url != expectedUrl) return true
            if (!isExpectedUri(url)) return true
            if (finishedWithResult) return true
            if (container.sessions.kduss() != kdussSnapshot) {
                finishWith(RESULT_NEED_LOGIN)
                return true
            }
            val payload = defaultValue ?: return true
            if (payload.length > MAX_BRIDGE_PAYLOAD) return true
            dispatchBridgeMessage(payload)
            return true
        }
    }

    private fun isExpectedUri(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return false
        }
        val rawQuery = uri.rawQuery ?: return false
        return "https".equals(uri.scheme, ignoreCase = true) &&
            uri.host == VERIFY_HOST &&
            uri.rawUserInfo == null &&
            uri.port == -1 &&
            uri.rawPath == ProtocolProfile.PATH_VERIFICATION &&
            uri.rawFragment == null &&
            rawQuery.startsWith("validatedInfo=") &&
            !rawQuery.contains('&')
    }

    private fun dispatchBridgeMessage(payload: String) {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return
        }
        val action = json.optString("action")
        val param = json.optJSONObject("param")
        val callbackKey = json.optString("callbackKey", "")
        val session = container.sessions.current()

        when (action) {
            "getuserinfo" -> reply(
                callbackKey,
                JSONObject()
                    .put("uid", session?.uid.orEmpty())
                    .put("uname", session?.userName.orEmpty())
                    .put("userId", session?.uid.orEmpty())
                    .put("gradeId", session?.grade ?: 0)
                    .put("identityName", ""),
                200,
            )

            "common", "core_commonData" -> {
                val data = JSONObject()
                for ((key, value) in container.identity.publicParams()) data.put(key, value)
                data.put("cuid", UrlForm.encode(container.identity.cuid))
                data.put("appid", ProtocolProfile.APP_ID)
                data.put("pkgname", ProtocolProfile.PKG_NAME)
                data.put("grade", session?.grade ?: 0)
                data.put("nt", container.identity.networkType())
                data.put("host", ProtocolProfile.HOST_KDDZY)
                reply(callbackKey, data, 200)
            }

            // 验证完成：交给上层用同一张图重试搜题。
            "app_zyb_searchRefreshData" -> finishWith(RESULT_VERIFIED)

            "loginForResult" -> finishWith(RESULT_NEED_LOGIN)

            "app_zyb_identityCheck" -> handleIdentityCheck(param, callbackKey)

            "toast" -> {
                val text = param?.optString("text", "").orEmpty()
                if (text.isNotEmpty()) Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                reply(callbackKey, JSONObject(), 200)
            }

            "core_removeLoading" -> reply(callbackKey, JSONObject(), 200)

            else -> reply(callbackKey, JSONObject(), 404)
        }
    }

    private fun handleIdentityCheck(param: JSONObject?, callbackKey: String) {
        val name = param?.optString("name", "").orEmpty()
        val id = param?.optString("id", "").orEmpty()
        if (name.isEmpty() || id.isEmpty()) {
            reply(callbackKey, JSONObject(), 404)
            return
        }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.apiClient.checkIdentity(name, id)
                }
                reply(
                    callbackKey,
                    JSONObject().put("age", result.age).put("pass", result.pass),
                    200,
                )
            } catch (e: ApiException) {
                reply(callbackKey, JSONObject(), 404)
            }
        }
    }

    private fun reply(callbackKey: String, data: JSONObject, code: Int) {
        binding.webView.evaluateJavascript(
            VerificationBridge.replyScript(callbackKey, data, code),
            null,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun fetchHtml(): String {
        val result = container.transport.getHtml(
            url = expectedUrl,
            readTimeoutMs = ProtocolProfile.VERIFICATION_READ_TIMEOUT_MS,
            maxBytes = ProtocolProfile.MAX_VERIFICATION_HTML_BYTES,
            userAgent = userAgent,
            cookie = CookieManager.getInstance().getCookie(expectedUrl),
        )
        if (result.statusCode != 200) throw IOException("验证服务暂时不可用")
        return result.body
    }

    private fun widthDp(): Int = resources.configuration.screenWidthDp

    private fun showError(message: String) {
        binding.progress.visibility = View.GONE
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun finishWith(resultCode: Int) {
        if (finishedWithResult) return
        finishedWithResult = true
        setResult(resultCode)
        finish()
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VALIDATED_INFO = "validated_info"
        private const val VERIFY_HOST = "paisou.zuoyebang.com"
        private const val MAX_BRIDGE_PAYLOAD = 32768

        const val RESULT_VERIFIED = 1001
        const val RESULT_NEED_LOGIN = 1002

        fun newIntent(context: Context, validatedInfo: String): Intent =
            Intent(context, VerificationActivity::class.java)
                .putExtra(EXTRA_VALIDATED_INFO, validatedInfo)
    }
}
