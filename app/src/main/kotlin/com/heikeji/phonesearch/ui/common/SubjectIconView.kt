package com.heikeji.phonesearch.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import com.heikeji.phonesearch.R
import com.heikeji.phonesearch.protocol.render.SubjectIcons

/**
 * 学科图标：圆角方块底 + 该学科的线稿图标（物理是锥形瓶、化学是试管、数学是根号……）。
 *
 * 图标路径来自 `:protocol` 的 [SubjectIcons]，与答案页 HTML 里的内联 SVG 共用同一份定义，
 * 避免原生和网页两处各画一套导致不一致。
 */
class SubjectIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val renderMatrix = Matrix()
    private val transformed = Path()

    private var sourcePath: Path? = null

    var subject: String = ""
        set(value) {
            field = value
            sourcePath = runCatching {
                PathParser.createPathFromPathData(SubjectIcons.pathData(value))
            }.getOrNull()
            backgroundPaint.color = ContextCompat.getColor(context, SubjectStyle.of(value).colorRes)
            invalidate()
        }

    init {
        backgroundPaint.color = ContextCompat.getColor(context, R.color.subject_default)
        iconPaint.color = ContextCompat.getColor(context, R.color.ivory)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = minOf(w, h) * 0.32f
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, backgroundPaint)

        val path = sourcePath ?: return
        val iconSize = minOf(w, h) * 0.62f
        val scale = iconSize / VIEWPORT
        renderMatrix.reset()
        renderMatrix.postScale(scale, scale)
        renderMatrix.postTranslate((w - iconSize) / 2f, (h - iconSize) / 2f)

        transformed.reset()
        path.transform(renderMatrix, transformed)
        iconPaint.strokeWidth = scale * 2f
        canvas.drawPath(transformed, iconPaint)
    }

    private companion object {
        const val VIEWPORT = 24f
    }
}
