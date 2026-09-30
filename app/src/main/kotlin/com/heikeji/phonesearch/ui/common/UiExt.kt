package com.heikeji.phonesearch.ui.common

import android.content.Context
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar

/**
 * 给某个 View 加上系统栏内边距。
 *
 * 横屏时系统栏（导航栏 / 刘海）在左右两侧，所以 [horizontal] 为 true 时也把左右内边距算进去，
 * 否则横屏下内容会贴到屏幕边缘。
 */
fun View.applySystemBarPadding(
    top: Boolean = true,
    bottom: Boolean = true,
    horizontal: Boolean = false,
) {
    val initialLeft = paddingLeft
    val initialRight = paddingRight
    val initialTop = paddingTop
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(
            left = initialLeft + if (horizontal) bars.left else 0,
            right = initialRight + if (horizontal) bars.right else 0,
            top = initialTop + if (top) bars.top else 0,
            bottom = initialBottom + if (bottom) bars.bottom else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

fun View.showMessage(message: CharSequence) {
    Snackbar.make(this, message, Snackbar.LENGTH_LONG).show()
}

/** 统一把异常转成可展示文案。 */
fun Throwable.displayMessage(fallback: String = "操作失败，请重试"): String =
    message?.takeIf { it.isNotBlank() } ?: fallback

/** 读取 dp。 */
fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** 读取 dp（小数）。 */
fun Context.dp(value: Float): Float = value * resources.displayMetrics.density

/** 读取 sp。 */
fun Context.sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

/** 屏幕高度的百分比。 */
fun Context.screenHeightFraction(fraction: Float): Int =
    (resources.displayMetrics.heightPixels * fraction).toInt()
