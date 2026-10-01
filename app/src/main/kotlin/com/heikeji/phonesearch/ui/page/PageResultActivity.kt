package com.heikeji.phonesearch.ui.page

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.appContainer
import com.heikeji.phonesearch.databinding.ActivityPageResultBinding
import com.heikeji.phonesearch.image.OriginalImageHandle
import com.heikeji.phonesearch.image.QuestionImageProcessor
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.model.PageQuestionBlock
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.protocol.render.AnswerPageRenderer
import com.heikeji.phonesearch.ui.common.PageNumberView
import com.heikeji.phonesearch.ui.common.SubjectStyle
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.displayMessage
import com.heikeji.phonesearch.ui.common.dp
import com.heikeji.phonesearch.ui.common.showMessage
import com.heikeji.phonesearch.ui.login.LoginActivity
import com.heikeji.phonesearch.ui.result.AnswerImageHost
import com.heikeji.phonesearch.ui.result.AnswerPagerAdapter
import com.heikeji.phonesearch.ui.result.ImageViewerDialog
import com.heikeji.phonesearch.ui.verification.VerificationActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 整页搜题结果页。
 *
 * 层级：答案候选 -> 题块 -> 整页 -> 首页；框选页取消时回到进入框选前的页面。
 *
 * 原图句柄由 [PageViewModel] 持有（ViewModel 销毁时释放临时文件），
 * 这里的缩略图只是预览，实际裁剪从原始文件区域解码。
 */
class PageResultActivity : AppCompatActivity(), AnswerImageHost {

    private lateinit var binding: ActivityPageResultBinding
    private val viewModel: PageViewModel by viewModels {
        PageViewModel.factory(appContainer)
    }

