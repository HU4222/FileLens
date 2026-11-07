package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.NodeStore
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** 环形视图：当前层一级子项同心圆环，点击扇区钻入。 */
class RingView(context: Context) : View(context) {

    var onDrill: ((Int) -> Unit)? = null
    private var store: NodeStore? = null
    private var root = 0
    private val children = ArrayList<NodeStore.NodeInfo>()
    private val d = resources.displayMetrics.density
    private var pressed = -1

    fun setTree(store: NodeStore, root: Int) {
        this.store = store
        this.root = root
        children.clear()
        children.addAll(store.childrenInfo(root).filter { it.size > 0 }.sortedByDescending { it.size })
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        canvas.drawColor(p.bg)
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) * 0.42f
        val inner = outer * 0.56f
        val box = RectF(cx - outer, cy - outer, cx + outer, cy + outer)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outer - inner
            color = p.panel
        }
        canvas.drawCircle(cx, cy, (outer + inner) / 2f, track)
        if (children.isEmpty()) return
        val total = children.sumOf { it.size }.coerceAtLeast(1L)
        var angle = -90f
        for (i in children.indices) {
            val sweep = 360f * children[i].size / total
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = outer - inner
                color = Theme.alpha(p.category[children[i].category.coerceIn(0, 6)], 220)
                strokeCap = Paint.Cap.BUTT
            }
            canvas.drawArc(box, angle, sweep - 1.2f, false, paint)
            if (sweep > 10f) {
                val mid = Math.toRadians((angle + sweep / 2).toDouble())
                val rr = (outer + inner) / 2f
                val tx = cx + (rr * kotlin.math.cos(mid)).toFloat()
                val ty = cy + (rr * kotlin.math.sin(mid)).toFloat()
                canvas.drawCircle(tx, ty, 2.4f * d, Draw.fill(p.panel))
                if (sweep > 24f) {
                    val tp = Draw.text(context, 9f, p.text2, align = Paint.Align.CENTER)
                    canvas.drawText(Draw.ellipsize(children[i].name, tp, 72f * d), tx, ty - 4f * d, tp)
                    val sp = Draw.text(context, 8f, p.muted, align = Paint.Align.CENTER)
                    canvas.drawText((100f * children[i].size / total).toInt().toString() + "%", tx, ty + 8f * d, sp)
                }
            }
            angle += sweep
        }
        val value = Draw.text(context, 26f, p.text, medium = true, align = Paint.Align.CENTER)
        val size = store?.sizeOf(root) ?: 0L
        canvas.drawText(Fmt.bytes(size), cx, cy + 2f * d, value)
        val name = Draw.text(context, 11f, p.muted, align = Paint.Align.CENTER)
        canvas.drawText((store?.nameOf(root) ?: "") + " · " + children.size + " 项", cx, cy + 24f * d, name)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val h = hit(event.x, event.y)
                if (h >= 0 && h == pressed && h < children.size) onDrill?.invoke(children[h].index)
                pressed = -1
                return true
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) * 0.42f
        val inner = outer * 0.56f
        val r = hypot(x - cx, y - cy)
        if (r < inner || r > outer || children.isEmpty()) return -1
        var a = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        if (a < -90f) a += 360f
        val total = children.sumOf { it.size }.coerceAtLeast(1L)
        var start = -90f
        for (i in children.indices) {
            val sweep = 360f * children[i].size / total
            if (a >= start && a < start + sweep) return i
            start += sweep
        }
        return -1
    }
}
