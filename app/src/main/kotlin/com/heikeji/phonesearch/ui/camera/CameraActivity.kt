package com.heikeji.phonesearch.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Surface
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.databinding.ActivityCameraBinding
import com.heikeji.phonesearch.protocol.model.SearchMode
import com.heikeji.phonesearch.ui.common.applySystemBarPadding
import com.heikeji.phonesearch.ui.common.showMessage
import com.heikeji.phonesearch.ui.crop.CropActivity
import com.heikeji.phonesearch.ui.page.PageResultActivity
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 应用内相机（CameraX），只用后置摄像头。
 *
 * ## 关于预览方向
 *
 * 正常情况下 `targetRotation` + PreviewView 自带变换就能得到正确方向，这里也照做了。
 * 但本机的相机 HAL 上报的传感器方向有偏差，预览会整体转 180°——这种情况在应用侧
 * 无论怎么设置 API 都纠正不了。
 *
 * 因此额外提供一个**预览旋转**按钮：在 0° / 90° / 180° / 270° 之间循环并持久化，
 * 出厂默认为 180°（实测本机需要这个值）。PreviewView 被撑成「屏幕长边 × 屏幕长边」
 * 的正方形再旋转，所以任何角度都能铺满可视区域；同一个角度会传给裁剪页应用到成片，
 * 保证「看到的」就是「拍到的」。
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    private var previewRotation = DEFAULT_ROTATION
    private var sourceMode = SearchMode.SINGLE

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val displayManager by lazy {
        getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            syncTargetRotation()
            applyPreviewTransform()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.root.showMessage(getString(R.string.camera_permission_denied))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceMode = SearchMode.fromWire(intent.getIntExtra(EXTRA_MODE, SearchMode.SINGLE.wireValue))
            ?: SearchMode.SINGLE

        binding.topBar.applySystemBarPadding(top = true, bottom = false, horizontal = true)
        binding.bottomBar.applySystemBarPadding(top = false, bottom = true, horizontal = true)

        previewRotation = prefs.getInt(KEY_ROTATION, DEFAULT_ROTATION)
        updateFlipButtonTint()

        // 预览容器尺寸变化（含旋转屏幕）时重新铺满
        binding.previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            applyPreviewTransform(right - left, bottom - top)
        }

        binding.closeButton.setOnClickListener { finish() }
        binding.shutterButton.setOnClickListener { capture() }
        binding.flashButton.setOnClickListener { cycleFlash() }
        binding.flipButton.setOnClickListener { cyclePreviewRotation() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, null)
    }

    override fun onStop() {
        displayManager.unregisterDisplayListener(displayListener)
        super.onStop()
    }

    // ------------------------------------------------------------------ 方向

    /**
     * PreviewView 撑成正方形（边长取容器长边）后按 [previewRotation] 旋转。
     * 这样 0/90/180/270 四种角度都能完整覆盖可视区域，不会露黑边。
     */
    private fun applyPreviewTransform(
        containerWidth: Int = binding.previewContainer.width,
        containerHeight: Int = binding.previewContainer.height,
    ) {
        if (containerWidth <= 0 || containerHeight <= 0) return
        val side = max(containerWidth, containerHeight)

        val params = binding.previewView.layoutParams
        if (params.width != side || params.height != side) {
            params.width = side
            params.height = side
            binding.previewView.layoutParams = params
        }
        binding.previewView.rotation = previewRotation.toFloat()
    }

    private fun cyclePreviewRotation() {
        previewRotation = (previewRotation + 90) % 360
        prefs.edit().putInt(KEY_ROTATION, previewRotation).apply()
        applyPreviewTransform()
        updateFlipButtonTint()
        binding.root.showMessage(getString(R.string.camera_rotate_format, previewRotation))
    }

    private fun updateFlipButtonTint() {
        binding.flipButton.setColorFilter(
            ContextCompat.getColor(
                this,
                if (previewRotation == 0) R.color.white else R.color.terracotta,
            ),
        )
    }

    private fun syncTargetRotation() {
        val rotation = currentRotation()
        previewUseCase?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    /**
     * PreviewView 还没 attach 时 `display` 为 null，必须回退到 WindowManager，
     * 否则会默认成 ROTATION_0，在横屏设备上直接算错方向。
     */
    private fun currentRotation(): Int {
        binding.previewView.display?.rotation?.let { return it }
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.rotation
    }

    // ------------------------------------------------------------------ 相机

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindUseCases(provider)
                } catch (e: Exception) {
                    binding.root.showMessage(getString(R.string.camera_failed))
                    finish()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        val rotation = currentRotation()

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
        previewUseCase = preview

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setTargetRotation(rotation)
            .build()
        imageCapture = capture

        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture)
        } catch (e: Exception) {
            binding.root.showMessage(getString(R.string.camera_failed))
            finish()
        }
    }

    private fun capture() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "capture-${UUID.randomUUID()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        binding.shutterButton.isEnabled = false
        binding.progress.visibility = View.VISIBLE

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    binding.progress.visibility = View.GONE
                    startActivity(
                        CropActivity.newIntent(
                            context = this@CameraActivity,
                            capturePath = file.absolutePath,
                            extraRotation = previewRotation,
                        ),
                    )
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.progress.visibility = View.GONE
                    binding.shutterButton.isEnabled = true
                    binding.root.showMessage(
                        getString(R.string.camera_capture_failed, exception.message.orEmpty()),
                    )
                }
            },
        )
    }

    /** 闪光灯三态循环：自动 -> 开 -> 关。 */
    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        imageCapture?.flashMode = flashMode
        binding.flashButton.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_OFF -> R.drawable.ic_flash_off
                else -> R.drawable.ic_flash_auto
            },
        )
    }

    companion object {
        private const val PREFS_NAME = "camera_prefs"
        private const val KEY_ROTATION = "preview_rotation"
        private const val EXTRA_MODE = "source_mode"

        /**
         * 默认旋转 180°：实测本机预览需要额外转 180° 才是正的（HAL 上报的传感器方向有偏差）。
         * 用户仍可用翻转按钮调整，调整后会覆盖这个默认值。
         */
        private const val DEFAULT_ROTATION = 180

        fun newIntent(context: Context, mode: SearchMode): Intent =
            Intent(context, CameraActivity::class.java)
                .putExtra(EXTRA_MODE, mode.wireValue)
    }
}
