package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * 平板左侧 NavigationRail（8.5）：88dp 宽，图标 24dp + 10sp 标签。
 * 手机（<600dp）不显示，仍用底部 TabBar。
 */
class NavRail(context: Context) : View(context) {

    private val labels = arrayOf("总览", "空间树", "测速", "我的")
    private val icons = arrayOf(Icon.OVERVIEW, Icon.TREE, Icon.GAUGE, Icon.USER)

    var selected: Int = 0
        private set
    private var barProgress = 1f
    private var barAnim: Motion.Animation? = null
    var onSelect: ((Int) -> Unit)? = null

    private var pressed = -1
    private var hovered = -1
    private val itemH = Draw.dp(context, 72f)
    private val topStart = Draw.dp(context, 92f)

    init {
        isClickable = true
        isFocusable = true
    }

    fun setSelected(index: Int) {
        val next = index.coerceIn(0, labels.size - 1)
        if (next == selected) {
            invalidate()
            return
        }
        selected = next
        barAnim?.cancel()
        barProgress = 0f
        barAnim = Motion.indicatorSpring(0f, 1f, { v ->
            barProgress = v
            postInvalidateOnAnimation()
        })
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(p.panel)
        val line = Draw.stroke(p.line, 1f * d)
        canvas.drawLine(w - 0.5f * d, 0f, w - 0.5f * d, h, line)

        // 品牌标记
        val size = 34f * d
        val mark = RectF((w - size) / 2f, 20f * d, (w + size) / 2f, 20f * d + size)
        val grad = Paint(Paint.ANTI_ALIAS_FLAG)
        grad.shader = LinearGradient(mark.left, mark.top, mark.right, mark.bottom,
                intArrayOf(p.brand, p.brand2), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(mark, 10f * d, 10f * d, grad)
        grad.shader = null
        grad.color = 0xFFFFFFFF.toInt()
        val tile = size * 0.19f
        val gap = size * 0.055f
        val ox = mark.left + size * 0.20f
        val oy = mark.top + size * 0.20f
        canvas.drawRoundRect(ox, oy, ox + tile * 2 + gap, oy + tile, 2f * d, 2f * d, grad)
        canvas.drawRoundRect(ox, oy + tile + gap, ox + tile, oy + tile * 2 + gap, 2f * d, 2f * d, grad)
        canvas.drawRoundRect(ox + tile + gap, oy + tile + gap,
                ox + tile * 2 + gap, oy + tile * 2 + gap, 2f * d, 2f * d, grad)

        for (i in labels.indices) {
            val top = topStart + i * itemH
            val item = RectF(8f * d, top + 6f * d, w - 8f * d, top + itemH - 6f * d)
            val active = i == selected
            val hot = i == hovered || i == pressed
            if (active) {
                canvas.drawRoundRect(item, 16f * d, 16f * d, Draw.fill(p.brandSoft))
                val barH = 28f * d * barProgress
                val bar = RectF(4f * d, item.centerY() - barH / 2f,
                        7f * d, item.centerY() + barH / 2f)
                canvas.drawRoundRect(bar, 2f * d, 2f * d, Draw.fill(p.brand))
            } else if (hot) {
                canvas.drawRoundRect(item, 16f * d, 16f * d, Draw.fill(Theme.alpha(p.panelSunken, 220)))
            }
            val cx = w / 2f
            val iconY = item.centerY() - 9f * d
            Draw.icon(canvas, icons[i], cx, iconY, 24f * d,
                    if (active) p.brand else p.muted, 2f * d)
            val tp = Draw.text(context, 10f, if (active) p.brand else p.muted,
                    medium = active, align = Paint.Align.CENTER)
            canvas.drawText(labels[i], cx, item.bottom - 10f * d, tp)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (y < topStart) return -1
        val i = ((y - topStart) / itemH).toInt()
        return if (i in labels.indices) i else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = indexAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed >= 0 && indexAt(event.x, event.y) != pressed) {
                    pressed = -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val idx = indexAt(event.x, event.y)
                val was = pressed
                pressed = -1
                invalidate()
                if (idx >= 0 && idx == was) onSelect?.invoke(idx)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val idx = indexAt(event.x, event.y)
                if (idx != hovered) {
                    hovered = idx
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hovered = -1
                invalidate()
                return true
            }
        }
        return super.onHoverEvent(event)
    }
}
