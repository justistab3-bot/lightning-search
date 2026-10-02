package com.heikeji.phonesearch.ui.result

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewAssetLoader
import com.heikeji.phonesearch.protocol.render.AnswerPageRenderer

/**
 * 答案区的一页 = 一个 WebView。
 *
 * 每页只放一个 WebView 是有意为之：答案内容在页内垂直滚动，页间由 ViewPager2 左右滑动切换。
 * 手势冲突由 [AnswerWebView] 在方向层面区分处理。
 *
 * ## 关于 JavaScript
 *
 * 答案页**开启** JavaScript，用于跑本地的 KaTeX（公式排版）。安全边界靠三件事守住：
 *
 * 1. **CSP**（见 [AnswerPageRenderer]）把脚本来源锁死在 `appassets.androidplatform.net`，
 *    答案正文里的脚本、事件处理器、外部资源一律加载不了；
 * 2. **HTML 白名单清洗**在渲染前就剔除了 `<script>` 与 `on*` 属性；
 * 3. **WebView 自身设置**：不开 file/content access、不开 DOM storage、不注册任何 JS Bridge、
 *    禁止多窗口，主框架只允许停在本地页面。
 *
 * 本地资源通过 [WebViewAssetLoader] 以 https 提供，因此不需要 `allowFileAccess`。
 */
class AnswerPageFragment : Fragment() {

    private var webView: AnswerWebView? = null

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(requireContext()))
            .build()
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = AnswerWebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.setGeolocationEnabled(false)
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.mediaPlaybackRequiresUserGesture = true

        // 移除系统注入的桥接口，也不注册任何业务 Bridge。
        @Suppress("DEPRECATION")
        run {
            view.removeJavascriptInterface("searchBoxJavaBridge_")
            view.removeJavascriptInterface("accessibility")
            view.removeJavascriptInterface("accessibilityTraversal")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)

        view.webViewClient = object : WebViewClient() {
            /** 本地 KaTeX 资源走 AssetLoader，其余子资源一律拒绝。 */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            // 图片外层包了 <a href="图片地址">，点击时打开应用自己的查看器；
            // 其余一切导航仍然一律拦掉。
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url.toString()
                if (isImageUrl(url)) {
                    (activity as? AnswerImageHost)?.openAnswerImage(url)
                }
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isImageUrl(url)) {
                    (activity as? AnswerImageHost)?.openAnswerImage(url)
                }
                return true
            }
        }

        view.loadDataWithBaseURL(
            AnswerPageRenderer.ASSET_BASE_URL,
            arguments?.getString(ARG_HTML).orEmpty(),
            "text/html",
            "UTF-8",
            null,
        )

        // 把竖向滚动量回报给宿主，用于自动收起/展开上方原图。
        view.onVerticalScroll = { delta ->
            (activity as? AnswerScrollHost)?.onAnswerScrolled(delta)
        }

        webView = view
        return view
    }

    override fun onDestroyView() {
        webView?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_HTML = "html"

        private val IMAGE_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
        )

        /** 只把 https 的图片地址交给查看器，其余一律当作普通导航拦掉。 */
        private fun isImageUrl(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            if (IMAGE_EXTENSIONS.any { path.endsWith(it) }) return true
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                ?.lowercase() ?: return false
            return host.endsWith(".zuoyebang.cc")
        }

        fun newInstance(html: String): AnswerPageFragment = AnswerPageFragment().apply {
            arguments = bundleOf(ARG_HTML to html)
        }
    }
}
