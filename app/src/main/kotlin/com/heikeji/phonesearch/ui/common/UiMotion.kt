package com.heikeji.phonesearch.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.heikeji.phonesearch.R
import kotlin.math.abs

/** Small, interruptible motion. Never tween layout sizes or animate streamed text updates. */
object UiMotion {
    fun enabled(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }.getOrDefault(true)

    /** Install once after click handlers exist; preserve native touch, long-press and focus handling. */
    fun bindTree(view: View) {
        if (view is WebView || view is EditText) return
        if (view is Button || view is ImageButton || view is Chip ||
            (view.isClickable && (view is MaterialCardView || view is LinearLayout ||
                (view is TextView && !view.isTextSelectable)))
        ) bindControl(view)
        if (view is ViewPager2) bindPager(view)
        if (view is RecyclerView && view.getTag(R.id.motion_recycler_bound) == null) {
            view.setTag(R.id.motion_recycler_bound, true)
            view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(child: View) {
                    child.post { if (child.isAttachedToWindow) bindTree(child) }
                }
                override fun onChildViewDetachedFromWindow(child: View) = Unit
            })
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) bindTree(view.getChildAt(index))
        }
    }

    fun bindControl(view: View) {
        if (view.getTag(R.id.motion_feedback_bound) != null) return
        view.setTag(R.id.motion_feedback_bound, true)
        // Let View dispatch its own drawable states. A zero-duration signal retargets physical
        // springs without installing a touch listener or restarting from a fixed scale.
        view.stateListAnimator = StateListAnimator().apply {
            fun signal(states: IntArray, target: Float, bounce: Boolean) {
                addState(states, ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 0
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationStart(animation: Animator) {
                            spring(view, DynamicAnimation.SCALE_X, target, bounce)
                            spring(view, DynamicAnimation.SCALE_Y, target, bounce)
                        }
                    })
                })
            }
            signal(intArrayOf(-android.R.attr.state_enabled), 1f, false)
            signal(intArrayOf(android.R.attr.state_pressed), 0.97f, false)
            signal(intArrayOf(), 1f, true)
        }
        view.stateListAnimator?.jumpToCurrentState()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.stateListAnimator?.jumpToCurrentState()
                v.scaleX = 1f
                v.scaleY = 1f
            }
        })
    }

    /** Directly tracks the swipe; does not fight the pager's gesture or scroll animation. */
    private fun bindPager(pager: ViewPager2) {
        val motionEnabled = enabled(pager.context)
        pager.setPageTransformer { page, position ->
            val distance = if (motionEnabled) abs(position).coerceAtMost(1f) else 0f
            page.alpha = 1f - 0.12f * distance
            page.scaleX = 1f - 0.015f * distance
            page.scaleY = 1f - 0.015f * distance
        }
    }

    /** A genuine spring retains velocity when rapidly retargeted. */
    fun rotate(view: View, degrees: Float) {
        spring(view, DynamicAnimation.ROTATION, degrees)
    }

    /** One arrival per completed state change, never per SSE chunk or RecyclerView bind. */
    fun reveal(view: View) {
        if (!enabled(view.context) || view.visibility != View.VISIBLE) return
        val state = state(view)
        cancelFade(state)
        view.alpha = 0f
        view.translationY = view.context.dp(8f)
        spring(view, DynamicAnimation.TRANSLATION_Y, 0f)
        fade(view, state, 1f, 160)
    }

    /** Paired expand/collapse; repeated renders of the same target leave animation untouched. */
    fun visible(view: View, show: Boolean, animate: Boolean = true) {
        val state = state(view)
        if (state.targetVisible == show) return
        state.targetVisible = show
        cancelFade(state)
        val wasVisible = view.visibility == View.VISIBLE
        if (!animate || !enabled(view.context) || (!wasVisible && !show)) {
            state.springs.values.forEach { it.cancel() }
            view.visibility = if (show) View.VISIBLE else View.GONE
            reset(view)
            return
        }
        if (show) {
            if (!wasVisible) {
                view.alpha = 0f
                view.translationY = view.context.dp(6f)
            }
            view.visibility = View.VISIBLE
            spring(view, DynamicAnimation.TRANSLATION_Y, 0f)
            fade(view, state, 1f, 160)
        } else {
            // Remove from layout once at the end, without changing the height every frame.
            spring(view, DynamicAnimation.TRANSLATION_Y, view.context.dp(4f), bounce = false)
            fade(view, state, 0f, 120) {
                view.visibility = View.GONE
                state.springs.values.forEach { it.cancel() }
                reset(view)
            }
        }
    }

    /** Logical visibility remains usable while an outgoing surface finishes fading. */
    fun isShown(view: View): Boolean =
        (view.getTag(R.id.motion_state) as? MotionState)?.targetVisible ?: (view.visibility == View.VISIBLE)

    private fun spring(view: View, property: DynamicAnimation.ViewProperty, target: Float, bounce: Boolean = true) {
        val state = state(view)
        if (state.springs[property]?.isRunning != true && property.getValue(view) == target) return
        if (!enabled(view.context)) {
            state.springs[property]?.cancel()
            property.setValue(view, target)
            return
        }
        val animation = state.springs.getOrPut(property) { SpringAnimation(view, property) }
        if (animation.spring == null) animation.spring = SpringForce(target)
        val scale = property === DynamicAnimation.SCALE_X || property === DynamicAnimation.SCALE_Y
        if (scale) animation.setMinimumVisibleChange(0.0005f)
        animation.spring.stiffness = if (scale && !bounce) 2400f else 850f
        animation.spring.dampingRatio = if (!bounce) 1f else if (scale) 0.58f else 0.72f
        animation.animateToFinalPosition(target)
    }

    private fun fade(view: View, state: MotionState, target: Float, duration: Long, end: () -> Unit = {}) {
        val animator = ObjectAnimator.ofFloat(view, View.ALPHA, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (state.fade !== animation) return
                    state.fade = null
                    end()
                }
            })
        }
        state.fade = animator
        animator.start()
    }

    private fun cancelFade(state: MotionState) {
        val previous = state.fade
        state.fade = null // A cancelled fade must not hide a surface that just reopened.
        previous?.cancel()
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun state(view: View): MotionState {
        (view.getTag(R.id.motion_state) as? MotionState)?.let { return it }
        val state = MotionState()
        view.setTag(R.id.motion_state, state)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                cancelFade(state)
                state.springs.forEach { (property, animation) ->
                    animation.cancel()
                    property.setValue(v, animation.spring.finalPosition)
                }
                state.targetVisible?.let { v.visibility = if (it) View.VISIBLE else View.GONE }
                reset(v)
            }
        })
        return state
    }

    private class MotionState {
        val springs = mutableMapOf<DynamicAnimation.ViewProperty, SpringAnimation>()
        var fade: ObjectAnimator? = null
        var targetVisible: Boolean? = null
    }
}
