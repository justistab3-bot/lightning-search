package com.heikeji.phonesearch.ui.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.protocol.ProtocolProfile
import com.heikeji.phonesearch.ui.common.dp
import kotlin.math.abs
import kotlin.math.min

/**
 * 原图预览 + 缩放平移 + 手动框选，对应原 `Q0.b`。
 *
 * - 双指缩放 1x..8x，单指平移（在框外拖动时）
 * - 拖动框角调整范围，框内拖动整体移动
 * - 可高亮服务端返回的当前题框
 *
 * 对外暴露的 [selectionRect] 是**归一化坐标** `[0,1]`，基于用户所见的摆正图片；
 * 逆映射到原始文件由 [com.heikeji.phonesearch.image.OriginalImageHandle] 负责。
 */
class SelectionImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private enum class Drag { NONE, MOVE, PAN, LEFT_TOP, RIGHT_TOP, RIGHT_BOTTOM, LEFT_BOTTOM }

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SCRIM }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = ContextCompat.getColor(context, R.color.terracotta)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.terracotta)
    }
    private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = Color.WHITE
    }

    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val inverse = Matrix()

    private var scale = 1f
    private var minScale = 1f
    private var translateX = 0f
    private var translateY = 0f

    /** 归一化框选区域。 */
    private val rect = RectF(
        ProtocolProfile.QUAD_DEFAULT_RECT[0],
        ProtocolProfile.QUAD_DEFAULT_RECT[1],
        ProtocolProfile.QUAD_DEFAULT_RECT[2],
        ProtocolProfile.QUAD_DEFAULT_RECT[3],
    )

    /** 服务端题框（归一化），仅用于高亮。 */
    private var quadNormalized: RectF? = null

    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    private val cornerRadius = context.dp(6f)
    private val grabSlop = context.dp(28f)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(minScale, MAX_SCALE)
                val factor = target / scale
                if (factor == 1f) return true
                scale = target
                // 以捏合中心为锚点缩放
                translateX = detector.focusX - (detector.focusX - translateX) * factor
                translateY = detector.focusY - (detector.focusY - translateY) * factor
                clampTranslation()
                invalidate()
                return true
            }
        },
    )

    var onSelectionChanged: ((RectF) -> Unit)? = null

    fun setBitmap(value: Bitmap) {
        bitmap = value
        scale = 1f
        translateX = 0f
        translateY = 0f
        post { fitToView() }
        invalidate()
    }

    fun setSelection(normalized: RectF) {
        rect.set(
            normalized.left.coerceIn(0f, 1f),
            normalized.top.coerceIn(0f, 1f),
            normalized.right.coerceIn(0f, 1f),
            normalized.bottom.coerceIn(0f, 1f),
        )
        invalidate()
        onSelectionChanged?.invoke(RectF(rect))
    }

    /** 高亮服务端题框（归一化）。 */
    fun setHighlightQuad(normalized: RectF?) {
        quadNormalized = normalized
        invalidate()
    }

    fun selectionRect(): RectF = RectF(rect)

    fun resetView() {
        scale = 1f
        post { fitToView() }
        invalidate()
    }

    // ------------------------------------------------------------------ 布局与矩阵

    private fun fitToView() {
        val source = bitmap ?: return
        if (width <= 0 || height <= 0) return
        minScale = min(
            width.toFloat() / source.width,
            height.toFloat() / source.height,
        )
        if (scale < minScale) scale = minScale
        val shown = minScale * (scale / minScale)
        translateX = (width - source.width * shown) / 2f
        translateY = (height - source.height * shown) / 2f
        rebuildMatrix()
    }

    private fun rebuildMatrix() {
        val source = bitmap ?: return
        val effective = minScale * scale
        matrix.reset()
        matrix.postScale(effective, effective)
        matrix.postTranslate(translateX, translateY)
        matrix.invert(inverse)
    }

    private fun clampTranslation() {
        val source = bitmap ?: return
        val effective = minScale * scale
        val shownW = source.width * effective
        val shownH = source.height * effective
        translateX = if (shownW <= width) {
            (width - shownW) / 2f
        } else {
            translateX.coerceIn(width - shownW, 0f)
        }
        translateY = if (shownH <= height) {
            (height - shownH) / 2f
        } else {
            translateY.coerceIn(height - shownH, 0f)
        }
    }

    private fun toNormalized(x: Float, y: Float): PointF {
        val source = bitmap ?: return PointF()
        val points = floatArrayOf(x, y)
        inverse.mapPoints(points)
        return PointF(points[0] / source.width, points[1] / source.height)
    }

    private fun toView(nx: Float, ny: Float): PointF {
        val source = bitmap ?: return PointF()
        val points = floatArrayOf(nx * source.width, ny * source.height)
        matrix.mapPoints(points)
        return PointF(points[0], points[1])
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        val source = bitmap ?: return
        rebuildMatrix()
        canvas.drawBitmap(source, matrix, bitmapPaint)

        // 框外压暗（画四块遮罩，避免用到 API 26 的 clipOutRect）
        val left = toView(rect.left, rect.top)
        val right = toView(rect.right, rect.bottom)
        val viewRect = RectF(
            min(left.x, right.x),
            min(left.y, right.y),
            maxOf(left.x, right.x),
            maxOf(left.y, right.y),
        )
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, viewRect.top, scrimPaint)
        canvas.drawRect(0f, viewRect.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, viewRect.top, viewRect.left, viewRect.bottom, scrimPaint)
        canvas.drawRect(viewRect.right, viewRect.top, w, viewRect.bottom, scrimPaint)

        // 服务端题框高亮
        quadNormalized?.let { quad ->
            val q1 = toView(quad.left, quad.top)
            val q2 = toView(quad.right, quad.bottom)
            canvas.drawRect(
                min(q1.x, q2.x),
                min(q1.y, q2.y),
                maxOf(q1.x, q2.x),
                maxOf(q1.y, q2.y),
                quadPaint,
            )
        }

        canvas.drawRect(viewRect, borderPaint)
        for (corner in cornersOf(viewRect)) {
            canvas.drawCircle(corner.x, corner.y, cornerRadius, handlePaint)
        }
    }

    private fun cornersOf(viewRect: RectF): List<PointF> = listOf(
        PointF(viewRect.left, viewRect.top),
        PointF(viewRect.right, viewRect.top),
        PointF(viewRect.right, viewRect.bottom),
        PointF(viewRect.left, viewRect.bottom),
    )

    // ------------------------------------------------------------------ 手势

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastX = x
                lastY = y
                drag = hitTest(x, y)
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                when (drag) {
                    Drag.PAN -> {
                        translateX += dx
                        translateY += dy
                        clampTranslation()
                        invalidate()
                    }

                    Drag.MOVE -> moveSelection(x - downX, y - downY)
                    Drag.LEFT_TOP -> resizeSelection(left = x, top = y)
                    Drag.RIGHT_TOP -> resizeSelection(right = x, top = y)
                    Drag.RIGHT_BOTTOM -> resizeSelection(right = x, bottom = y)
                    Drag.LEFT_BOTTOM -> resizeSelection(left = x, bottom = y)
                    Drag.NONE -> Unit
                }
                lastX = x
                lastY = y
                downX = x - dx
                downY = y - dy
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Drag {
        val left = toView(rect.left, rect.top)
        val right = toView(rect.right, rect.bottom)
        val viewRect = RectF(
            min(left.x, right.x),
            min(left.y, right.y),
            maxOf(left.x, right.x),
            maxOf(left.y, right.y),
        )
        cornersOf(viewRect).forEachIndexed { index, corner ->
            if (abs(x - corner.x) <= grabSlop && abs(y - corner.y) <= grabSlop) {
                return when (index) {
                    0 -> Drag.LEFT_TOP
                    1 -> Drag.RIGHT_TOP
                    2 -> Drag.RIGHT_BOTTOM
                    else -> Drag.LEFT_BOTTOM
                }
            }
        }
        return if (viewRect.contains(x, y)) Drag.MOVE else Drag.PAN
    }

    private fun moveSelection(dxView: Float, dyView: Float) {
        val source = bitmap ?: return
        val effective = minScale * scale
        if (effective <= 0f) return
        val dx = dxView / (source.width * effective)
        val dy = dyView / (source.height * effective)
        val width = rect.width()
        val height = rect.height()
        val left = (rect.left + dx).coerceIn(0f, 1f - width)
        val top = (rect.top + dy).coerceIn(0f, 1f - height)
        rect.set(left, top, left + width, top + height)
        notifyChanged()
    }

    private fun resizeSelection(
        left: Float? = null,
        top: Float? = null,
        right: Float? = null,
        bottom: Float? = null,
    ) {
        val current = RectF(rect)
        left?.let { current.left = toNormalized(it, 0f).x.coerceIn(0f, current.right - MIN_SIZE) }
        right?.let { current.right = toNormalized(it, 0f).x.coerceIn(current.left + MIN_SIZE, 1f) }
        top?.let { current.top = toNormalized(0f, it).y.coerceIn(0f, current.bottom - MIN_SIZE) }
        bottom?.let {
            current.bottom = toNormalized(0f, it).y.coerceIn(current.top + MIN_SIZE, 1f)
        }
        rect.set(current)
        notifyChanged()
    }

    private fun notifyChanged() {
        invalidate()
        onSelectionChanged?.invoke(RectF(rect))
    }

    private companion object {
        const val MAX_SCALE = 8f
        const val MIN_SIZE = 0.02f
        const val SCRIM = 0x99000000.toInt()
    }
}
