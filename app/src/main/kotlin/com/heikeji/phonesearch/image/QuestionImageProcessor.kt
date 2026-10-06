package com.heikeji.phonesearch.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.heikeji.phonesearch.net.ApiException
import com.heikeji.phonesearch.protocol.core.NetConfig
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 题目图片处理。
 *
 * 上传前的字节校验沿用原实现：必须以 FF D8 开头。
 * 方向处理统一走 [ImageOrientation]，与框选裁剪的逆映射共用同一个矩阵。
 */
object QuestionImageProcessor {

    /** 编辑用位图（最长边约 2048，已按 EXIF 摆正）。 */
    fun decodeForEditing(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) {
            throw ApiException("无法识别这张图片，请重新拍摄")
        }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize >
            NetConfig.IMAGE_DECODE_MAX_EDGE
        ) {
            sampleSize *= 2
        }

        val decoded = try {
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: OutOfMemoryError) {
            throw ApiException("内存不足，请重启应用后重试", 0, e)
        } ?: throw ApiException("图片解码失败，请重新拍摄")

        val orientation = try {
            ImageOrientation.readOrientation(ExifInterface(file))
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return ImageOrientation.apply(decoded, orientation)
    }

    /** 预览位图（最长边约 [maxEdge]，已按 EXIF 摆正）。 */
    fun decodePreview(file: File, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) {
            throw ApiException("无法识别这张图片，请重新拍摄")
        }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxEdge) {
            sampleSize *= 2
        }
        val decoded = try {
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: OutOfMemoryError) {
            throw ApiException("内存不足，请重启应用后重试", 0, e)
        } ?: throw ApiException("图片解码失败，请重新拍摄")

        val orientation = try {
            ImageOrientation.readOrientation(ExifInterface(file))
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return ImageOrientation.apply(decoded, orientation)
    }

    /**
     * 解码成「已按 EXIF 摆正、且最长边不低于 [minEdge]」的位图。
     *
     * 整页模式用：采样率取「结果仍不小于 [minEdge]」的最小值，
     * 保证上传图有足够分辨率让服务端定位题框。
     */
    fun decodeOriented(file: File, extraRotationDegrees: Int = 0, minEdge: Int = 0): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) {
            throw ApiException("无法识别这张图片，请重新拍摄")
        }

        var sampleSize = 1
        if (minEdge > 0) {
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / (sampleSize * 2) >= minEdge) sampleSize *= 2
        }

        val decoded = try {
            BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (e: OutOfMemoryError) {
            throw ApiException("内存不足，请重启应用后重试", 0, e)
        } ?: throw ApiException("图片解码失败，请重新拍摄")

        val orientation = try {
            ImageOrientation.readOrientation(ExifInterface(file))
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val oriented = ImageOrientation.apply(decoded, orientation)
        if (extraRotationDegrees % 360 == 0) return oriented

        val matrix = Matrix().apply { postRotate(extraRotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(
            oriented, 0, 0, oriented.width, oriented.height, matrix, true,
        )
        if (rotated !== oriented && !oriented.isRecycled) oriented.recycle()
        return rotated
    }

    /** 普通单题的上传 JPEG（最长边 1600 / 质量 88）。 */
    fun encodeForUpload(bitmap: Bitmap): ByteArray = encode(
        bitmap = bitmap,
        maxEdge = NetConfig.IMAGE_OUTPUT_MAX_EDGE,
        quality = NetConfig.IMAGE_JPEG_QUALITY,
    )

    /**
     * 按指定参数编码成上传用 JPEG。
     *
     * **不会回收传入的位图**（调用方可能仍在使用它），只回收自己创建的中间缩放位图。
     */
    fun encode(bitmap: Bitmap, maxEdge: Int, quality: Int): ByteArray {
        val scaled = scaleDown(bitmap, maxEdge)
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw ApiException("图片处理失败")
            }
            val bytes = out.toByteArray()
            if (bytes.size < 2 ||
                (bytes[0].toInt() and 0xFF) != 0xFF ||
                (bytes[1].toInt() and 0xFF) != 0xD8
            ) {
                throw ApiException("图片处理失败")
            }
            return bytes
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            maxOf(1, Math.round(source.width * ratio)),
            maxOf(1, Math.round(source.height * ratio)),
            true,
        )
    }
}
