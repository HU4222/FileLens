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
        Glass.draw(canvas, capsule, radius, dark, density = d)

        val iw = itemWidth()
        val selectedIndex = progress.roundToInt().coerceIn(0, labels.size - 1)
        val selectedText = Draw.text(context, 10f, p.text)
        val blockW = max(44f * d, selectedText.measureText(labels[selectedIndex]) + 28f * d)
        val blockCx = contentLeft() + progress * iw + iw / 2f
        val block = RectF(blockCx - blockW / 2f, capsule.centerY() - 22f * d,
                blockCx + blockW / 2f, capsule.centerY() + 22f * d)
        val blockFill = if (dark) Color.argb(71, 139, 135, 255) else Color.argb(36, 79, 70, 229)
        canvas.drawRoundRect(block, 20f * d, 20f * d, Draw.fill(blockFill))
        canvas.drawRoundRect(block, 20f * d, 20f * d,
                Draw.stroke(Color.argb(56, 255, 255, 255), max(1f, 0.5f * d)))

        for (i in labels.indices) {
            val cx = contentLeft() + iw * (i + 0.5f)
            val k = 1f - abs(progress - i).coerceIn(0f, 1f)
            val base = if (pressIndex == i) p.text2 else p.muted
            val color = Theme.blend(base, p.brand, k)
            if (pressIndex == i && k < 0.5f) {
                canvas.drawCircle(cx, capsule.centerY() - 7f * d, 17f * d,
                        Draw.fill(Theme.alpha(p.brandSoft, 150)))
            }
            Draw.icon(canvas, icons[i], cx, capsule.top + 22f * d,
                    20f * d, color, 2f * d)
            val tp = Draw.text(context, 10f, color, medium = k > 0.5f)
            val label = labels[i]
            canvas.drawText(label, cx - tp.measureText(label) / 2f,
                    capsule.bottom - 10f * d, tp)
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
