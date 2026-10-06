package com.heikeji.phonesearch.ui.page

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.databinding.ActivitySelectImageBinding
import com.heikeji.phonesearch.image.QuestionImageProcessor
import com.heikeji.phonesearch.protocol.core.NetConfig
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.displayMessage
import com.heikeji.phonesearch.ui.common.showMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 框选一道题。
 *
 * 显示的是**原图的预览**，但实际裁剪由 `OriginalImageHandle.cropToJpeg` 从原始文件
 * 区域解码完成——预览图只用来决定框在哪里。
 *
 * 返回值是归一化 `float[4]`：left / top / right / bottom。
 */
class SelectImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectImageBinding
    private var preview: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectImageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        val path = intent.getStringExtra(EXTRA_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            finish()
            return
        }

        binding.backButton.setOnClickListener { finish() }
        binding.resetButton.setOnClickListener { binding.selectionView.resetView() }
        binding.confirmButton.setOnClickListener { confirm() }

        val initial = intent.getFloatArrayExtra(EXTRA_RECT)
            ?: NetConfig.QUAD_DEFAULT_RECT.copyOf()
        binding.selectionView.setSelection(
            RectF(initial[0], initial[1], initial[2], initial[3]),
        )

        val quad = intent.getFloatArrayExtra(EXTRA_QUAD)
        if (quad != null && quad.size == 4) {
            binding.selectionView.setHighlightQuad(
                RectF(quad[0], quad[1], quad[2], quad[3]),
            )
            binding.hintText.setText(R.string.page_select_hint_highlight)
        }

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    QuestionImageProcessor.decodePreview(file, PREVIEW_MAX_EDGE)
                }
                preview = bitmap
                binding.selectionView.setBitmap(bitmap)
            } catch (e: Exception) {
                binding.root.showMessage(e.displayMessage(getString(R.string.page_crop_failed)))
                finish()
            }
        }
    }

    private fun confirm() {
        val rect = binding.selectionView.selectionRect()
        if (rect.width() <= 0f || rect.height() <= 0f) {
            binding.root.showMessage(getString(R.string.crop_too_small))
            return
        }
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(
                RESULT_RECT,
                floatArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            ),
        )
        finish()
    }

    override fun onDestroy() {
        preview?.takeIf { !it.isRecycled }?.recycle()
        preview = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_RECT = "rect"
        private const val EXTRA_QUAD = "quad"
        const val RESULT_RECT = "result_rect"

        /** 预览足够看清题框即可，裁剪精度由原始文件保证。 */
        private const val PREVIEW_MAX_EDGE = 1600

        fun newIntent(
            context: Context,
            path: String,
            rect: FloatArray,
            quad: FloatArray?,
        ): Intent = Intent(context, SelectImageActivity::class.java)
            .putExtra(EXTRA_PATH, path)
            .putExtra(EXTRA_RECT, rect)
            .putExtra(EXTRA_QUAD, quad)

        fun rectOf(data: Intent?): RectF? {
            val values = data?.getFloatArrayExtra(RESULT_RECT) ?: return null
            if (values.size != 4) return null
            return RectF(values[0], values[1], values[2], values[3])
        }
    }
}
