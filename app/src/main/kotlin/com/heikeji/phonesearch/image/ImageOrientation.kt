package com.heikeji.phonesearch.image

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface

/**
 * EXIF 方向处理的唯一出处。
 *
 * 正向矩阵把**原始文件坐标**映射到**用户所见坐标**；取逆即可把用户在预览上框选的
 * 归一化区域映射回原始文件，交给 `BitmapRegionDecoder` 局部解码。
 *
 * 之前这段逻辑散在 [QuestionImageProcessor] 里，现在抽出来，保证「预览怎么摆正的」
 * 和「裁剪怎么逆映射的」用的是同一个矩阵。
 */
object ImageOrientation {

    /**
     * 构造把原始坐标映射到摆正后坐标的矩阵。
     *
     * 与 `Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)` 的行为一致：
     * 先按方向旋转/镜像，再整体平移回非负坐标。
     */
    fun matrix(orientation: Int, rawWidth: Int, rawHeight: Int): Matrix {
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
            else -> return matrix
        }
        val bounds = RectF(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        return matrix
    }

    /** 把位图摆正；不需要变换时原样返回。 */
    fun apply(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return source
        }
        val matrix = matrix(orientation, source.width, source.height)
        if (matrix.isIdentity) return source
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /** 摆正后的显示尺寸（宽高在 90/270 度时会互换）。 */
    fun displaySize(orientation: Int, rawWidth: Int, rawHeight: Int): IntArray {
        val bounds = RectF(0f, 0f, rawWidth.toFloat(), rawHeight.toFloat())
        matrix(orientation, rawWidth, rawHeight).mapRect(bounds)
        return intArrayOf(
            Math.round(bounds.width()).coerceAtLeast(1),
            Math.round(bounds.height()).coerceAtLeast(1),
        )
    }

    /** 读 EXIF 方向；失败时按正常处理。 */
    fun readOrientation(exif: ExifInterface?): Int = try {
        exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
}
