package com.heikeji.phonesearch.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.heikeji.phonesearch.R
import kotlin.math.min

/**
 * 答案序号：数字外面套一个正圆。
 *
 * 之前用 shape drawable + TextView，被 TabView 拉伸成了椭圆。这里改成自绘并强制 onMeasure
 * 返回正方形，无论父容器怎么约束都保证是正圆。
 *
 * 选中态通过 `android:duplicateParentState="true"` 从 TabView 继承。
 */
class PageNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(STROKE_DP)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = context.sp(TEXT_SP)
    }

    var number: Int = 1
        set(value) {
            field = value
            invalidate()
        }

    private val active: Boolean
        get() = drawableState.contains(android.R.attr.state_selected)

    /** 强制正方形：这是「不变成椭圆」的关键。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dp(DIAMETER_DP)
        setMeasuredDimension(size, size)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        if (diameter <= 0f) return
        val radius = diameter / 2f
        val inset = strokePaint.strokeWidth / 2f

        if (active) {
            fillPaint.color = ContextCompat.getColor(context, R.color.terracotta)
            canvas.drawCircle(radius, radius, radius - inset, fillPaint)
            textPaint.color = ContextCompat.getColor(context, R.color.ivory)
        } else {
            strokePaint.color = ContextCompat.getColor(context, R.color.ring_warm)
            canvas.drawCircle(radius, radius, radius - inset, strokePaint)
            textPaint.color = ContextCompat.getColor(context, R.color.olive_gray)
        }

        val baseline = radius - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(number.toString(), radius, baseline, textPaint)
    }

    private companion object {
        const val DIAMETER_DP = 30
        const val STROKE_DP = 1.5f
        const val TEXT_SP = 13f
    }
}
