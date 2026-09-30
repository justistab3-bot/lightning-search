package com.heikeji.phonesearch.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.heikeji.phonesearch.ui.common.dp
import kotlin.math.abs

/**
 * 裁剪 / 旋转视图：自己绘制位图并管理裁剪框，避免 ImageView + 覆盖层两套坐标系同步的问题。
 *
 * 交互：拖动框内平移、拖动四角缩放；裁剪框始终被约束在图片显示区域内。
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class Handle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private var source: Bitmap? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val cropRect = RectF()
    private val imageBounds = RectF()

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x59FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = context.dp(4).toFloat()
        strokeCap = Paint.Cap.ROUND
    }

    private var activeHandle = Handle.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val touchSlop = context.dp(30).toFloat()
    private val minCropSize = context.dp(72).toFloat()
    private val handleLength = context.dp(22).toFloat()

    fun setBitmap(bitmap: Bitmap) {
        source?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
        source = bitmap
        updateImageMatrix()
        invalidate()
    }

    fun currentBitmap(): Bitmap? = source

    /** 顺时针旋转 90 度（负值为逆时针）。 */
    fun rotate(degrees: Int) {
        val current = source ?: return
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
        if (rotated !== current) current.recycle()
        source = rotated
        updateImageMatrix()
        invalidate()
    }

    /** 当前裁剪框对应的位图坐标（已裁剪到图片范围内）。 */
    fun cropRectInBitmap(): Rect {
        val bitmap = source ?: return Rect()
        val rect = RectF(cropRect)
        inverseMatrix.mapRect(rect)
        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bitmap.height)
        return Rect(left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = source ?: return
        canvas.drawBitmap(bitmap, imageMatrix, null)

        // 裁剪框外的四块遮罩
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, dimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, dimPaint)

        // 三分构图网格
        for (index in 1..2) {
            val x = cropRect.left + cropRect.width() * index / 3f
            val y = cropRect.top + cropRect.height() * index / 3f
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
        }

        canvas.drawRect(cropRect, borderPaint)
        drawHandles(canvas)
    }

    private fun drawHandles(canvas: Canvas) {
        val len = handleLength
        // 左上
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left + len, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.top, cropRect.left, cropRect.top + len, handlePaint)
        // 右上
        canvas.drawLine(cropRect.right - len, cropRect.top, cropRect.right, cropRect.top, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.top, cropRect.right, cropRect.top + len, handlePaint)
        // 左下
        canvas.drawLine(cropRect.left, cropRect.bottom - len, cropRect.left, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.left, cropRect.bottom, cropRect.left + len, cropRect.bottom, handlePaint)
        // 右下
        canvas.drawLine(cropRect.right - len, cropRect.bottom, cropRect.right, cropRect.bottom, handlePaint)
        canvas.drawLine(cropRect.right, cropRect.bottom - len, cropRect.right, cropRect.bottom, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (source == null) return false
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitTest(x, y)
                lastX = x
                lastY = y
                parent?.requestDisallowInterceptTouchEvent(activeHandle != Handle.NONE)
                return activeHandle != Handle.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == Handle.NONE) return false
                applyDrag(x - lastX, y - lastY)
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = Handle.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): Handle = when {
        near(x, y, cropRect.left, cropRect.top) -> Handle.TOP_LEFT
        near(x, y, cropRect.right, cropRect.top) -> Handle.TOP_RIGHT
        near(x, y, cropRect.left, cropRect.bottom) -> Handle.BOTTOM_LEFT
        near(x, y, cropRect.right, cropRect.bottom) -> Handle.BOTTOM_RIGHT
        cropRect.contains(x, y) -> Handle.MOVE
        else -> Handle.NONE
    }

    private fun near(x: Float, y: Float, targetX: Float, targetY: Float): Boolean =
        abs(x - targetX) <= touchSlop && abs(y - targetY) <= touchSlop

    private fun applyDrag(dx: Float, dy: Float) {
        when (activeHandle) {
            Handle.MOVE -> {
                val limitedX = dx.coerceIn(
                    imageBounds.left - cropRect.left,
                    imageBounds.right - cropRect.right,
                )
                val limitedY = dy.coerceIn(
                    imageBounds.top - cropRect.top,
                    imageBounds.bottom - cropRect.bottom,
                )
                cropRect.offset(limitedX, limitedY)
            }

            Handle.TOP_LEFT -> {
                cropRect.left = (cropRect.left + dx)
                    .coerceIn(imageBounds.left, cropRect.right - minCropSize)
                cropRect.top = (cropRect.top + dy)
                    .coerceIn(imageBounds.top, cropRect.bottom - minCropSize)
            }

            Handle.TOP_RIGHT -> {
                cropRect.right = (cropRect.right + dx)
                    .coerceIn(cropRect.left + minCropSize, imageBounds.right)
                cropRect.top = (cropRect.top + dy)
                    .coerceIn(imageBounds.top, cropRect.bottom - minCropSize)
            }

            Handle.BOTTOM_LEFT -> {
                cropRect.left = (cropRect.left + dx)
                    .coerceIn(imageBounds.left, cropRect.right - minCropSize)
                cropRect.bottom = (cropRect.bottom + dy)
                    .coerceIn(cropRect.top + minCropSize, imageBounds.bottom)
            }

            Handle.BOTTOM_RIGHT -> {
                cropRect.right = (cropRect.right + dx)
                    .coerceIn(cropRect.left + minCropSize, imageBounds.right)
                cropRect.bottom = (cropRect.bottom + dy)
                    .coerceIn(cropRect.top + minCropSize, imageBounds.bottom)
            }

            Handle.NONE -> Unit
        }
    }

    private fun updateImageMatrix() {
        val bitmap = source ?: return
        if (width == 0 || height == 0) return

        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f

        imageMatrix.reset()
        imageMatrix.postScale(scale, scale)
        imageMatrix.postTranslate(dx, dy)
        imageMatrix.invert(inverseMatrix)

        imageBounds.set(
            dx,
            dy,
            dx + bitmap.width * scale,
            dy + bitmap.height * scale,
        )

        val inset = minOf(imageBounds.width(), imageBounds.height()) * 0.05f
        cropRect.set(
            imageBounds.left + inset,
            imageBounds.top + inset,
            imageBounds.right - inset,
            imageBounds.bottom - inset,
        )
    }
}
