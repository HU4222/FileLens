package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.spacelens.app.core.Fmt

/** 多选模式顶条：已选 N 项 · 合计 X ｜ 取消。M5 在此接预览 / 删除。 */
class SelectionBar(context: Context) : View(context) {

    private val labels = arrayOf("已选", "合计")
    private var count = 0
    private var bytes = 0L
    private val cancelRect = RectF()
    private val deleteRect = RectF()
    private val allRect = RectF()
    private var pressed = false
    private var delPressed = false
    private var allPressed = false
    var onCancel: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null

    fun setState(count: Int, bytes: Long) {
        this.count = count
        this.bytes = bytes
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, Draw.dp(context, 46f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val d = resources.displayMetrics.density
        val rect = RectF(Draw.dp(context, 18f), Draw.dp(context, 4f),
                width - Draw.dp(context, 18f), height - Draw.dp(context, 4f))
        Draw.card(canvas, rect, Radius.SM * d, p.brandSoft, p.line)
        canvas.drawCircle(rect.left + 18f * d, rect.centerY(), 5f * d, Draw.fill(p.brand))
        val tp = Draw.text(context, 11f, p.text2, medium = true)
        canvas.drawText("已选 $count 项", rect.left + 32f * d, rect.centerY() + 4f * d, tp)
        val bp = Draw.text(context, 11f, p.muted)
        canvas.drawText("合计 " + Fmt.bytes(bytes), rect.left + 122f * d, rect.centerY() + 4f * d, bp)

        deleteRect.set(rect.right - 112f * d, rect.centerY() - 14f * d,
                rect.right - 64f * d, rect.centerY() + 14f * d)
        allRect.set(rect.right - 168f * d, rect.centerY() - 14f * d,
                rect.right - 118f * d, rect.centerY() + 14f * d)
        canvas.drawRoundRect(allRect, 14f * d, 14f * d, Draw.fill(p.panelSunken))
        val ap = Draw.text(context, 10f, p.text2, medium = true, align = Paint.Align.CENTER)
        canvas.drawText("全选", allRect.centerX(), allRect.centerY() + 3.6f * d, ap)
        cancelRect.set(rect.right - 58f * d, rect.centerY() - 14f * d,
                rect.right - 10f * d, rect.centerY() + 14f * d)
        canvas.drawRoundRect(deleteRect, 14f * d, 14f * d,
                Draw.fill(Theme.alpha(p.danger, 30)))
        val dp = Draw.text(context, 10f, p.danger, medium = true, align = Paint.Align.CENTER)
        canvas.drawText("删除", deleteRect.centerX(), deleteRect.centerY() + 3.6f * d, dp)
        canvas.drawRoundRect(cancelRect, 14f * d, 14f * d,
                Draw.fill(if (pressed) Theme.alpha(p.brand, 22) else p.panel))
        val cp = Draw.text(context, 10f, p.brand, medium = true, align = Paint.Align.CENTER)
        canvas.drawText("取消", cancelRect.centerX(), cancelRect.centerY() + 3.6f * d, cp)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = cancelRect.contains(event.x, event.y)
                delPressed = deleteRect.contains(event.x, event.y)
                allPressed = allRect.contains(event.x, event.y)
                invalidate()
                return pressed || delPressed
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed && !cancelRect.contains(event.x, event.y)) {
                    pressed = false
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val cancel = cancelRect.contains(event.x, event.y)
                val del = deleteRect.contains(event.x, event.y)
                val all = allRect.contains(event.x, event.y)
                pressed = false
                delPressed = false
                invalidate()
                if (cancel) onCancel?.invoke() else if (del) onDelete?.invoke() else if (all) onSelectAll?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
