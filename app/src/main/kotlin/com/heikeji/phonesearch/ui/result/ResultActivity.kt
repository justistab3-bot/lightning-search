package com.heikeji.phonesearch.ui.result

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.data.HistoryEntry
import com.heikeji.phonesearch.data.UserPrefs
import com.heikeji.phonesearch.databinding.ActivitySearchResultBinding
import com.heikeji.phonesearch.protocol.search.model.SearchResult
import com.heikeji.phonesearch.protocol.render.AnswerPageRenderer
import com.heikeji.phonesearch.ui.chat.ChatActivity
import com.heikeji.phonesearch.ui.common.PageNumberView
import com.heikeji.phonesearch.ui.common.SubjectStyle
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.dp
import com.heikeji.phonesearch.ui.login.LoginActivity
import com.heikeji.phonesearch.ui.verification.VerificationActivity
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * 结果页。
 *
 * 竖屏：上方固定原图，下方答案区左右滑动切换；向上滚动答案时原图自动收起，向下滚回来会展开。
 * 横屏：左右分栏，右上角可收起左栏让答案区全屏。
 *
 * 也用于打开历史记录里的缓存结果（不重新联网）。
 */
class ResultActivity : AppCompatActivity(), AnswerScrollHost, AnswerImageHost {

    private lateinit var binding: ActivitySearchResultBinding
    private val viewModel: SearchViewModel by viewModels {
        SearchViewModel.factory(appContainer)
    }

    private var jpegBytes: ByteArray = ByteArray(0)
    private var photoBitmap: Bitmap? = null
    private var result: SearchResult? = null
    private var mediator: TabLayoutMediator? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null
    private var historyEntry: HistoryEntry? = null

    private var readingMode = false
    private var answerFullscreen = false
    private var hasMultiplePages = false
    private var scrollAccumulator = 0
    private var lastAutoToggleAt = 0L
    private var suppressScrollUntil = 0L
    private var autoToggleThreshold = 0

