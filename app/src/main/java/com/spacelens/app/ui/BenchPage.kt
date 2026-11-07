package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.cos
import kotlin.math.sin

/** 测速页：仪表盘 + 六项测试卡（M4 接 BenchEngine）。 */
class BenchPage(context: Context) : FrameLayout(context) {

    var onStart: (() -> Unit)? = null

    private val scroll = ScrollView(context)
    private val content = Content(context)
    private var running = false
    private var result: com.spacelens.app.bench.BenchEngine.Result? = null
    private var progressLabel = ""
    private var progressFrac = 0f

    init {
        scroll.isFillViewport = true
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setRunning(running: Boolean) {
        this.running = running
        content.invalidate()
    }

    fun setProgress(label: String, fraction: Float) {
        progressLabel = label
        progressFrac = fraction
        content.invalidate()
    }

    fun setResult(r: com.spacelens.app.bench.BenchEngine.Result) {
        result = r
        running = false
        content.invalidate()
    }

    fun clearResult() {
        result = null
        content.invalidate()
    }

    private inner class Content(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val margin = Breakpoints.metrics(context).marginDp * d
        private val gaugeH = 222f * d
        private val cardH = 96f * d
        private val gap = 10f * d
        private val buttonRect = RectF()
        private var pressed = false

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (8f * d + gaugeH + 14f * d + 22f * d + 3 * cardH + 2 * gap + 16f * d +
                    46f * d + 60f * d).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val p = Theme.palette
            val w = width.toFloat()
            var y = 8f * d

            // 仪表盘
            val gauge = RectF(margin, y, w - margin, y + gaugeH)
            Draw.card(canvas, gauge, Radius.PANEL * d, p.panel, p.line)
            drawGauge(canvas, gauge, p)
            y = gauge.bottom + 14f * d

            val head = Draw.text(context, 13f, p.text2, medium = true)
            canvas.drawText("六项测试", margin + 2f * d, y + 14f * d, head)
            val hint = Draw.text(context, 10f, p.muted, align = Paint.Align.RIGHT)
            val hintText = if (running) "$progressLabel ${(progressFrac * 100).toInt()}%" else "串行执行 · 可中途取消"
            canvas.drawText(hintText, w - margin - 2f * d, y + 14f * d, hint)
            y += 22f * d

            val labels = arrayOf("顺序写", "顺序读", "随机读 4K", "随机写 4K", "元数据", "fsync 延迟")
            val units = arrayOf("MB/s", "MB/s", "IOPS", "IOPS", "files/s", "ms")
            val cols = 2
            val cw = (w - margin * 2 - gap) / cols
            for (i in labels.indices) {
                val col = i % cols
                val row = i / cols
                val left = margin + col * (cw + gap)
                val top = y + row * (cardH + gap)
                val rect = RectF(left, top, left + cw, top + cardH)
                Draw.card(canvas, rect, Radius.PANEL * d, p.panel, p.line)
                val lp = Draw.text(context, 10.5f, p.muted)
                canvas.drawText(labels[i], rect.left + 14f * d, rect.top + 24f * d, lp)
                val r = result
                val text = when (i) {
                    0 -> r?.let { String.format(java.util.Locale.US, "%.0f", it.seqWriteMb) } ?: "—"
                    1 -> r?.let { String.format(java.util.Locale.US, "%.0f", it.seqReadMb) } ?: "—"
                    2 -> r?.let { String.format(java.util.Locale.US, "%.0f", it.randReadIops) } ?: "—"
                    3 -> r?.let { String.format(java.util.Locale.US, "%.0f", it.randWriteIops) } ?: "—"
                    4 -> r?.let { String.format(java.util.Locale.US, "%.0f", it.metaFiles) } ?: "—"
                    else -> r?.let { String.format(java.util.Locale.US, "%.1f", it.fsyncAvgMs) } ?: "—"
                }
                val vp = Draw.text(context, 24f, p.text, medium = true)
                canvas.drawText(text, rect.left + 14f * d, rect.top + 58f * d, vp)
                val up = Draw.text(context, 9f, p.muted)
                canvas.drawText(units[i], rect.left + 14f * d, rect.top + 76f * d, up)
                val badge = RectF(rect.right - 50f * d, rect.top + 12f * d, rect.right - 12f * d, rect.top + 30f * d)
                canvas.drawRoundRect(badge, 9f * d, 9f * d, Draw.fill(p.panelSunken))
                val bp = Draw.text(context, 8.5f, if (result != null) p.brand else p.muted, align = Paint.Align.CENTER)
                canvas.drawText(if (result != null) result!!.grade else "待测", badge.centerX(), badge.centerY() + 3f * d, bp)
            }
            y += 3 * cardH + 2 * gap + 16f * d

            buttonRect.set(margin, y, w - margin, y + 46f * d)
            canvas.drawRoundRect(buttonRect, 23f * d, 23f * d,
                    Draw.fill(if (pressed) Theme.blend(p.brand, p.brand2, 0.35f) else p.brand))
            val bt = Draw.text(context, 13f, 0xFFFFFFFF.toInt(), medium = true, align = Paint.Align.CENTER)
            canvas.drawText(if (running) "测速中…" else "开始测速", buttonRect.centerX(), buttonRect.centerY() + 4.5f * d, bt)
            y = buttonRect.bottom + 20f * d

            val disclaimer = Draw.text(context, 9.5f, p.muted, align = Paint.Align.LEFT)
            canvas.drawText(if (result != null) "最弱项：${result!!.weakest} · 写入 ${com.spacelens.app.core.Fmt.bytes(result!!.writeBytes)} · QD32 读 ${String.format(java.util.Locale.US, "%.0f", result!!.randReadQd32Iops)} IOPS" else "实际速度受主控、温度、剩余空间与文件系统影响，仅供参考。", margin + 2f * d, y, disclaimer)
            val note = Draw.text(context, 9.5f, p.muted, align = Paint.Align.LEFT)
            canvas.drawText((if (result?.cacheNote?.isNotEmpty() == true) result!!.cacheNote else "测试文件只写在应用专属目录，结束即删。") + " · 实际速度仅供参考。", margin + 2f * d, y + 17f * d, note)
        }

        private fun drawGauge(canvas: Canvas, r: RectF, p: Palette) {
            val cx = r.centerX()
            val cy = r.top + 142f * d
            val radius = 74f * d
            val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            val track = Draw.stroke(p.line, 9f * d)
            canvas.drawArc(arc, 160f, 220f, false, track)

            // 刻度
            val tick = Draw.stroke(p.lineStrong, 1.6f * d)
            for (i in 0..20) {
                val a = Math.toRadians((160f + 220f * i / 20f).toDouble())
                val inner = radius - 15f * d
                val outer = radius - (if (i % 5 == 0) 7f * d else 11f * d)
                canvas.drawLine(
                        cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat(),
                        cx + (outer * cos(a)).toFloat(), cy + (outer * sin(a)).toFloat(),
                        tick,
                )
            }
            // 指针（静止在起点）
            val a = Math.toRadians(160.0)
            val nx = cx + (radius - 30f * d) * cos(a).toFloat()
            val ny = cy + (radius - 30f * d) * sin(a).toFloat()
            canvas.drawLine(cx, cy, nx, ny, Draw.stroke(p.brand, 2.6f * d))
            canvas.drawCircle(cx, cy, 5f * d, Draw.fill(p.brand))
            canvas.drawCircle(cx, cy, 2f * d, Draw.fill(p.panel))

            val value = Draw.text(context, 30f, p.text, medium = true, align = Paint.Align.CENTER)
            val gv = result?.let { String.format(java.util.Locale.US, "%.0f", it.seqWriteMb) } ?: "—"
            canvas.drawText(gv, cx, cy - 4f * d, value)
            val unit = Draw.text(context, 10f, p.muted, align = Paint.Align.CENTER)
            canvas.drawText("MB/s · 顺序写", cx, cy + 18f * d, unit)

            val title = Draw.text(context, 11f, p.muted, align = Paint.Align.LEFT)
            canvas.drawText("闪存测速", r.left + 17f * d, r.top + 25f * d, title)
            val subject = Draw.text(context, 10.5f, p.text2, medium = true)
            canvas.drawText("顺序写", r.left + 17f * d, r.top + 46f * d, subject)
            val state = Draw.text(context, 9.5f, if (result != null) p.brand else p.warn, medium = true, align = Paint.Align.RIGHT)
            canvas.drawText(result?.grade ?: "尚未测试", r.right - 17f * d, r.top + 25f * d, state)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = buttonRect.contains(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressed && !buttonRect.contains(event.x, event.y)) {
                        pressed = false
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val hit = buttonRect.contains(event.x, event.y)
                    pressed = false
                    invalidate()
                    if (hit && !running) onStart?.invoke()
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
}
