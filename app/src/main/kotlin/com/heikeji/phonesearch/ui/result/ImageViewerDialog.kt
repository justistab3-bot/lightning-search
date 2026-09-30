package com.heikeji.phonesearch.ui.result

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.image.RemoteImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片全屏查看：捏合缩放 / 双击放大 / 拖动。
 *
 * - [show] 直接解码内存里的 JPEG 字节（本地原图，带采样避免 OOM）
 * - [showRemote] 异步下载答案里的远端图片，只走 https
 *
 * 两者都不走 WebView、不碰 file:// 协议。
 */
object ImageViewerDialog {

    private const val MAX_EDGE = 2048

    fun show(activity: Activity, jpeg: ByteArray) {
        val bitmap = decodeSampled(jpeg) ?: return
        val dialog = createDialog(activity)
        dialog.findViewById<ZoomableImageView>(R.id.zoomImage).setImageBitmap(bitmap)
        dialog.setOnDismissListener {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        dialog.show()
    }

    fun showRemote(activity: ComponentActivity, url: String) {
        val dialog = createDialog(activity)
        val image = dialog.findViewById<ZoomableImageView>(R.id.zoomImage)
        val progress = dialog.findViewById<ProgressBar>(R.id.viewerProgress)
        val error = dialog.findViewById<TextView>(R.id.viewerError)
        progress.visibility = View.VISIBLE

        var loaded: Bitmap? = null
        dialog.setOnDismissListener {
            loaded?.takeIf { !it.isRecycled }?.recycle()
        }
        dialog.show()

        activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { RemoteImageLoader.load(url) }
            progress.visibility = View.GONE
            if (!dialog.isShowing) {
                bitmap?.takeIf { !it.isRecycled }?.recycle()
                return@launch
            }
            if (bitmap == null) {
                error.visibility = View.VISIBLE
            } else {
                loaded = bitmap
                image.setImageBitmap(bitmap)
            }
        }
    }

    private fun createDialog(activity: Activity): Dialog =
        Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(R.layout.dialog_image_viewer)
            findViewById<View>(R.id.closeButton).setOnClickListener { dismiss() }
        }

    private fun decodeSampled(jpeg: ByteArray): Bitmap? {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_EDGE) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }
}
