package com.heikeji.phonesearch.ui.essay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.protocol.aiwriting.AiWritingRequest
import com.heikeji.phonesearch.protocol.aiwriting.EssayLanguage
import com.heikeji.phonesearch.protocol.aiwriting.model.WritingMode
import kotlinx.coroutines.launch

/**
 * AI 作文。
 *
 * 竖屏：单列；横屏：左配置右结果（见 `layout-land/activity_essay.xml`）。
 */
class EssayActivity : AppCompatActivity() {

    private lateinit var viewModel: EssayViewModel

    private lateinit var backButton: ImageButton
    private lateinit var copyButton: MaterialButton
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var titleInput: EditText
    private lateinit var queryTypeLabel: TextView
    private lateinit var genreSpinner: Spinner
    private lateinit var wordCountSpinner: Spinner
    private lateinit var gradeSpinner: Spinner
    private lateinit var generateButton: MaterialButton
    private lateinit var outlineCard: MaterialCardView
    private lateinit var outlineText: TextView
    private lateinit var writeFromOutlineButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var resultTitle: TextView
    private lateinit var resultText: TextView
    private lateinit var fontSmallerButton: MaterialButton
    private lateinit var fontLargerButton: MaterialButton

    /** 上一次渲染时的语言，用于判断是否需要重建字数档位。 */
    private var renderedLanguage: EssayLanguage? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var fontSizeSp = DEFAULT_FONT_SIZE_SP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_essay)

        viewModel = ViewModelProvider(this)[EssayViewModel::class.java]

        bindViews()
        setupModeGroup()
        setupGenreSpinner()
        setupGradeSpinner()
        setupFontControls()
        setupActions()
        observeState()
    }

    private fun bindViews() {
        backButton = findViewById(R.id.backButton)
        copyButton = findViewById(R.id.copyButton)
        modeGroup = findViewById(R.id.modeGroup)
        titleInput = findViewById(R.id.titleInput)
        queryTypeLabel = findViewById(R.id.queryTypeLabel)
        genreSpinner = findViewById(R.id.genreSpinner)
        wordCountSpinner = findViewById(R.id.wordCountSpinner)
        gradeSpinner = findViewById(R.id.gradeSpinner)
        generateButton = findViewById(R.id.generateButton)
        outlineCard = findViewById(R.id.outlineCard)
        outlineText = findViewById(R.id.outlineText)
        writeFromOutlineButton = findViewById(R.id.writeFromOutlineButton)
        progress = findViewById(R.id.progress)
        statusLabel = findViewById(R.id.statusLabel)
        resultTitle = findViewById(R.id.resultTitle)
        resultText = findViewById(R.id.resultText)
        fontSmallerButton = findViewById(R.id.fontSmallerButton)
        fontLargerButton = findViewById(R.id.fontLargerButton)
    }

    private fun setupModeGroup() {
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = modeOf(checkedId)
            // 提纲类流程的服务端参数尚未完全探通，先挡住，避免走到必然失败的路径
            if (mode.needsThought && !THOUGHT_FLOW_READY) {
                Toast.makeText(this, R.string.essay_thought_pending, Toast.LENGTH_SHORT).show()
                modeGroup.check(R.id.modeQuick)
                return@addOnButtonCheckedListener
            }
            viewModel.onModeChanged(mode)
        }
        modeGroup.check(R.id.modeQuick)
    }

    private fun modeOf(buttonId: Int): WritingMode = when (buttonId) {
        R.id.modeThinking -> WritingMode.THINKING
        R.id.modeMap -> WritingMode.THINKING_MAP
        R.id.modeEnglish -> WritingMode.ENGLISH
        else -> WritingMode.QUICK
    }

    private fun setupGradeSpinner() {
        val labels = AiWritingRequest.GRADES.map { it.second }
        gradeSpinner.adapter = spinnerAdapter(labels)
        val defaultIndex = AiWritingRequest.GRADES
            .indexOfFirst { it.first == AiWritingRequest.DEFAULT_GRADE }
            .coerceAtLeast(0)
        gradeSpinner.setSelection(defaultIndex)
        gradeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                AiWritingRequest.GRADES.getOrNull(position)?.let {
                    viewModel.onGradeChanged(it.first)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 文体：第 0 项是「自动识别」，其余对应 [AiWritingRequest.GENRES]。 */
    private fun setupGenreSpinner() {
        val options = listOf(getString(R.string.essay_genre_auto)) + AiWritingRequest.GENRES
        genreSpinner.adapter = spinnerAdapter(options)
        genreSpinner.setSelection(0)
        genreSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                // position 0 = 自动 -> null
                viewModel.onGenreChanged(AiWritingRequest.GENRES.getOrNull(position - 1))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    // ------------------------------------------------------------------ 正文字号

    private fun setupFontControls() {
        fontSizeSp = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)
            .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        applyFontSize()

        fontSmallerButton.setOnClickListener { changeFontSize(-FONT_STEP_SP) }
        fontLargerButton.setOnClickListener { changeFontSize(FONT_STEP_SP) }
    }

    private fun changeFontSize(delta: Int) {
        val next = (fontSizeSp + delta).coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        if (next == fontSizeSp) return
        fontSizeSp = next
        prefs.edit().putInt(KEY_FONT_SIZE, next).apply()
        applyFontSize()
        Toast.makeText(
            this,
            getString(R.string.essay_font_size_format, fontSizeSp),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 标题跟着正文一起放大，始终比正文大 6sp。 */
    private fun applyFontSize() {
        resultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
        resultTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSizeSp + 6).toFloat())
    }

    private fun setupActions() {
        backButton.setOnClickListener { finish() }

        titleInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    viewModel.onTitleChanged(s?.toString().orEmpty())
                }
            },
        )

        generateButton.setOnClickListener {
            if (viewModel.state.value.running) viewModel.cancel() else viewModel.generate()
        }

        copyButton.setOnClickListener { copyResult() }

        writeFromOutlineButton.setOnClickListener { viewModel.generate() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: EssayUiState) {
        // 语言变了就重建字数档位（中英档位不通用）
        if (renderedLanguage != state.language) {
            renderedLanguage = state.language
            rebuildWordCountSpinner(state)
        }

        if (titleInput.text.toString() != state.title &&
            titleInput.text.toString() != state.title.trim()
        ) {
            titleInput.setText(state.title)
            titleInput.setSelection(titleInput.text.length)
        }

        // 文体
        if (state.queryType.isNotEmpty()) {
            queryTypeLabel.visibility = View.VISIBLE
            queryTypeLabel.text = getString(R.string.essay_query_type_format, state.queryType)
        } else {
            queryTypeLabel.visibility = View.GONE
        }

        // 英语作文没有文体概念，禁用文体选择（切模式时状态已归零）
        val genreEnabled = state.language == EssayLanguage.CHINESE
        genreSpinner.isEnabled = genreEnabled
        genreSpinner.alpha = if (genreEnabled) 1f else 0.35f

        // 提纲卡片：仅提纲类模式显示
        val showOutline = state.mode.needsThought && state.outline.isNotBlank()
        outlineCard.visibility = if (showOutline) View.VISIBLE else View.GONE
        if (showOutline) outlineText.text = state.outline

        // 生成按钮
        generateButton.text = getString(
            if (state.running) R.string.essay_action_stop else R.string.essay_action_generate,
        )

        // 进度与状态
        progress.visibility = if (state.running) View.VISIBLE else View.GONE
        statusLabel.text = statusText(state)
        statusLabel.visibility = if (statusLabel.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // 正文标题：服务端有时把标题当正文首段返回（带 `#`），已在协议层抽出来
        val heading = state.article?.displayTitle.orEmpty()
        val showTitle = state.done && heading.isNotEmpty() && heading != state.title.trim()
        resultTitle.visibility = if (showTitle) View.VISIBLE else View.GONE
        if (showTitle) resultTitle.text = heading

        // 正文
        resultText.text = if (state.hasResult) {
            state.text
        } else {
            getString(R.string.essay_empty_result)
        }
        copyButton.visibility = if (state.done && state.hasResult) View.VISIBLE else View.GONE

        // 流式期间跟随到底部
        if (state.running && state.hasResult) scrollResultToBottom()
    }

    private fun statusText(state: EssayUiState): String = when {
        state.failed -> getString(R.string.essay_failed)
        state.stage == EssayUiState.Stage.DETECTING -> getString(R.string.essay_status_detecting)
        state.stage == EssayUiState.Stage.OUTLINING -> getString(R.string.essay_status_outlining)
        state.stage == EssayUiState.Stage.WRITING -> getString(R.string.essay_status_writing)
        state.stage == EssayUiState.Stage.REWRITING -> getString(R.string.essay_status_rewriting)
        state.stage == EssayUiState.Stage.DONE -> {
            val actual = state.article?.wordCount ?: state.text.length
            val base = getString(
                R.string.essay_status_done_format,
                state.queryType.ifEmpty { state.mode.label },
                actual,
                state.wordCount,
            )
            // 明显没写够时给一句提示，免得用户以为是自己选错了字数
            if (actual < targetOf(state.wordCount) * 0.85) {
                base + "\n" + getString(R.string.essay_status_short)
            } else {
                base
            }
        }

        else -> ""
    }

    /** `"800+"` -> `800`。 */
    private fun targetOf(wordCount: String): Int =
        wordCount.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun rebuildWordCountSpinner(state: EssayUiState) {
        val counts = AiWritingRequest.wordCountsOf(state.language)
        wordCountSpinner.adapter = spinnerAdapter(counts)
        val index = counts.indexOf(state.wordCount).coerceAtLeast(0)
        wordCountSpinner.setSelection(index)
        wordCountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                counts.getOrNull(position)?.let { viewModel.onWordCountChanged(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun copyResult() {
        val text = viewModel.state.value.text
        if (text.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.essay_title), text))
        Toast.makeText(this, R.string.essay_copied, Toast.LENGTH_SHORT).show()
    }

    /** 正文可能在 NestedScrollView（竖屏）或 ScrollView（横屏）里，往上找最近的滚动容器。 */
    private fun scrollResultToBottom() {
        var parent: android.view.ViewParent? = resultText.parent
        while (parent != null) {
            val current = parent
            when (current) {
                is NestedScrollView -> {
                    current.post { current.fullScroll(View.FOCUS_DOWN) }
                    return
                }

                is ScrollView -> {
                    current.post { current.fullScroll(View.FOCUS_DOWN) }
                    return
                }

                else -> parent = current.parent
            }
        }
    }

    private companion object {
        /**
         * 提纲流程（`writingThought` 系列端点）的地址与必需参数已探明，
         * 但 `createThought` 仍返回 `5324 生成思路失败`，缺的参数待真实抓包确认。
         */
        const val THOUGHT_FLOW_READY = false

        const val PREFS_NAME = "essay_ui"
        const val KEY_FONT_SIZE = "fontSizeSp"
        const val DEFAULT_FONT_SIZE_SP = 16
        const val MIN_FONT_SIZE_SP = 12
        const val MAX_FONT_SIZE_SP = 28
        const val FONT_STEP_SP = 2
    }
}
