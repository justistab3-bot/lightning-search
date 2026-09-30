package com.heikeji.phonesearch.ui.crop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.databinding.ActivityCropBinding
import com.heikeji.phonesearch.image.QuestionImageProcessor
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.displayMessage
import com.heikeji.phonesearch.ui.common.showMessage
import com.heikeji.phonesearch.ui.result.ResultActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 裁剪与旋转页。
 *
 * 取代了原来的「图片预览」：拍完直接在这里裁剪/旋转，确认后编码上传用 JPEG 并进入结果页。
 * 相机开了「预览翻转」时，同一旋转也应用到这里，保证看到的和拍到的是一致的。
 */
class CropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCropBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarPadding(horizontal = true)

        val path = intent.getStringExtra(EXTRA_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            finish()
            return
        }
        val extraRotation = intent.getIntExtra(EXTRA_ROTATION, 0)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.rotateLeftButton.setOnClickListener { binding.cropView.rotate(-90) }
        binding.rotateRightButton.setOnClickListener { binding.cropView.rotate(90) }
        binding.confirmButton.setOnClickListener { confirm(file) }

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    QuestionImageProcessor.decodeForEditing(file)
                }
                binding.cropView.setBitmap(bitmap)
                // 相机页设了预览旋转时，成片应用同一角度，保证「看到的」就是「拍到的」。
                if (extraRotation != 0) binding.cropView.rotate(extraRotation)
            } catch (e: Exception) {
                binding.root.showMessage(e.displayMessage(getString(R.string.error_image_failed)))
                finish()
            } finally {
                binding.progress.visibility = View.GONE
            }
        }
    }

    private fun confirm(sourceFile: File) {
        val bitmap = binding.cropView.currentBitmap() ?: return
        val rect = binding.cropView.cropRectInBitmap()
        if (rect.width() < MIN_CROP_PX || rect.height() < MIN_CROP_PX) {
            binding.root.showMessage(getString(R.string.crop_too_small))
            return
        }

        binding.confirmButton.isEnabled = false
        binding.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    // createBitmap 在整图裁剪时会直接返回原对象，这里避免误回收视图持有的位图。
                    val cropped = Bitmap.createBitmap(
                        bitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height(),
                    )
                    try {
                        QuestionImageProcessor.encodeForUpload(cropped)
                    } finally {
                        if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
                    }
                }
                val target = File(cacheDir, "question-${UUID.randomUUID()}.jpg")
                withContext(Dispatchers.IO) { target.writeBytes(bytes) }
                sourceFile.delete()

                startActivity(ResultActivity.newIntent(this@CropActivity, target.absolutePath))
                finish()
            } catch (e: Exception) {
                binding.confirmButton.isEnabled = true
                binding.progress.visibility = View.GONE
                binding.root.showMessage(e.displayMessage(getString(R.string.error_image_failed)))
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "capture_path"
        private const val EXTRA_ROTATION = "extra_rotation"
        private const val MIN_CROP_PX = 32

        fun newIntent(
            context: Context,
            capturePath: String,
            extraRotation: Int = 0,
        ): Intent = Intent(context, CropActivity::class.java)
            .putExtra(EXTRA_PATH, capturePath)
            .putExtra(EXTRA_ROTATION, extraRotation)
    }
}
