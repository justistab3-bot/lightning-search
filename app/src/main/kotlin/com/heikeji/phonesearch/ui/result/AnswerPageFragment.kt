package com.heikeji.phonesearch.ui.result

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment

/**
 * 答案区的一页 = 一个 WebView。
 *
 * 每页只放一个 WebView 是有意为之：答案内容在页内垂直滚动，页间由 ViewPager2 左右滑动切换。
 * 手势冲突由 [AnswerWebView] 在方向层面区分处理。
 *
 * 安全设置：JavaScript 关闭、file/content access 关闭、DOM storage 关闭、禁止一切导航。
 * 页面本身带 CSP（见 AnswerPageRenderer），图片只允许 https。
 */
class AnswerPageFragment : Fragment() {

    private var webView: AnswerWebView? = null

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
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = false
        settings.mediaPlaybackRequiresUserGesture = true

        view.webViewClient = object : WebViewClient() {
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
            null,
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
