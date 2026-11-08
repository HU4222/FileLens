package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 底部胶囊页签：Canvas 自绘，指示器跟手 + 弹簧收口。 */
class TabBar(context: Context) : View(context) {

    private val labels = arrayOf("空间", "搜索", "清理", "传输")
    private val icons = arrayOf(Icon.TREE, Icon.SEARCH, Icon.TRASH, Icon.SHIELD)

    var selected: Int = 0
        private set

    var onSelect: ((Int) -> Unit)? = null

    private var progress = 0f
    private var pressIndex = -1
    private var pressScale = 1f
    private var pressAlpha = 1f
    private var pressAnim: Motion.Animation? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 62dp 胶囊 + 18dp 离底，视图本身占 80dp；胶囊由 onDraw 画在底部。
        setMeasuredDimension(w, Draw.dp(context, 80f).toInt())
    }

    private fun contentLeft(): Float = Draw.dp(context, 24f)
    private fun contentRight(): Float = width - Draw.dp(context, 24f)
    private fun itemWidth(): Float = (contentRight() - contentLeft()) / labels.size

    fun setPage(index: Int, animate: Boolean) {
        val target = index.coerceIn(0, labels.size - 1)
        selected = target
        if (!animate) {
            progress = target.toFloat()
            invalidate()
        }
        // animate=true 时不自己动：等 SwipePager 的 onProgress 逐帧喂进度。
    }

    /** 跟手：pager 拖动/收口时实时移动指示器。 */
    fun setSwipeProgress(p: Float) {
        progress = p.coerceIn(0f, (labels.size - 1).toFloat())
        invalidate()
    }

    fun finishSwipe(index: Int) {
        selected = index.coerceIn(0, labels.size - 1)
        // pager 的最后一帧 onProgress 已到终点，这里只兜底对齐，不再补动画。
        progress = selected.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        val p = Theme.palette
        val d = resources.displayMetrics.density
        val dark = p.dark
        val capsule = RectF(
                Draw.dp(context, 12f),
                height - Draw.dp(context, 18f + 62f),
                width - Draw.dp(context, 12f),
                height - Draw.dp(context, 18f),
        )
        val radius = 31f * d
        Draw.softShadow(canvas, capsule, radius, d)
        canvas.drawRoundRect(capsule, radius, radius, Draw.fill(Color.argb(31, 0, 0, 0)))
        Glass.draw(canvas, capsule, radius, dark, density = d)

        val iw = itemWidth()
        val selectedColor = 0xFFB7B4FF.toInt()
        val selectedText = 0xFFEEF2FF.toInt()
        for (i in labels.indices) {
            val cx = contentLeft() + iw * (i + 0.5f)
            val k = 1f - abs(progress - i).coerceIn(0f, 1f)
            val pressed = pressIndex == i
            val color = Theme.blend(p.muted, selectedColor, k)
            val textColor = Theme.blend(p.muted, selectedText, k)
            val size = (18f + 4f * k) * d
            val stroke = (1.5f + 0.5f * k) * d
            val alpha = if (pressed) (255 * pressAlpha).toInt() else 255
            canvas.save()
            if (pressed) canvas.scale(pressScale, pressScale, cx, capsule.centerY())
            Draw.icon(canvas, icons[i], cx, capsule.top + 22f * d,
                    size, Theme.alpha(color, alpha), stroke)
            val tp = Draw.text(context, 10f, textColor, medium = k > 0.5f)
            tp.alpha = alpha
            val label = labels[i]
            canvas.drawText(label, cx - tp.measureText(label) / 2f,
                    capsule.bottom - 10f * d, tp)
            canvas.restore()
        }
    }

    private fun pressDown(index: Int) {
        if (index < 0) return
        pressAnim?.cancel()
        pressIndex = index
        pressScale = 0.96f
        pressAlpha = 0.85f
        invalidate()
    }

    private fun releasePress() {
        if (pressIndex < 0) return
        val fromScale = pressScale
        val fromAlpha = pressAlpha
        pressAnim?.cancel()
        pressAnim = Motion.tween(130, Ease::softOut, { t ->
            pressScale = fromScale + (1f - fromScale) * t
            pressAlpha = fromAlpha + (1f - fromAlpha) * t
            invalidate()
        }, {
            pressIndex = -1
            pressScale = 1f
            pressAlpha = 1f
            invalidate()
        })
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
                pressDown(indexAt(event.x))
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (Math.abs(event.x - downX) > slop ||
                                Math.abs(event.y - downY) > slop)) {
                    moved = true
                    releasePress()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val idx = indexAt(event.x)
                if (!moved && idx >= 0 && idx == indexAt(downX)) {
                    onSelect?.invoke(idx)
                }
                releasePress()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releasePress()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
