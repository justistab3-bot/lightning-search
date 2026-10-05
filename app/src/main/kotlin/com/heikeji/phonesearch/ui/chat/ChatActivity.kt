package com.heikeji.phonesearch.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.SearchApp
import com.heikeji.phonesearch.databinding.ActivityChatBinding
import com.heikeji.phonesearch.databinding.ItemChatBubbleBinding
import com.heikeji.phonesearch.protocol.chat.model.ChatRole
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快问 AI 对话页。
 *
 * 接口是 `/kdchat/api/ask`（SSE 流式），协议实测记录见
 * `protocol/chat/ChatEventParser.kt`。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var viewModel: ChatViewModel
    private val adapter = BubbleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        val app = application as SearchApp
        viewModel = ViewModelProvider(
            this,
            ChatViewModel.factory(app, app.container.sessions.current()?.grade ?: 0),
        )[ChatViewModel::class.java]

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener { viewModel.clear() }

        binding.messageList.layoutManager = LinearLayoutManager(this)
        binding.messageList.adapter = adapter

        binding.thinkChip.setOnCheckedChangeListener { _, _ -> viewModel.toggleThink() }
        binding.searchChip.setOnCheckedChangeListener { _, _ -> viewModel.toggleSearch() }

        binding.sendButton.setOnClickListener {
            if (viewModel.state.value.streaming) {
                viewModel.stopStreaming()
            } else {
                submit()
            }
        }
        binding.imageButton.setOnClickListener { pickImage.launch("image/*") }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else {
                false
            }
        }

        observe()
        viewModel.start()
    }

    private fun submit() {
        val text = binding.input.text?.toString().orEmpty()
        if (text.isBlank()) return
        binding.input.setText("")
        hideKeyboard()
        viewModel.send(text)
    }

    // ------------------------------------------------------------------ 选图

    private val pickImage = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val caption = binding.input.text?.toString().orEmpty()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { loadShrunk(uri) }
            if (bytes == null) {
                binding.root.showMessage(getString(R.string.chat_image_failed))
                return@launch
            }
            binding.input.setText("")
            hideKeyboard()
            viewModel.sendImage(bytes, caption)
        }
    }

    /**
     * 读图并压到服务端能接受的大小。
     *
     * 服务端那张样图约 100KB；这里按最长边 1280、质量 80 压，通常落在 100–300KB。
     * 压不下去（超大图）就返回 null，宁可提示也不发一个几十兆的包。
     */
    private fun loadShrunk(uri: android.net.Uri): ByteArray? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth < 1 || bounds.outHeight < 1) return null

            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_EDGE) sample *= 2

            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(
                    stream,
                    null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } ?: return null

            try {
                val out = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray().takeIf { it.size <= MAX_IMAGE_BYTES }
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ChatUiState) {
        adapter.submit(state.bubbles)
        binding.emptyBox.isVisible = state.isEmpty
        binding.connecting.isVisible = state.connecting

        // 开关状态（用户点过之后以 state 为准）
        if (binding.thinkChip.isChecked != state.thinkEnabled) {
            binding.thinkChip.isChecked = state.thinkEnabled
        }
        if (binding.searchChip.isChecked != state.searchEnabled) {
            binding.searchChip.isChecked = state.searchEnabled
        }

        binding.sendButton.text = getString(
            if (state.streaming) R.string.chat_stop else R.string.chat_send,
        )
        binding.input.isEnabled = !state.streaming

        renderSuggestions(state.suggestions)

        state.message?.let {
            binding.root.showMessage(it)
            viewModel.consumeMessage()
        }

        if (state.bubbles.isNotEmpty()) {
            binding.messageList.scrollToPosition(state.bubbles.size - 1)
        }
    }

    private fun renderSuggestions(suggestions: List<String>) {
        if (binding.suggestionGroup.childCount == suggestions.size) return
        binding.suggestionGroup.removeAllViews()
        for (text in suggestions) {
            val chip = Chip(this).apply {
                this.text = text
                isCheckable = false
                setOnClickListener {
                    binding.input.setText(text)
                    submit()
                }
            }
            binding.suggestionGroup.addView(chip)
        }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(binding.input.windowToken, 0)
    }

    // ------------------------------------------------------------------ 列表

    private inner class BubbleAdapter : RecyclerView.Adapter<BubbleHolder>() {

        private var items: List<ChatBubble> = emptyList()

        fun submit(next: List<ChatBubble>) {
            // 流式时最后一条每帧都在变，直接全量刷新最省心（气泡数量很少）
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleHolder {
            val itemBinding = ItemChatBubbleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
            return BubbleHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: BubbleHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class BubbleHolder(
        private val item: ItemChatBubbleBinding,
    ) : RecyclerView.ViewHolder(item.root) {

        private var expanded = true

        fun bind(bubble: ChatBubble) {
            val isUser = bubble.role == ChatRole.USER

            // 对齐与配色
            val params = item.bubbleText.layoutParams as LinearLayout.LayoutParams
            params.gravity = if (isUser) Gravity.END else Gravity.START
            item.bubbleText.layoutParams = params
            item.bubbleText.setBackgroundResource(
                if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_assistant,
            )
            // 气泡最宽不超过屏幕的 82%
            item.bubbleText.maxWidth =
                (item.root.resources.displayMetrics.widthPixels * 0.82f).toInt()

            item.bubbleText.text = if (isUser) {
                bubble.text
            } else {
                ChatMarkdown.render(bubble.text)
            }
            item.bubbleText.isVisible = bubble.text.isNotEmpty()

            bindImage(bubble, isUser)

            // 思考过程
            val showReasoning = !isUser && bubble.reasoning.isNotBlank()
            item.reasoningBox.isVisible = showReasoning
            if (showReasoning) {
                val seconds = bubble.reasoningCostMs / 1000.0
                item.reasoningHeader.text = if (bubble.reasoningCostMs > 0) {
                    getString(R.string.chat_reasoning_with_time, seconds)
                } else {
                    getString(R.string.chat_reasoning)
                }
                item.reasoningText.text = bubble.reasoning.trim()
                // 流式时展开让用户看到在思考，结束后自动收起
                expanded = bubble.streaming
                applyExpanded()
                item.reasoningHeader.setOnClickListener {
                    expanded = !expanded
                    applyExpanded()
                }
            }

            item.bubbleProgress.isVisible = bubble.streaming && bubble.text.isEmpty()
        }

        /** 用户发的图：直接显示在气泡上方，右对齐。 */
        private fun bindImage(bubble: ChatBubble, isUser: Boolean) {
            val bytes = bubble.imageBytes
            if (bytes == null) {
                item.bubbleImage.setImageDrawable(null)
                item.bubbleImage.isVisible = false
                return
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                item.bubbleImage.isVisible = false
                return
            }
            item.bubbleImage.setImageBitmap(bitmap)
            val imageParams = item.bubbleImage.layoutParams as LinearLayout.LayoutParams
            imageParams.gravity = if (isUser) Gravity.END else Gravity.START
            item.bubbleImage.layoutParams = imageParams
            item.bubbleImage.isVisible = true
        }

        private fun applyExpanded() {
            item.reasoningText.isVisible = expanded
            item.reasoningHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                0,
            )
        }
    }

    companion object {
        /** 上传前把图缩到最长边这么多像素。 */
        private const val MAX_IMAGE_EDGE = 1280

        /** 压缩后仍超过这个大小就不发（服务端样图约 100KB）。 */
        private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

        fun newIntent(context: Context): Intent = Intent(context, ChatActivity::class.java)
    }
}
