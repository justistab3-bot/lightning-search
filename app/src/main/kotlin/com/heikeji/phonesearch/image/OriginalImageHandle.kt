package com.heikeji.phonesearch.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.heikeji.phonesearch.net.ApiException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.protocol.model.SearchMode
import java.io.File

/**
 * 原图句柄，对应原 `Q0.c`。
 *
 * 持有**原始临时文件**、EXIF 方向、原始尺寸，以及预处理后的整图 JPEG。
 * 原图文件在上传后不能立刻删除——后续预览、题框高亮和框选重搜都要用它。
 *
 * 上传参数按模式区分（交接文档 §3.2）：
 *
 * | 模式 | 最长边 | 质量 |
 * |---|---:|---:|
 * | 普通单题 | 1600 | 88 |
 * | 整页搜题 | 2400 | 92 |
 *
 * 框选裁剪另走 [cropToJpeg]：最长边 1600 / 质量 92，且**从原始文件区域解码**，
 * 不从预览图二次裁剪。
 */
class OriginalImageHandle private constructor(
    val file: File,
    val exifOrientation: Int,
    /** 原始文件像素尺寸（未摆正）。 */
    val rawWidth: Int,
    val rawHeight: Int,
    /** 摆正后、也就是用户所见图片的尺寸。 */
    val displayWidth: Int,
    val displayHeight: Int,
    /** 预处理后的整图 JPEG。 */
    val uploadJpeg: ByteArray,
) {

    /** 上传 JPEG 的像素尺寸——服务端会拿它和 `picture.width/height` 比对。 */
    val uploadWidth: Int get() = displayWidth
    val uploadHeight: Int get() = displayHeight

    /** 预览用位图（最长边约 900），调用方负责回收。 */
    fun previewBitmap(maxEdge: Int = ProtocolProfile.IMAGE_PREVIEW_MAX_EDGE): Bitmap =
        QuestionImageProcessor.decodePreview(file, maxEdge)

    /**
     * 把用户所见图片上的归一化框选区域裁成上传 JPEG。
     *
     * @param displayRect 归一化坐标 `[0,1]`，基于**摆正后**的图片
     * @return 最长边 1600 / 质量 92 的 JPEG
     */
    fun cropToJpeg(displayRect: RectF): ByteArray {
        validate(displayRect)

        // 归一化 -> 显示像素
        val leftPx = displayRect.left * displayWidth
        val topPx = displayRect.top * displayHeight
        val rightPx = displayRect.right * displayWidth
        val bottomPx = displayRect.bottom * displayHeight

        // 显示坐标 -> 原始文件坐标（EXIF 逆映射）
        val inverse = Matrix()
        if (!ImageOrientation.matrix(exifOrientation, rawWidth, rawHeight).invert(inverse)) {
            throw ApiException("图片方向无法还原，请重新拍摄")
        }
        val mapped = RectF(leftPx, topPx, rightPx, bottomPx)
        inverse.mapRect(mapped)

        // left/top 向下取整，right/bottom 向上取整，至少保留 1 像素
        val left = Math.floor(mapped.left.toDouble()).toInt().coerceIn(0, rawWidth - 1)
        val top = Math.floor(mapped.top.toDouble()).toInt().coerceIn(0, rawHeight - 1)
        val right = Math.ceil(mapped.right.toDouble()).toInt().coerceIn(left + 1, rawWidth)
        val bottom = Math.ceil(mapped.bottom.toDouble()).toInt().coerceIn(top + 1, rawHeight)

        val region = decodeRegion(Rect(left, top, right, bottom))
        val oriented = try {
            ImageOrientation.apply(region, exifOrientation)
        } catch (e: OutOfMemoryError) {
            if (!region.isRecycled) region.recycle()
            throw ApiException("内存不足，请框选更小的范围", 0, e)
        }

        try {
            return QuestionImageProcessor.encode(
                bitmap = oriented,
                maxEdge = ProtocolProfile.IMAGE_CROP_OUTPUT_MAX_EDGE,
                quality = ProtocolProfile.IMAGE_CROP_JPEG_QUALITY,
            )
        } finally {
            if (!oriented.isRecycled) oriented.recycle()
        }
    }

    /** 删除原始临时文件。 */
    fun close() {
        runCatching { if (file.exists()) file.delete() }
    }

    // ------------------------------------------------------------------ 内部

    private fun validate(rect: RectF) {
        if (!rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite()
        ) {
            throw ApiException("框选区域无效，请重新框选")
        }
        if (rect.left < 0f || rect.top < 0f || rect.right > 1f || rect.bottom > 1f) {
            throw ApiException("框选区域超出图片范围，请重新框选")
        }
        if (rect.width() <= 0f || rect.height() <= 0f) {
            throw ApiException("框选区域太小，请重新框选")
        }
    }

    private fun decodeRegion(rect: Rect): Bitmap {
        var sample = 1
        while (maxOf(rect.width(), rect.height()) / (sample * 2) >=
            ProtocolProfile.IMAGE_CROP_OUTPUT_MAX_EDGE
        ) {
            sample *= 2
        }
        val decoder = try {
            BitmapRegionDecoder.newInstance(file.path, false)
        } catch (e: Exception) {
            throw ApiException("图片无法局部解码，请重新拍摄", 0, e)
        }
        try {
            return try {
                decoder.decodeRegion(
                    rect,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                ) ?: throw ApiException("框选区域解码失败，请重新框选")
            } catch (e: OutOfMemoryError) {
                throw ApiException("内存不足，请框选更小的范围", 0, e)
            }
        } finally {
            runCatching { decoder.recycle() }
        }
    }

    companion object {

        /** 解码后允许的最大像素数（约 64 MB / ARGB_8888），超过就多采样一档。 */
        private const val MAX_DECODE_PIXELS = 16_000_000L

        /**
         * 接管 [source]（调用方不再负责删除），生成预处理后的整图 JPEG。
         *
         * @throws ApiException 文件缺失、过大或无法解码
         */
        fun prepare(source: File, mode: SearchMode): OriginalImageHandle {
            if (!source.isFile) throw ApiException("图片文件不存在，请重新拍摄")
            if (source.length() > ProtocolProfile.IMAGE_MAX_INPUT_BYTES) {
                throw ApiException("图片过大，请重新拍摄或裁剪后再试")
            }

            val bounds = readBounds(source)
            val exif = try {
                ExifInterface(source)
            } catch (e: Exception) {
                null
            }
            val orientation = ImageOrientation.readOrientation(exif)
            val display = ImageOrientation.displaySize(orientation, bounds[0], bounds[1])

            val targetEdge = when (mode) {
                SearchMode.PAGE -> ProtocolProfile.IMAGE_PAGE_OUTPUT_MAX_EDGE
                else -> ProtocolProfile.IMAGE_OUTPUT_MAX_EDGE
            }
            val quality = when (mode) {
                SearchMode.PAGE -> ProtocolProfile.IMAGE_PAGE_JPEG_QUALITY
                else -> ProtocolProfile.IMAGE_JPEG_QUALITY
            }

            val decoded = decodeForTarget(source, bounds, targetEdge)
            val oriented = try {
                ImageOrientation.apply(decoded, orientation)
            } catch (e: OutOfMemoryError) {
                if (!decoded.isRecycled) decoded.recycle()
                throw ApiException("内存不足，请重启应用后重试", 0, e)
            }

            val upload = try {
                QuestionImageProcessor.encode(oriented, targetEdge, quality)
            } finally {
                if (!oriented.isRecycled) oriented.recycle()
            }

            // 上传 JPEG 的真实尺寸可能因取整与显示尺寸差 1 像素，以实际编码结果为准。
            val uploadBounds = readBounds(upload)
            return OriginalImageHandle(
                file = source,
                exifOrientation = orientation,
                rawWidth = bounds[0],
                rawHeight = bounds[1],
                displayWidth = uploadBounds[0].takeIf { it > 0 } ?: display[0],
                displayHeight = uploadBounds[1].takeIf { it > 0 } ?: display[1],
                uploadJpeg = upload,
            )
        }

        /** 采样解码到「不小于 [targetEdge]，但不超过内存上限」。 */
        private fun decodeForTarget(source: File, bounds: IntArray, targetEdge: Int): Bitmap {
            val longest = maxOf(bounds[0], bounds[1])
            var sample = 1
            while (longest / (sample * 2) >= targetEdge) sample *= 2
            while ((bounds[0].toLong() / sample) * (bounds[1].toLong() / sample) >
                MAX_DECODE_PIXELS
            ) {
                sample *= 2
            }
            return decodeFile(source, sample) ?: throw ApiException("图片解码失败，请重新拍摄")
        }

        private fun decodeFile(file: File, sample: Int): Bitmap? = try {
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: OutOfMemoryError) {
            throw ApiException("内存不足，请重启应用后重试", 0, e)
        }

        private fun readBounds(file: File): IntArray {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            if (options.outWidth < 1 || options.outHeight < 1) {
                throw ApiException("无法识别这张图片，请重新拍摄")
            }
            return intArrayOf(options.outWidth, options.outHeight)
        }

        private fun readBounds(jpeg: ByteArray): IntArray {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            return intArrayOf(options.outWidth, options.outHeight)
        }
    }
}