    private var thumbnail: Bitmap? = null
    private var mediator: TabLayoutMediator? = null
    private var pageCallback: ViewPager2.OnPageChangeCallback? = null
    private var renderedSignature: String = ""

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val rect = SelectImageActivity.rectOf(result.data) ?: return@registerForActivityResult
        viewModel.refine(rect, grade())
    }

    private val verificationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (result.resultCode) {
            VerificationActivity.RESULT_VERIFIED -> viewModel.retryAfterVerification(grade())
            VerificationActivity.RESULT_NEED_LOGIN -> launchLogin()
            else -> binding.root.showMessage(getString(R.string.verification_failed))
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
        binding = ActivityPageResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        binding.backButton.setOnClickListener { finish() }
        binding.cropButton.setOnClickListener { openSelection() }
        binding.pagePhoto.setOnClickListener { openSelection() }

        if (viewModel.originalHandle != null) {
            // 配置变更后重建：句柄在 ViewModel 里，直接接着渲染
            bindThumbnail()
            observe()
            return
        }

        val path = intent.getStringExtra(EXTRA_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            finish()
            return
        }
        val extraRotation = intent.getIntExtra(EXTRA_ROTATION, 0)

        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val handle = withContext(Dispatchers.IO) {
                    prepareHandle(file, extraRotation)
                }
                viewModel.attach(handle)
                bindThumbnail()
                observe()
                viewModel.start(grade())
            } catch (e: Exception) {
                binding.progress.visibility = View.GONE
                binding.root.showMessage(e.displayMessage(getString(R.string.camera_failed)))
            }
        }
    }

    /**
     * 把拍摄结果规范化成「已摆正、无 EXIF 旋转」的 JPEG，再交给原图句柄。
     *
     * 这样句柄的 EXIF 逆映射就是恒等变换，框选坐标不会因为相机旋转而错位。
     */
    private fun prepareHandle(source: File, extraRotation: Int): OriginalImageHandle {
        val normalized = if (extraRotation % 360 == 0) {
            source
        } else {
            val bitmap = QuestionImageProcessor.decodeOriented(
                file = source,
                extraRotationDegrees = extraRotation,
                minEdge = ProtocolProfile.IMAGE_PAGE_OUTPUT_MAX_EDGE,
            )
            try {
                val bytes = QuestionImageProcessor.encode(bitmap, Int.MAX_VALUE, NORMALIZE_QUALITY)
                File(source.parentFile, "page-${UUID.randomUUID()}.jpg").apply {
                    writeBytes(bytes)
                }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        return OriginalImageHandle.prepare(normalized, SearchMode.PAGE)
    }

    private fun bindThumbnail() {
        val handle = viewModel.originalHandle ?: return
        binding.progress.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { handle.previewBitmap(THUMBNAIL_MAX_EDGE) }
                thumbnail?.takeIf { !it.isRecycled }?.recycle()
                thumbnail = bitmap
                binding.pagePhoto.setImageBitmap(bitmap)
            } catch (e: Exception) {
                // 缩略图失败不影响主流程
            }
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    // ------------------------------------------------------------------ 渲染

    private fun render(state: PageUiState) {
        binding.progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.cropButton.isEnabled = state.result != null && !state.loading

        state.message?.let {
            binding.root.showMessage(it)
            viewModel.consumeMessage()
        }
        if (state.needLogin) {
            launchLogin()
            return
        }

        val result = state.result
        if (result == null) {
            binding.blockRow.removeAllViews()
            binding.emptyText.visibility = View.GONE
            binding.pager.visibility = View.INVISIBLE
            return
        }

        binding.subjectBadge.subject = result.subject
        binding.headerTitle.text = if (result.subject.isEmpty()) {
            getString(R.string.page_title_format, getString(R.string.page_title), result.blocks.size)
        } else {
            getString(R.string.page_title_format, result.subject, result.blocks.size)
        }

        renderBlocks(state)
        renderStatus(state)
        renderCandidates(state)
    }

    private fun renderBlocks(state: PageUiState) {
        if (binding.blockRow.childCount != state.blocks.size) {
            binding.blockRow.removeAllViews()
            state.blocks.forEachIndexed { position, block ->
                binding.blockRow.addView(createBlockChip(block, position))
            }
        }
        for (position in 0 until binding.blockRow.childCount) {
            val chip = binding.blockRow.getChildAt(position)
            chip.isSelected = position == state.selectedBlockPosition
        }
    }

    private fun createBlockChip(block: PageQuestionBlock, position: Int): TextView =
        TextView(this).apply {
            text = getString(R.string.page_block_format, position + 1)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            background = ContextCompat.getDrawable(
                this@PageResultActivity,
                if (block.hasAnswer) R.drawable.bg_block_chip else R.drawable.bg_block_chip_empty,
            )
            setTextColor(
                ContextCompat.getColorStateList(this@PageResultActivity, R.color.block_chip_text),
            )
            isClickable = true
            setOnClickListener { viewModel.selectBlock(position) }
        }

    private fun renderStatus(state: PageUiState) {
        val block = state.selectedBlock
        val text = when {
            block == null -> ""
            state.isRefined -> getString(R.string.page_refined)
            block.warning.isNotEmpty() -> block.warning
            else -> ""
        }
        binding.statusText.text = text
        binding.statusText.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderCandidates(state: PageUiState) {
        val candidates = state.candidates
        val signature = "${state.selectedBlockPosition}:${state.isRefined}:${candidates.size}"
        if (signature == renderedSignature) return
        renderedSignature = signature

        binding.emptyText.visibility = if (candidates.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyText.text = if (state.blocks.isEmpty()) {
            getString(R.string.page_empty)
        } else {
            state.selectedBlock?.warning?.takeIf { it.isNotEmpty() }
                ?: getString(R.string.page_block_no_answer)
        }

        if (candidates.isEmpty()) {
            binding.pager.adapter = null
            binding.pager.visibility = View.INVISIBLE
            binding.indicator.visibility = View.GONE
            return
        }

        val pages = candidates.mapIndexed { index, item ->
            AnswerPageRenderer.render(
                item = item,
                index = index + 1,
                total = candidates.size,
                subjectColorHex = SubjectStyle.colorHex(this, item.subject.ifEmpty { state.result?.subject.orEmpty() }),
                darkTheme = isNightMode(),
            )
        }
        binding.pager.adapter = AnswerPagerAdapter(this, pages)
        binding.pager.offscreenPageLimit = 1
        binding.pager.isUserInputEnabled = pages.size > 1
        binding.pager.visibility = View.VISIBLE
        binding.pager.setCurrentItem(state.selectedCandidatePosition, false)

        pageCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = viewModel.selectCandidate(position)
        }
        pageCallback = callback
        binding.pager.registerOnPageChangeCallback(callback)

        mediator?.detach()
        mediator = if (pages.size > 1) {
            TabLayoutMediator(binding.indicator, binding.pager) { tab, position ->
                val number = layoutInflater
                    .inflate(R.layout.tab_page_number, binding.indicator, false) as PageNumberView
                number.number = position + 1
                tab.customView = number
            }.also { it.attach() }
        } else {
            null
        }
        binding.indicator.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ 操作

    private fun openSelection() {
        val handle = viewModel.originalHandle ?: return
        val state = viewModel.state.value
        val block = state.selectedBlock

        // 初始框：有服务端题框就按题框归一化，否则用默认区域
        val initial = block?.location?.let { quad ->
            val width = state.result?.pictureWidth ?: 0
            val height = state.result?.pictureHeight ?: 0
            if (width > 0 && height > 0) {
                floatArrayOf(
                    quad.left.toFloat() / width,
                    quad.top.toFloat() / height,
                    quad.right.toFloat() / width,
                    quad.bottom.toFloat() / height,
                )
            } else {
                null
            }
        } ?: ProtocolProfile.QUAD_DEFAULT_RECT.copyOf()

        val highlight = block?.location?.let { quad ->
            val width = state.result?.pictureWidth ?: 0
            val height = state.result?.pictureHeight ?: 0
            if (width > 0 && height > 0) {
                floatArrayOf(
                    quad.left.toFloat() / width,
                    quad.top.toFloat() / height,
                    quad.right.toFloat() / width,
                    quad.bottom.toFloat() / height,
                )
            } else {
                null
            }
        }

        cropLauncher.launch(
            SelectImageActivity.newIntent(this, handle.file.absolutePath, initial, highlight),
        )
    }

    override fun openAnswerImage(url: String) {
        if (isFinishing || isDestroyed) return
        ImageViewerDialog.showRemote(this, url)
    }

    private fun launchLogin() {
        loginLauncher.launch(LoginActivity.reloginIntent(this))
    }

    private fun grade(): Int = appContainer.sessions.current()?.grade ?: 0

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onDestroy() {
        mediator?.detach()
        mediator = null
        pageCallback?.let { binding.pager.unregisterOnPageChangeCallback(it) }
        pageCallback = null
        thumbnail?.takeIf { !it.isRecycled }?.recycle()
        thumbnail = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "capture_path"
        private const val EXTRA_ROTATION = "extra_rotation"
        private const val THUMBNAIL_MAX_EDGE = 1200

        /** 规范化中间图的质量：尽量无损，真正上传前还会再按 2400/92 处理。 */
        private const val NORMALIZE_QUALITY = 95

        fun newIntent(context: Context, capturePath: String, extraRotation: Int = 0): Intent =
            Intent(context, PageResultActivity::class.java)
                .putExtra(EXTRA_PATH, capturePath)
                .putExtra(EXTRA_ROTATION, extraRotation)
    }
}
