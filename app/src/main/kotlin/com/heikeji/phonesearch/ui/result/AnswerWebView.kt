package com.heikeji.phonesearch.ui.result

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs

/**
 * 答案页 WebView：解决「竖向滑动被 ViewPager2 当成翻页」的手势冲突。
 *
 * 做法是在手势早期判断主方向：竖向为主时对父容器
 * `requestDisallowInterceptTouchEvent(true)`，把这一整个手势锁给 WebView 滚动；
 * 横向为主时不做拦截，交给 ViewPager2 翻页。
 *
 * 之前直接用普通 WebView，ViewPager2 会在纵向拖动时抢先拦截，导致解析被划走。
 */
class AnswerWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : WebView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var verticalGesture = false

    /** 竖向滚动回调：正数为向下浏览内容（手指上滑）。 */
    var onVerticalScroll: ((Int) -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
    }

    /**
     * 直接重写 onScrollChanged 而不是用 `setOnScrollChangeListener`：
     * WebView 的滚动由原生层驱动，走监听器并不总是可靠。
     */
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val delta = t - oldt
        if (delta != 0) onVerticalScroll?.invoke(delta)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                verticalGesture = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!verticalGesture) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    // 阈值取一半 touchSlop，抢在 ViewPager2 判定为翻页之前锁定方向。
                    if (maxOf(dx, dy) > touchSlop * 0.5f && dy > dx) {
                        verticalGesture = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = super.onGenericMotionEvent(event)
}
