package com.heikeji.phonesearch.ui.book

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.image.RemoteImageLoader
import com.heikeji.phonesearch.protocol.book.model.BookAnswerPage
import com.heikeji.phonesearch.ui.result.ZoomableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「查看整本答案」：左右翻页看整本教辅的答案扫描图。
 *
 * 数据来自 `/search/submit/booksearch`，见 [BookAnswerViewModel]。
 */
class BookAnswerActivity : AppCompatActivity() {

    private lateinit var viewModel: BookAnswerViewModel

    private lateinit var backButton: ImageButton
    private lateinit var bookTitle: TextView
    private lateinit var bookSubtitle: TextView
    private lateinit var pageIndicator: TextView
    private lateinit var pager: ViewPager2
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: MaterialButton
    private lateinit var footer: View
    private lateinit var footerIndex: TextView
    private lateinit var pageSeek: SeekBar

    private val adapter = PageAdapter()
    private var bookId: String = ""
    private var grade: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_answer)

        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        grade = intent.getIntExtra(EXTRA_GRADE, 0)

        viewModel = ViewModelProvider(this)[BookAnswerViewModel::class.java]

        bindViews()
        setupPager()
        backButton.setOnClickListener { finish() }
        retryButton.setOnClickListener { viewModel.load(bookId, grade) }
        observeState()

        viewModel.load(bookId, grade)
    }

    private fun bindViews() {
        backButton = findViewById(R.id.backButton)
        bookTitle = findViewById(R.id.bookTitle)
        bookSubtitle = findViewById(R.id.bookSubtitle)
        pageIndicator = findViewById(R.id.pageIndicator)
        pager = findViewById(R.id.pager)
        progress = findViewById(R.id.progress)
        errorBox = findViewById(R.id.errorBox)
        errorText = findViewById(R.id.errorText)
        retryButton = findViewById(R.id.retryButton)
        footer = findViewById(R.id.footer)
        footerIndex = findViewById(R.id.footerIndex)
        pageSeek = findViewById(R.id.pageSeek)
    }

    private fun setupPager() {
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    viewModel.onPageChanged(position)
                }
            },
        )
        pageSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) pager.setCurrentItem(value, false)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            },
        )
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: BookUiState) {
        progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        errorBox.visibility = if (state.failed) View.VISIBLE else View.GONE
        pager.visibility = if (state.total > 0) View.VISIBLE else View.GONE
        footer.visibility = if (state.total > 1) View.VISIBLE else View.GONE

        bookTitle.text = state.title.ifEmpty { getString(R.string.book_answer_title) }
        bookSubtitle.visibility = if (state.subtitle.isEmpty()) View.GONE else View.VISIBLE
        bookSubtitle.text = state.subtitle

        if (state.total > 0) {
            val label = getString(R.string.book_answer_page_format, state.index + 1, state.total)
            pageIndicator.visibility = View.VISIBLE
            pageIndicator.text = label
            footerIndex.text = label
            pageSeek.max = (state.total - 1).coerceAtLeast(0)
            if (pageSeek.progress != state.index) pageSeek.progress = state.index
        } else {
            pageIndicator.visibility = View.GONE
        }

        if (adapter.pages != state.pages) {
            adapter.pages = state.pages
            adapter.notifyDataSetChanged()
            if (state.total > 0 && pager.currentItem != state.index) {
                pager.setCurrentItem(state.index, false)
            }
        }
        if (state.failed) errorText.text = getString(R.string.book_answer_failed)
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 每页一个大图。
     *
     * 图片是 CDN 上的扫描件，体积不小，所以：
     * - 只在页面真正显示时才加载（[ViewHolder.onViewAttachedToWindow] 由绑定时机保证）
     * - 用 [LruCache] 按 URL 缓存已解码的 Bitmap，翻回来不用重新下载
     */
    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {

        var pages: List<BookAnswerPage> = emptyList()

        private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT,
                )
            }
            val image = ZoomableImageView(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            val spinner = ProgressBar(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER,
                )
            }
            container.addView(image)
            container.addView(spinner)
            return PageHolder(container, image, spinner)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = pages.getOrNull(position) ?: return
            holder.bind(page, cache)
        }

        override fun getItemCount(): Int = pages.size

        override fun onViewRecycled(holder: PageHolder) {
            super.onViewRecycled(holder)
            holder.reset()
        }
    }

    private inner class PageHolder(
        container: FrameLayout,
        private val image: ZoomableImageView,
        private val spinner: ProgressBar,
    ) : RecyclerView.ViewHolder(container) {

        private var job: kotlinx.coroutines.Job? = null

        fun bind(page: BookAnswerPage, cache: LruCache<String, Bitmap>) {
            val url = page.fullUrl.ifEmpty { page.previewUrl }
            reset()

            val cached = cache.get(url)
            if (cached != null) {
                image.setImageBitmap(cached)
                return
            }

            spinner.visibility = View.VISIBLE
            job = lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) { RemoteImageLoader.load(url) }
                if (bitmap != null) cache.put(url, bitmap)
                spinner.visibility = View.GONE
                if (bitmap != null) image.setImageBitmap(bitmap)
            }
        }

        fun reset() {
            job?.cancel()
            job = null
            image.setImageDrawable(null)
            spinner.visibility = View.GONE
        }
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_GRADE = "grade"

        /**
         * 扫描件解码后单张约 7-8MB（1200x1600 ARGB），
         * 给 48MB 让前后几页都留在内存里，翻回来不用重新下载。
         */
        private const val CACHE_BYTES = 48 * 1024 * 1024

        fun newIntent(context: Context, bookId: String, grade: Int): Intent =
            Intent(context, BookAnswerActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_GRADE, grade)
    }
}
