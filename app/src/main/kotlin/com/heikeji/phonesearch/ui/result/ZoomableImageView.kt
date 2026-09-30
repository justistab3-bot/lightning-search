package com.heikeji.phonesearch.ui.result

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * 可捏合缩放 / 双击放大 / 拖动查看的 ImageView。
 *
 * 只在需要放大原图时使用，避免引入第三方图片库。
 * 缩放范围 1x（适配屏幕）到 6x，拖动时把图片约束在可视范围内。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val currentMatrix = Matrix()
    private var scale = 1f
    private var baseScale = 1f

    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                val factor = target / scale
                scale = target
                currentMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clamp()
                imageMatrix = currentMatrix
                return true
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) {
                    resetZoom()
                } else {
                    val target = 2.5f
                    val factor = target / scale
                    scale = target
                    currentMatrix.postScale(factor, factor, e.x, e.y)
                    clamp()
                    imageMatrix = currentMatrix
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        },
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { applyBaseMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyBaseMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activePointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0 && !scaleDetector.isInProgress && scale > 1.01f) {
                    val x = event.getX(index)
                    val y = event.getY(index)
                    currentMatrix.postTranslate(x - lastX, y - lastY)
                    lastX = x
                    lastY = y
                    clamp()
                    imageMatrix = currentMatrix
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newIndex)
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (scale <= 1.01f) resetZoom()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun resetZoom() {
        scale = 1f
        applyBaseMatrix()
    }

    /** 把图片按 fit-center 铺到当前 View 尺寸。 */
    private fun applyBaseMatrix() {
        val drawable = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || drawableWidth <= 0f || drawableHeight <= 0f) {
            return
        }
        baseScale = minOf(viewWidth / drawableWidth, viewHeight / drawableHeight)
        scale = 1f
        currentMatrix.reset()
        currentMatrix.postScale(baseScale, baseScale)
        currentMatrix.postTranslate(
            (viewWidth - drawableWidth * baseScale) / 2f,
            (viewHeight - drawableHeight * baseScale) / 2f,
        )
        imageMatrix = currentMatrix
    }

    /** 约束平移，使图片始终填满或居中于可视区域。 */
    private fun clamp() {
        val drawable = drawable ?: return
        currentMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val scaledWidth = drawable.intrinsicWidth * matrixValues[Matrix.MSCALE_X]
        val scaledHeight = drawable.intrinsicHeight * matrixValues[Matrix.MSCALE_Y]
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val deltaX = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) / 2f - transX
        } else {
            transX.coerceIn(viewWidth - scaledWidth, 0f) - transX
        }
        val deltaY = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) / 2f - transY
        } else {
            transY.coerceIn(viewHeight - scaledHeight, 0f) - transY
        }
        if (abs(deltaX) > 0.01f || abs(deltaY) > 0.01f) {
            currentMatrix.postTranslate(deltaX, deltaY)
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
    }
}
