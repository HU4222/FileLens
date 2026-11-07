package com.spacelens.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** 底部胶囊页签：Canvas 自绘，指示器跟手 + 弹簧收口。 */
class TabBar(context: Context) : View(context) {

    private val labels = arrayOf("空间", "搜索", "清理", "传输")
    private val icons = arrayOf(Icon.TREE, Icon.SEARCH, Icon.TRASH, Icon.SHIELD)

    var selected: Int = 0
        private set

    var onSelect: ((Int) -> Unit)? = null

    private var progress = 0f
    private var pressIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var springAnim: Motion.Animation? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSpring: Runnable? = null

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Draw.dp(context, 66f).toInt())
    }

    private fun contentLeft(): Float = Draw.dp(context, 14f)
    private fun contentRight(): Float = width - Draw.dp(context, 14f)
    private fun itemWidth(): Float = (contentRight() - contentLeft()) / labels.size

    fun setPage(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, labels.size - 1)
        selected = target
        if (!animate) {
            springAnim?.cancel()
            progress = target.toFloat()
            invalidate()
        } else {
            pendingSpring?.let { handler.removeCallbacks(it) }
            val job = Runnable { springTo(target.toFloat()) }
            pendingSpring = job
            handler.postDelayed(job, 60L)
        }
    }

    /** 跟手：pager 拖动时实时移动指示器。 */
    fun setSwipeProgress(p: Float) {
        pendingSpring?.let { handler.removeCallbacks(it) }
        pendingSpring = null
        springAnim?.cancel()
        progress = p.coerceIn(0f, (labels.size - 1).toFloat())
        invalidate()
    }

    fun finishSwipe(index: Int) {
        pendingSpring?.let { handler.removeCallbacks(it) }
        pendingSpring = null
        selected = index.coerceIn(0, labels.size - 1)
        springTo(selected.toFloat())
    }

    private fun springTo(target: Float) {
        springAnim?.cancel()
        val start = progress
        springAnim = Motion.tween(
                MotionDurations.INDICATOR,
                Ease::softOut,
                update = {
                    progress = start + (target - start) * it
                    postInvalidateOnAnimation()
                },
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val p = Theme.palette
        val d = resources.displayMetrics.density
        val top = 5f * d
        val bottom = height - 7f * d

        canvas.drawLine(0f, 0.5f * d, width.toFloat(), 0.5f * d,
                Draw.stroke(p.line, 1f * d))

        // FileLens 风格：选中项底部一条短靛蓝指示条
        val iw = itemWidth()
        val indW = 18f * d
        val indLeft = contentLeft() + progress * iw + (iw - indW) / 2f
        val indTop = height - 5f * d
        canvas.drawRoundRect(RectF(indLeft, indTop, indLeft + indW, indTop + 3f * d),
                1.5f * d, 1.5f * d, Draw.fill(p.brand))

        for (i in labels.indices) {
            val cx = contentLeft() + iw * (i + 0.5f)
            val active = i == selected
            val color = when {
                active -> p.brand
                pressIndex == i -> p.text2
                else -> p.muted
            }
            if (pressIndex == i && !active) {
                canvas.drawCircle(cx, top + (bottom - top) * 0.34f, 17f * d,
                        Draw.fill(Theme.alpha(p.brandSoft, 150)))
            }
            Draw.icon(canvas, icons[i], cx, top + (bottom - top) * 0.34f,
                    21f * d, color, 2f * d)
            val tp = Draw.text(context, 10f, color, medium = active)
            val label = labels[i]
            canvas.drawText(label, cx - tp.measureText(label) / 2f,
                    bottom - 11f * d, tp)
        }
    }

    private fun indexAt(x: Float): Int {
        val iw = itemWidth()
        if (iw <= 0f) return -1
        val i = ((x - contentLeft()) / iw).toInt()
        return if (i in labels.indices) i else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                pressIndex = indexAt(event.x)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                    moved = true
                    pressIndex = -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val idx = indexAt(event.x)
                pressIndex = -1
                if (!moved && idx >= 0 && idx == indexAt(downX)) {
                    onSelect?.invoke(idx)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