    private var expandedHeight = 0
    private var retryAction: (() -> Unit)? = null

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val verificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { activityResult ->
        when (activityResult.resultCode) {
            VerificationActivity.RESULT_VERIFIED -> viewModel.retryAfterVerification(grade())

            VerificationActivity.RESULT_NEED_LOGIN -> launchLogin()

            else -> showMessage(getString(R.string.verification_failed)) { launchVerification() }
        }
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (appContainer.sessions.current() != null) {
            viewModel.resumeAfterLogin(grade())
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        setupChrome()
        setupStates()

        val historyId = intent.getStringExtra(EXTRA_HISTORY_ID)
        if (historyId != null) {
            openFromHistory(historyId)
            return
        }

        val path = intent.getStringExtra(EXTRA_JPEG_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            finish()
            return
        }
        jpegBytes = file.readBytes()
        if (jpegBytes.isEmpty()) {
            finish()
            return
        }

        setupPhoto()
        if (savedInstanceState == null) {
            viewModel.start(jpegBytes, grade())
        }
        observeState()
    }

    // ------------------------------------------------------------------ 初始化

    private fun setupChrome() {
        autoToggleThreshold = dp(24)
        binding.backButton.setOnClickListener { finish() }
        binding.headerTitle.text = getString(R.string.result_no_subject)
        binding.subjectBadge.subject = ""

        // 全屏只在横屏有意义（竖屏原图在上方，收起即可）。
        binding.fullscreenButton.visibility = if (isLandscape) View.VISIBLE else View.GONE
        binding.fullscreenButton.setOnClickListener { toggleAnswerFullscreen() }
        binding.exitFullscreenButton.setOnClickListener { toggleAnswerFullscreen() }
    }

    private fun setupStates() {
        binding.loadingState.visibility = View.GONE
        binding.messageState.visibility = View.GONE
        binding.pager.visibility = View.INVISIBLE
        binding.cancelButton.setOnClickListener { viewModel.cancel() }
        binding.retryButton.setOnClickListener { retryAction?.invoke() }
        binding.aiSolveButton.setOnClickListener { launchAiSolve() }
    }

    private fun setupPhoto() {
        expandedHeight = (resources.displayMetrics.heightPixels * PHOTO_HEIGHT_FRACTION).toInt()

        photoBitmap = decodeSampled(jpegBytes, 1600)
        binding.questionPhoto.setImageBitmap(photoBitmap)

        if (isLandscape) {
            // 横屏由布局权重撑满左栏，不做高度动画，也没有收起按钮。
            binding.togglePhoto.visibility = View.GONE
        } else {
            setPhotoHeight(expandedHeight, animate = false)
            binding.togglePhoto.setOnClickListener { setReadingMode(!readingMode) }
        }

        binding.questionPhoto.setOnClickListener {
            if (jpegBytes.isNotEmpty()) ImageViewerDialog.show(this, jpegBytes)
        }
    }

    private fun openFromHistory(historyId: String) {
        val entry = appContainer.history.list().firstOrNull { it.id == historyId }
        if (entry == null) {
            finish()
            return
        }
        historyEntry = entry
        jpegBytes = runCatching { entry.questionFile.readBytes() }.getOrDefault(ByteArray(0))
        setupPhoto()

        val cached = appContainer.history.loadResult(entry)
        if (cached == null) {
            showMessage(getString(R.string.result_empty), null)
        } else {
            showResult(cached, recordHistory = false)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: SearchUiState) {
        when (state) {
            SearchUiState.Idle -> Unit
            SearchUiState.Loading -> showLoading()
            is SearchUiState.Success -> showResult(state.result, recordHistory = true)
            is SearchUiState.Challenge -> {
                if (viewModel.shouldLaunchChallenge()) {
                    viewModel.markChallengeLaunched()
                    launchVerification()
                }
            }
            is SearchUiState.NeedLogin -> showMessage(state.message) { launchLogin() }
            is SearchUiState.Failure -> showMessage(state.message) {
                viewModel.start(jpegBytes, grade())
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun showLoading() {
        binding.loadingState.visibility = View.VISIBLE
        binding.messageState.visibility = View.GONE
        binding.pager.visibility = View.INVISIBLE
        binding.aiSolveButton.visibility = View.GONE
    }

    // ------------------------------------------------------------------ AI 解题

    /**
     * AI 解题按钮：作用于当前页那道题。
     *
     * 官方每道题的「AI 讲解」带搜题上下文（sid/subjectId/etid/pid）走
     * `/kdchat/api/ask`；etid 缺失（该题没带题目编号）时按钮隐藏。
     */
    private fun updateAiSolveButton() {
        val current = result?.items?.getOrNull(binding.pager.currentItem)
        val usable = current != null && current.tid.isNotEmpty() && jpegBytes.isNotEmpty()
        binding.aiSolveButton.visibility = if (usable) View.VISIBLE else View.GONE
    }

    private fun launchAiSolve() {
        val item = result?.items?.getOrNull(binding.pager.currentItem)
        if (item == null || item.tid.isEmpty()) return
        val searchResult = result ?: return

        val dir = File(cacheDir, "ai-solve").apply { mkdirs() }
        val file = File(dir, "question-${UUID.randomUUID()}.jpg")
        try {
            file.writeBytes(jpegBytes)
        } catch (e: Exception) {
            showMessage(getString(R.string.ai_solve_prepare_failed), null)
            return
        }

        startActivity(
            ChatActivity.aiSolveIntent(
                context = this,
                imagePath = file.absolutePath,
                sid = searchResult.sid,
                subjectId = searchResult.subjectId.toString(),
                etid = item.tid,
                pid = searchResult.pid,
                subject = item.subject.ifEmpty { searchResult.subject },
            ),
        )
    }

    private fun showMessage(message: String, onRetry: (() -> Unit)?) {
        binding.loadingState.visibility = View.GONE
        binding.messageState.visibility = View.VISIBLE
        binding.pager.visibility = View.INVISIBLE
        binding.aiSolveButton.visibility = View.GONE
        binding.messageText.text = message
        retryAction = onRetry
        binding.retryButton.visibility = if (onRetry == null) View.GONE else View.VISIBLE
    }

    private fun showResult(searchResult: SearchResult, recordHistory: Boolean) {
        result = searchResult
        binding.loadingState.visibility = View.GONE
        binding.messageState.visibility = View.GONE

        if (searchResult.items.isEmpty()) {
            showMessage(getString(R.string.result_empty)) {
                viewModel.start(jpegBytes, grade())
            }
            return
        }

        if (recordHistory) {
            runCatching { appContainer.history.record(jpegBytes, searchResult) }
        }

        val pages = searchResult.items.map { item ->
            AnswerPageRenderer.render(
                item = item,
                index = item.index,
                total = searchResult.items.size,
                subjectColorHex = SubjectStyle.colorHex(this, item.subject),
                darkTheme = isNightMode(),
            )
        }
        binding.pager.adapter = AnswerPagerAdapter(this, pages)
        binding.pager.offscreenPageLimit = 1
        binding.pager.isUserInputEnabled = pages.size > 1
        binding.pager.visibility = View.VISIBLE
        hasMultiplePages = pages.size > 1
        updateIndicatorVisibility()
        updateAiSolveButton()

        pageCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                scrollAccumulator = 0
                updateHeader(position)
                updateAiSolveButton()
            }
        }
        pageCallback = callback
        binding.pager.registerOnPageChangeCallback(callback)

        mediator?.detach()
        mediator = TabLayoutMediator(binding.indicator, binding.pager) { tab, position ->
            val number = layoutInflater
                .inflate(R.layout.tab_page_number, binding.indicator, false) as PageNumberView
            number.number = position + 1
            tab.customView = number
        }.also { it.attach() }

        updateHeader(0)
    }

    /**
     * 阅读模式下序号行也一起收起，把空间全留给答案。竖屏横屏行为一致。
     */
    private fun updateIndicatorVisibility() {
        val visible = hasMultiplePages && !readingMode
        binding.indicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateHeader(position: Int) {
        val items = result?.items ?: return
        val item = items.getOrNull(position) ?: items.firstOrNull() ?: return
        val subject = item.subject.ifEmpty { items.firstOrNull()?.subject.orEmpty() }

        binding.subjectBadge.subject = subject

        val name = subject.ifEmpty { getString(R.string.result_no_subject) }
        binding.headerTitle.text = if (items.size > 1) {
            getString(R.string.result_title_format, name, position + 1, items.size)
        } else {
            getString(R.string.result_title_plain, name)
        }
    }

    // ------------------------------------------------------------------ 原图收起 / 展开

    /**
     * 阅读模式（收起原图 / 隐藏序号行）由滚动驱动：
     * 向上滚超过阈值进入，向下滚回来退出。
     *
     * 防抖动做了三层：
     * 1. `suppressScrollUntil`——每次程序化切换布局后一段时间内忽略滚动回调。
     *    这是关键：收起原图会改变 WebView 高度、进而改变滚动位置，如果不屏蔽，
     *    这个「自己造成」的滚动会立刻把状态切回去，在临界点来回横跳。
     * 2. `lastAutoToggleAt` 冷却时间。
     * 3. 累计位移超上限时清零，避免长时间单向滚动后一碰就触发。
     */
    override fun onAnswerScrolled(deltaY: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now < suppressScrollUntil) return
        scrollAccumulator += deltaY

        if (now - lastAutoToggleAt < AUTO_TOGGLE_COOLDOWN_MS) return

        when {
            scrollAccumulator >= autoToggleThreshold && !readingMode -> setReadingMode(true)
            scrollAccumulator <= -autoToggleThreshold && readingMode -> setReadingMode(false)
            abs(scrollAccumulator) > autoToggleThreshold * 8 -> scrollAccumulator = 0
        }
    }

    private fun setReadingMode(enabled: Boolean, animate: Boolean = true) {
        readingMode = enabled
        if (!isLandscape) setPhotoPaneVisible(!enabled, animate)
        updateIndicatorVisibility()

        val now = SystemClock.elapsedRealtime()
        lastAutoToggleAt = now
        scrollAccumulator = 0
        suppressScrollUntil = now + SCROLL_SUPPRESS_MS
    }

    /**
     * 阅读模式下原图**整块隐藏**（不是留一条），把竖向空间全部让给答案。
     * 恢复靠向下滚动；淡出过程中若已退出阅读模式则不隐藏。
     */
    private fun setPhotoPaneVisible(visible: Boolean, animate: Boolean) {
        val pane = binding.photoPane
        pane.animate().cancel()
        if (visible) {
            pane.visibility = View.VISIBLE
            if (animate) {
                pane.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            } else {
                pane.alpha = 1f
            }
        } else if (animate) {
            pane.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_MS)
                .withEndAction { if (readingMode) pane.visibility = View.GONE }
                .start()
        } else {
            pane.alpha = 0f
            pane.visibility = View.GONE
        }
    }

    private fun setPhotoHeight(target: Int, animate: Boolean) {
        if (isLandscape) return
        val params = binding.photoCard.layoutParams
        if (!animate) {
            params.height = target
            binding.photoCard.layoutParams = params
            return
        }
        val animator = ValueAnimator.ofInt(params.height, target)
        animator.duration = 180L
        animator.addUpdateListener { valueAnimator ->
            params.height = valueAnimator.animatedValue as Int
            binding.photoCard.layoutParams = params
        }
        animator.start()
    }

    /** 横屏：收起顶栏与左栏，答案区占满整屏；靠浮动按钮退出。 */
    private fun toggleAnswerFullscreen() {
        if (!isLandscape) return
        answerFullscreen = !answerFullscreen

        binding.photoPane.visibility = if (answerFullscreen) View.GONE else View.VISIBLE
        binding.headerBar.visibility = if (answerFullscreen) View.GONE else View.VISIBLE
        binding.exitFullscreenButton.visibility =
            if (answerFullscreen) View.VISIBLE else View.GONE
        binding.fullscreenButton.setImageResource(
            if (answerFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
        )
        binding.fullscreenButton.contentDescription = getString(
            if (answerFullscreen) R.string.action_answer_exit_fullscreen
            else R.string.action_answer_fullscreen,
        )
    }

    /** 答案页里点击图片：打开全屏查看器。 */
    override fun openAnswerImage(url: String) {
        if (isFinishing || isDestroyed) return
        ImageViewerDialog.showRemote(this, url)
    }

    // ------------------------------------------------------------------ 其他

    private fun launchVerification() {
        verificationLauncher.launch(VerificationActivity.newIntent(this, currentValidatedInfo()))
    }

    private fun currentValidatedInfo(): String =
        (viewModel.state.value as? SearchUiState.Challenge)?.validatedInfo.orEmpty()

    private fun launchLogin() {
        loginLauncher.launch(LoginActivity.reloginIntent(this))
    }

    /**
     * 请求用的年级：登录了用账号里的，没登录用本地选的。
     *
     * 原来是 `sessions.current()?.grade ?: 0` —— 不登录就传 0，识别质量会明显变差。
     */
    private fun grade(): Int =
        UserPrefs.effectiveGrade(this, appContainer.sessions.current()?.grade)

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun decodeSampled(jpeg: ByteArray, maxEdge: Int): Bitmap? {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxEdge) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    override fun onDestroy() {
        mediator?.detach()
        mediator = null
        pageCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        photoBitmap?.takeIf { !it.isRecycled }?.recycle()
        photoBitmap = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_JPEG_PATH = "jpeg_path"
        private const val EXTRA_HISTORY_ID = "history_id"
        private const val PHOTO_HEIGHT_FRACTION = 0.36f
        private const val AUTO_TOGGLE_COOLDOWN_MS = 350L

        /** 程序化切换布局后屏蔽滚动回调的时长，避免「自己造成的滚动」把状态切回去。 */
        private const val SCROLL_SUPPRESS_MS = 700L

        private const val FADE_IN_MS = 160L
        private const val FADE_OUT_MS = 140L

        fun newIntent(context: Context, jpegPath: String): Intent =
            Intent(context, ResultActivity::class.java).putExtra(EXTRA_JPEG_PATH, jpegPath)

        fun historyIntent(context: Context, historyId: String): Intent =
            Intent(context, ResultActivity::class.java).putExtra(EXTRA_HISTORY_ID, historyId)
    }
}
