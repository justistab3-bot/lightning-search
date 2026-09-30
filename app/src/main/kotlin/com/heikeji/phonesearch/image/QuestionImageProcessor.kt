package com.heikeji.phonesearch.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.heikeji.phonesearch.net.ApiException
import com.heikeji.phonesearch.protocol.ProtocolProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 题目图片处理。
 *
 * 流程分两步，中间夹着用户的裁剪/旋转编辑：
 * 1. [decodeForEditing]：把拍摄结果解码成可编辑的位图（采样到最长边约 2048，并按 EXIF 摆正方向）；
 * 2. [encodeForUpload]：把编辑后的位图编码成上传用 JPEG（最长边 1600，质量 88，校验 SOI）。
 *
 * 上传前的字节校验沿用原实现：必须以 FF D8 开头。
 */
object QuestionImageProcessor {

    fun decodeForEditing(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) {
            throw ApiException("无法识别这张图片，请重新拍摄")
        }

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize >
            ProtocolProfile.IMAGE_DECODE_MAX_EDGE
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

        return applyExifOrientation(file, decoded)
    }

    /**
     * 把编辑后的位图编码成上传用 JPEG。
     *
     * **不会回收传入的位图**（调用方可能仍在使用它），只回收自己创建的中间缩放位图。
     */
    fun encodeForUpload(bitmap: Bitmap): ByteArray {
        val scaled = scaleDown(bitmap, ProtocolProfile.IMAGE_OUTPUT_MAX_EDGE)
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(
                    Bitmap.CompressFormat.JPEG,
                    ProtocolProfile.IMAGE_JPEG_QUALITY,
                    out,
                )
            ) {
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

    private fun applyExifOrientation(file: File, source: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
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
