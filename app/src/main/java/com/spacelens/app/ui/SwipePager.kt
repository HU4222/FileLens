package com.spacelens.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 四页横向容器：拖动 1:1 跟手，松手按速度/位置吸附。
 * 页面全部常驻，切换只改 scrollX，避免 Fragment 生命周期与重建开销。
 */
class SwipePager(context: Context) : ViewGroup(context) {

    var onProgress: ((Float) -> Unit)? = null
    var onPageChanged: ((Int) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null

    var currentPage: Int = 0
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat() * 1.5f
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0
    private var dragging = false
    private var anim: Motion.Animation? = null
    private var velocity: VelocityTracker? = null

    init {
        isChildrenDrawingOrderEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setCurrent(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, childCount - 1)
        val to = target * width
        anim?.cancel()
        showAllPages()
        if (!animate || width == 0) {
            scrollX = to
            currentPage = target
            updatePageTransforms()
            applySettledVisibility()
            onPageChanged?.invoke(target)
            onProgress?.invoke(target.toFloat())
            return
        }
        val from = scrollX.toFloat()
        val duration = settleDuration(from, to.toFloat(), 0f)
        anim = Motion.tween(duration, Ease::inOutCubic, { t ->
            scrollX = (from + (to - from) * t).roundToInt()
            onProgress?.invoke(scrollX / width.toFloat())
            updatePageTransforms()
        }, {
            currentPage = target
            onProgress?.invoke(target.toFloat())
            updatePageTransforms()
            applySettledVisibility()
            onPageChanged?.invoke(target)
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h)
        }
        if (scrollX != currentPage * w) {
            scrollX = currentPage * w
        }
        updatePageTransforms()
        if (anim == null && !dragging && scrollX == currentPage * w) {
            applySettledVisibility()
        }
    }

    private fun horizontalDrag(dx: Float, dy: Float): Boolean =
            abs(dx) > slop && abs(dx) > abs(dy) * 1.2f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downScroll = scrollX
                dragging = false
                anim?.cancel()
                showAllPages()
            }
            MotionEvent.ACTION_MOVE -> {
                if (horizontalDrag(ev.x - downX, ev.y - downY)) {
                    dragging = true
                    onDragStateChanged?.invoke(true)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downScroll = scrollX
                anim?.cancel()
                showAllPages()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = downX - ev.x
                var target = downScroll + dx
                val max = (childCount - 1) * width.toFloat()
                if (target < 0f) target *= 0.35f
                if (target > max) target = max + (target - max) * 0.35f
                scrollX = target.roundToInt()
                updatePageTransforms()
                val progress = if (width > 0) scrollX.toFloat() / width else 0f
                onProgress?.invoke(progress.coerceIn(0f, (childCount - 1).toFloat()))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var page = if (width > 0) (scrollX.toFloat() / width).roundToInt() else currentPage
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                if (abs(vx) > minFling) {
                    page = if (vx < 0) currentPage + 1 else currentPage - 1
                }
                page = page.coerceIn(0, childCount - 1)
                settleTo(page, if (ev.actionMasked == MotionEvent.ACTION_UP) vx else 0f)
                velocity?.recycle()
                velocity = null
                if (dragging) {
                    dragging = false
                    onDragStateChanged?.invoke(false)
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun settleTo(page: Int, velocityX: Float) {
        val from = scrollX.toFloat()
        val to = (page * width).toFloat()
        val duration = settleDuration(from, to, velocityX)
        anim?.cancel()
        showAllPages()
        anim = Motion.tween(duration, Ease::inOutCubic, { t ->
            scrollX = (from + (to - from) * t).roundToInt()
            onProgress?.invoke(scrollX / width.toFloat())
            updatePageTransforms()
        }, {
            currentPage = page
            onProgress?.invoke(page.toFloat())
            updatePageTransforms()
            applySettledVisibility()
            onPageChanged?.invoke(page)
        })
    }

    /** 距离越短、速度越快，收口越短；限制在 120..320ms。 */
    private fun settleDuration(from: Float, to: Float, velocityX: Float): Int {
        val distance = abs(to - from)
        val speed = maxOf(abs(velocityX), 800f)
        return (distance / speed * 1000f).roundToInt().coerceIn(120, 320)
    }

    /** 动画/拖动期间所有页可见；停稳后只让当前页参与绘制，避免旧页继续 invalidate 造成残影。 */
    private fun showAllPages() {
        for (i in 0 until childCount) getChildAt(i).visibility = VISIBLE
    }

    private fun applySettledVisibility() {
        for (i in 0 until childCount) {
            getChildAt(i).visibility = if (i == currentPage) VISIBLE else INVISIBLE
        }
    }

    /** 页面切换：只做 6dp 上移 + 淡入，不缩放、不做 3D。 */
    private fun updatePageTransforms() {
        if (width <= 0) return
        val progress = scrollX.toFloat() / width
        val d = resources.displayMetrics.density
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val delta = i - progress
            val ad = abs(delta).coerceIn(0f, 1f)
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationY = 6f * d * ad
            child.alpha = (1f - 0.25f * ad).coerceIn(0f, 1f)
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        velocity?.recycle()
        velocity = null
        super.onDetachedFromWindow()
    }
}
