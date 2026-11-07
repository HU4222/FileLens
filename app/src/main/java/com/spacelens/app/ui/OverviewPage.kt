package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Environment
import android.os.StatFs
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.ScanSummary
import kotlin.math.max
import kotlin.math.min

/** 总览页：容量环 + 四类占比 + 三个入口卡 + 上次扫描摘要。 */
class OverviewPage(context: Context) : FrameLayout(context) {

    var onStartScan: (() -> Unit)? = null
    var onOpenTab: ((Int) -> Unit)? = null
    var onHealth: (() -> Unit)? = null

    private val scroll = ScrollView(context)
    private val content = Content(context)

    private var totalBytes = 0L
    private var freeBytes = 0L
    private var categoryBytes = LongArray(7)
    private var scannedBytes = 0L
    private var summary: ScanSummary? = null
    private var ringAnim = 0f
    private var ringSweep = 0f
    private var anim: Motion.Animation? = null
    private var valueAnim: Motion.Animation? = null
    private var animatedUsed = 0L
    private var shown = false
    private var scanning = false
    private var scanAngle = 0f
    private var scanAnim: Motion.Animation? = null

    init {
        scroll.isFillViewport = true
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshStorage()
    }

    fun refreshStorage() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            totalBytes = stat.totalBytes
            freeBytes = stat.availableBytes
        } catch (t: Throwable) {
            totalBytes = 0L
            freeBytes = 0L
        }
        if (shown) {
            anim?.cancel()
            ringSweep = if (totalBytes > 0) (totalBytes - freeBytes).toFloat() / totalBytes else 0f
            animateNumber((totalBytes - freeBytes).coerceAtLeast(0L))
        }
        content.requestLayout()
        content.invalidate()
    }

    fun setSummary(summary: ScanSummary?, categories: LongArray?, scanned: Long) {
        this.summary = summary
        scannedBytes = scanned
        if (categories != null && categories.size >= 7) {
            categoryBytes = categories.copyOf(7)
        } else if (categories != null && categories.size >= 4) {
            categoryBytes = LongArray(7)
            categories.copyInto(categoryBytes, 0, 0, 4)
        } else {
            categoryBytes = LongArray(7)
        }
        content.requestLayout()
        content.invalidate()
        if (!shown) {
            shown = true
            animateRing()
        } else {
            anim?.cancel()
            ringSweep = if (totalBytes > 0) (totalBytes - freeBytes).toFloat() / totalBytes else 0f
            animateNumber((totalBytes - freeBytes).coerceAtLeast(0L))
        }
    }

    fun updateScanProgress(categories: LongArray, scanned: Long) {
        if (categories.size >= 7) categories.copyInto(categoryBytes, 0, 0, 7)
        scannedBytes = scanned
        content.invalidate()
    }

    fun setScanning(value: Boolean) {
        if (scanning == value) return
        scanning = value
        scanAnim?.cancel()
        if (value) {
            scanAnim = Motion.add(object : Motion.FrameListener {
                override fun onFrame(frameTimeNanos: Long, dtMs: Float): Boolean {
                    if (!scanning) return false
                    scanAngle = (scanAngle + dtMs * 360f / 900f) % 360f
                    content.invalidate()
                    return true
                }
            })
        } else {
            scanAnim = null
            scanAngle = 0f
        }
        content.invalidate()
    }

    fun onShown() {
        if (!shown) {
            shown = true
            animateRing()
        }
    }

    private fun animateRing() {
        anim?.cancel()
        val target = if (totalBytes > 0) (totalBytes - freeBytes).toFloat() / totalBytes else 0f
        anim = Motion.tween(MotionDurations.PAGE, Ease::softOut, { t ->
            ringAnim = t
            ringSweep = target * t
            content.invalidate()
        })
        animatedUsed = 0L
        animateNumber((totalBytes - freeBytes).coerceAtLeast(0L))
    }

    private fun animateNumber(target: Long) {
        valueAnim?.cancel()
        val from = animatedUsed
        if (from == target) return
        valueAnim = Motion.tween(MotionDurations.PAGE, Ease::softOut, { t ->
            animatedUsed = (from + (target - from) * t).toLong()
            content.invalidate()
        }, { animatedUsed = target })
    }

    private inner class Content(context: Context) : View(context) {

        private val d = resources.displayMetrics.density
        private val margin = Breakpoints.metrics(context).marginDp * d
        private val heroH = 400f * d
        private val scanH = 42f * d
        private val catH = 484f * d
        private val quickH = 66f * d
        private val quickGap = 10f * d
        private val summaryH = 96f * d

        private val scanRect = RectF()
        private val quickRects = Array(3) { RectF() }
        private var pressed = -1

        private fun contentHeight(): Int {
            var y = 10f * d + heroH + 12f * d
            y += catH + 14f * d
            y += 22f * d + quickH * 3 + quickGap * 2 + 14f * d
            y += summaryH + 26f * d + 72f * d
            return y.toInt()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = contentHeight()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val p = Theme.palette
            val w = width.toFloat()
            var y = 10f * d

            // ---- Hero 卡 ----
            val hero = RectF(margin, y, w - margin, y + heroH)
            drawHero(canvas, hero, p)
            y = hero.bottom + 12f * d

            // ---- 分类卡 ----
            val cat = RectF(margin, y, w - margin, y + catH)
            drawCategories(canvas, cat, p)
            y = cat.bottom + 14f * d

            // ---- 快捷入口 ----
            val headerPaint = Draw.text(context, 13f, p.text2, medium = true)
            canvas.drawText("快捷入口", margin + 2f * d, y + 14f * d, headerPaint)
            y += 22f * d
            val titles = arrayOf("空间树", "闪存测速", "存储体检")
            val subs = arrayOf("实时矩形树图，边扫边长", "六项读写测试与档位评级", "大文件 / 重复 / 空目录")
            val icons = arrayOf(Icon.TREE, Icon.SPEED, Icon.HEALTH)
            val colors = arrayOf(p.brand, p.brand2, p.ok)
            for (i in 0 until 3) {
                val r = RectF(margin, y, w - margin, y + quickH)
                quickRects[i].set(r)
                drawQuick(canvas, r, i, titles[i], subs[i], icons[i], colors[i], p)
                y += quickH + quickGap
            }
            y = quickRects[2].bottom + 14f * d

            // ---- 上次扫描 ----
            val sum = RectF(margin, y, w - margin, y + summaryH)
            drawSummary(canvas, sum, p)
        }

        private fun drawHero(canvas: Canvas, r: RectF, p: Palette) {
            Draw.softShadow(canvas, r, Radius.PANEL * d, d)
            canvas.drawRoundRect(r, Radius.PANEL * d, Radius.PANEL * d, Draw.fill(p.panel))
            val label = Draw.text(context, 15f, p.text2, medium = true)
            canvas.drawText("存储总览", r.left + 20f * d, r.top + 28f * d, label)

            val used = (totalBytes - freeBytes).coerceAtLeast(0L)
            val free = freeBytes.coerceAtLeast(0L)
            val cx = r.centerX()
            val cy = r.top + 150f * d
            val radius = 110f * d
            val ringRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

            val track = Draw.stroke(p.panelSoft, 10f * d)
            canvas.drawArc(ringRect, 0f, 360f, false, track)
            if (ringSweep > 0.0001f) {
                val ring = Draw.stroke(p.brand, 10f * d)
                ring.shader = SweepGradient(cx, cy, intArrayOf(p.brand, p.brand2, p.brand),
                        floatArrayOf(0f, 0.62f, 1f))
                canvas.save()
                canvas.rotate(-90f, cx, cy)
                canvas.drawArc(ringRect, 0f, 360f * ringSweep, false, ring)
                canvas.restore()
            }

            val (valueText, unitText) = Fmt.splitBytes(animatedUsed)
            val vp = Draw.text(context, 34f, p.text, medium = true, align = Paint.Align.LEFT)
            val up = Draw.text(context, 14f, p.text2, medium = true, align = Paint.Align.LEFT)
            val gap = 5f * d
            val totalW = vp.measureText(valueText) + gap + up.measureText(unitText)
            val startX = cx - totalW / 2f
            val baseline = cy + 8f * d
            canvas.drawText(valueText, startX, baseline, vp)
            canvas.drawText(unitText, startX + vp.measureText(valueText) + gap,
                    baseline - 10f * d, up)
            val cap = Draw.text(context, 11f, p.muted, align = Paint.Align.CENTER)
            canvas.drawText("已用", cx, cy + 34f * d, cap)

            // 环下两枚胶囊
            val pillTop = cy + radius + 20f * d
            val pillH = 32f * d
            val pillGap = 10f * d
            val pillW = (r.width() - 40f * d - pillGap) / 2f
            val usedPill = RectF(r.left + 20f * d, pillTop, r.left + 20f * d + pillW, pillTop + pillH)
            val freePill = RectF(usedPill.right + pillGap, pillTop, r.right - 20f * d, pillTop + pillH)
            drawPill(canvas, usedPill, p, p.brand, "已用 " + Fmt.bytes(used))
            drawPill(canvas, freePill, p, p.ok, "可用 " + Fmt.bytes(free))

            // 主按钮：brand→brand2 45 度渐变胶囊
            scanRect.set(r.left + 20f * d, r.bottom - 68f * d, r.right - 20f * d, r.bottom - 16f * d)
            val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            buttonPaint.shader = LinearGradient(scanRect.left, scanRect.top,
                    scanRect.right, scanRect.bottom,
                    intArrayOf(p.brand, p.brand2), null, Shader.TileMode.CLAMP)
            canvas.save()
            if (pressed == 0) canvas.scale(0.97f, 0.97f, scanRect.centerX(), scanRect.centerY())
            canvas.drawRoundRect(scanRect, scanRect.height() / 2f, scanRect.height() / 2f, buttonPaint)
            val btnText = if (summary == null) "开始扫描" else "重新扫描"
            val bt = Draw.text(context, 15f, 0xFFFFFFFF.toInt(), medium = true)
            val iconSize = 17f * d
            val textW = bt.measureText(btnText)
            val total = textW + iconSize + 8f * d
            val bx = scanRect.centerX() - total / 2f
            Draw.icon(canvas, Icon.SEARCH, bx + iconSize / 2f, scanRect.centerY(), iconSize,
                    0xFFFFFFFF.toInt(), 1.8f * d)
            canvas.drawText(btnText, bx + iconSize + 8f * d, scanRect.centerY() + 5f * d, bt)
            canvas.restore()
        }

        private fun drawPill(canvas: Canvas, rect: RectF, p: Palette, color: Int, text: String) {
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f,
                    Draw.fill(p.panelSunken))
            canvas.drawCircle(rect.left + 16f * d, rect.centerY(), 4f * d, Draw.fill(color))
            val tp = Draw.text(context, 10.5f, p.text2, medium = true)
            canvas.drawText(Draw.ellipsize(text, tp, rect.width() - 28f * d),
                    rect.left + 26f * d, rect.centerY() + 3.6f * d, tp)
        }
        private fun drawCategories(canvas: Canvas, r: RectF, p: Palette) {
            val head = Draw.text(context, 15f, p.text, medium = true)
            canvas.drawText("空间分布", r.left, r.top + 16f * d, head)
            val sub = Draw.text(context, 10f, p.muted, align = Paint.Align.RIGHT)
            val scanText = if (scannedBytes > 0) "已扫描 " + Fmt.bytes(scannedBytes) else "尚未扫描"
            canvas.drawText(scanText, r.right, r.top + 16f * d, sub)

            val names = arrayOf("图片", "视频", "音频", "文档", "安装包", "压缩包", "其他", "可用")
            val values = LongArray(8)
            for (i in 0 until 7) values[i] = categoryBytes[i]
            values[7] = freeBytes.coerceAtLeast(0L)
            val colors = IntArray(8) { if (it < 7) p.category[it] else p.brand }
            val denom = max(scannedBytes, 1L).toFloat()
            var main = 0
            for (i in 1 until 7) if (categoryBytes[i] > categoryBytes[main]) main = i

            val gap = 12f * d
            val cardW = (r.width() - gap) / 2f
            val cardH = 100f * d
            val top = r.top + 34f * d
            for (i in 0 until 8) {
                val col = i % 2
                val row = i / 2
                val left = r.left + col * (cardW + gap)
                val card = RectF(left, top + row * (cardH + gap), left + cardW, top + row * (cardH + gap) + cardH)
                Draw.softShadow(canvas, card, Radius.PANEL * d, d)
                canvas.drawRoundRect(card, Radius.PANEL * d, Radius.PANEL * d, Draw.fill(p.panel))
                if (!scanning && i == main && scannedBytes > 0) {
                    val glow = Draw.stroke(Theme.alpha(colors[i], 90), 1f * d)
                    canvas.drawRoundRect(card, Radius.PANEL * d, Radius.PANEL * d, glow)
                }
                drawCatIcon(canvas, i, card.left + 16f * d, card.top + 18f * d, 24f * d, colors[i], p)

                val name = Draw.text(context, 15f, p.text2, medium = true)
                canvas.drawText(names[i], card.left + 50f * d, card.top + 36f * d, name)

                val value = values[i]
                val (num, unit) = if (scannedBytes > 0 || i == 7) Fmt.splitBytes(value) else "—" to ""
                val np = Draw.text(context, 28f, p.text, medium = true)
                canvas.drawText(num, card.left + 16f * d, card.top + 78f * d, np)
                if (unit.isNotEmpty()) {
                    val up = Draw.text(context, 13f, p.muted)
                    canvas.drawText(unit, card.left + 16f * d + np.measureText(num) + 4f * d,
                            card.top + 78f * d - 3f * d, up)
                }

                val track = RectF(card.left + 16f * d, card.bottom - 15f * d,
                        card.right - 16f * d, card.bottom - 12f * d)
                canvas.drawRoundRect(track, 2f * d, 2f * d, Draw.fill(p.panelSunken))
                val ratio = if (i == 7) {
                    if (totalBytes > 0) (value.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                } else {
                    (value / denom).coerceIn(0f, 1f)
                }
                if (ratio > 0f) {
                    val bar = RectF(track.left, track.top, track.left + track.width() * ratio, track.bottom)
                    canvas.drawRoundRect(bar, 2f * d, 2f * d, Draw.fill(colors[i]))
                }

                if (scanning) {
                    val rect = RectF(card.right - 34f * d, card.top + 10f * d,
                            card.right - 14f * d, card.top + 30f * d)
                    val arc = Draw.stroke(colors[i], 2f * d)
                    canvas.drawArc(rect, scanAngle, 270f, false, arc)
                }
            }
        }

        private fun drawCatIcon(canvas: Canvas, index: Int, x: Float, y: Float, s: Float,
                                color: Int, p: Palette) {
            val stroke = Draw.stroke(color, 1.6f * d)
            when (index) {
                0 -> { // 图片
                    canvas.drawRoundRect(RectF(x, y, x + s, y + s * 0.78f), 3f * d, 3f * d, stroke)
                    canvas.drawCircle(x + s * 0.28f, y + s * 0.28f, s * 0.10f, stroke)
                    val path = Path().apply {
                        moveTo(x + 3f * d, y + s * 0.66f)
                        lineTo(x + s * 0.38f, y + s * 0.36f)
                        lineTo(x + s * 0.58f, y + s * 0.56f)
                        lineTo(x + s * 0.78f, y + s * 0.34f)
                        lineTo(x + s, y + s * 0.60f)
                    }
                    canvas.drawPath(path, stroke)
                }
                1 -> { // 视频
                    canvas.drawRoundRect(RectF(x, y, x + s, y + s * 0.78f), 3f * d, 3f * d, stroke)
                    val path = Path().apply {
                        moveTo(x + s * 0.38f, y + s * 0.24f)
                        lineTo(x + s * 0.68f, y + s * 0.39f)
                        lineTo(x + s * 0.38f, y + s * 0.54f)
                        close()
                    }
                    canvas.drawPath(path, stroke)
                }
                2 -> { // 音频
                    canvas.drawCircle(x + s * 0.28f, y + s * 0.68f, s * 0.13f, stroke)
                    canvas.drawLine(x + s * 0.40f, y + s * 0.66f, x + s * 0.40f, y + s * 0.16f, stroke)
                    canvas.drawLine(x + s * 0.40f, y + s * 0.16f, x + s * 0.82f, y + s * 0.10f, stroke)
                    canvas.drawLine(x + s * 0.82f, y + s * 0.10f, x + s * 0.82f, y + s * 0.54f, stroke)
                }
                3 -> { // 文档
                    canvas.drawRoundRect(RectF(x + 2f * d, y, x + s - 2f * d, y + s), 2f * d, 2f * d, stroke)
                    canvas.drawLine(x + s * 0.32f, y + s * 0.30f, x + s * 0.68f, y + s * 0.30f, stroke)
                    canvas.drawLine(x + s * 0.32f, y + s * 0.50f, x + s * 0.68f, y + s * 0.50f, stroke)
                    canvas.drawLine(x + s * 0.32f, y + s * 0.70f, x + s * 0.56f, y + s * 0.70f, stroke)
                }
                4 -> { // 安装包
                    canvas.drawRoundRect(RectF(x + 2f * d, y + 3f * d, x + s - 2f * d, y + s), 2f * d, 2f * d, stroke)
                    canvas.drawLine(x + 2f * d, y + s * 0.36f, x + s - 2f * d, y + s * 0.36f, stroke)
                    canvas.drawLine(x + s * 0.50f, y + s * 0.36f, x + s * 0.50f, y + s, stroke)
                }
                5 -> { // 压缩包
                    canvas.drawRoundRect(RectF(x + 3f * d, y, x + s - 3f * d, y + s), 2f * d, 2f * d, stroke)
                    canvas.drawLine(x + s * 0.50f, y, x + s * 0.50f, y + s, stroke)
                    var yy = y + 4f * d
                    while (yy < y + s - 2f * d) {
                        canvas.drawLine(x + s * 0.40f, yy, x + s * 0.60f, yy, stroke)
                        yy += 5f * d
                    }
                }
                else -> { // 其他 / 可用
                    canvas.drawCircle(x + s * 0.50f, y + s * 0.50f, s * 0.34f, stroke)
                    canvas.drawCircle(x + s * 0.50f, y + s * 0.50f, s * 0.08f, stroke)
                }
            }
        }
        private fun drawQuick(
                canvas: Canvas, r: RectF, index: Int,
                title: String, subtitle: String, icon: Icon, color: Int, p: Palette,
        ) {
            Draw.card(canvas, r, Radius.PANEL * d, p.panel, p.line, shadow = true)
            if (pressed == index + 1) {
                canvas.drawRoundRect(r, Radius.PANEL * d, Radius.PANEL * d, Draw.fill(Theme.alpha(p.brand, 12)))
            }
            val cx = r.left + 38f * d
            canvas.drawCircle(cx, r.centerY(), 18f * d, Draw.fill(Theme.alpha(color, if (p.dark) 42 else 22)))
            Draw.icon(canvas, icon, cx, r.centerY(), 20f * d, color, 1.9f * d)
            val t = Draw.text(context, 15f, p.text, medium = true)
            canvas.drawText(title, r.left + 66f * d, r.centerY() - 4f * d, t)
            val s = Draw.text(context, 11f, p.muted)
            canvas.drawText(subtitle, r.left + 66f * d, r.centerY() + 14f * d, s)
            Draw.icon(canvas, Icon.CHEVRON, r.right - 24f * d, r.centerY(), 14f * d, p.muted, 1.8f * d)
        }

        private fun drawSummary(canvas: Canvas, r: RectF, p: Palette) {
            Draw.card(canvas, r, Radius.PANEL * d, p.panel, p.line, shadow = true)
            val s = summary
            if (s == null || s.finishedAt <= 0L) {
                canvas.drawCircle(r.left + 38f * d, r.centerY(), 18f * d, Draw.fill(p.panelSunken))
                Draw.icon(canvas, Icon.REFRESH, r.left + 38f * d, r.centerY(), 18f * d, p.muted, 1.8f * d)
                val t = Draw.text(context, 15f, p.text2, medium = true)
                canvas.drawText("还没有扫描记录", r.left + 66f * d, r.centerY() - 4f * d, t)
                val s2 = Draw.text(context, 10f, p.muted)
                canvas.drawText("扫描后这里显示文件数、耗时与时间", r.left + 66f * d, r.centerY() + 14f * d, s2)
                return
            }
            val title = Draw.text(context, 11f, p.muted)
            canvas.drawText("上次扫描 · " + Fmt.dateTime(s.finishedAt), r.left + 16f * d, r.top + 24f * d, title)
            if (s.partial) {
                val badge = RectF(r.right - 72f * d, r.top + 12f * d, r.right - 16f * d, r.top + 32f * d)
                canvas.drawRoundRect(badge, 10f * d, 10f * d, Draw.fill(Theme.alpha(p.warn, 32)))
                val bp = Draw.text(context, 9f, p.warn, medium = true, align = Paint.Align.CENTER)
                canvas.drawText("部分结果", badge.centerX(), badge.centerY() + 3f * d, bp)
            }
            val big = Draw.text(context, 16f, p.text, medium = true)
            canvas.drawText(Fmt.count(s.files.toLong()) + " 个文件", r.left + 16f * d, r.top + 52f * d, big)
            val detail = Draw.text(context, 10.5f, p.text2)
            canvas.drawText(
                    "${Fmt.bytes(s.bytes)} · ${Fmt.count(s.dirs.toLong())} 个目录 · 耗时 ${Fmt.duration(s.elapsedMs)}",
                    r.left + 16f * d, r.top + 73f * d, detail,
            )
            val root = Draw.text(context, 9.5f, p.muted, align = Paint.Align.RIGHT)
            canvas.drawText(Draw.ellipsize(s.rootName.ifEmpty { s.rootPath }, root, r.width() * 0.38f),
                    r.right - 16f * d, r.top + 73f * d, root)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hit(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressed >= 0 && hit(event.x, event.y) != pressed) {
                        pressed = -1
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val h = hit(event.x, event.y)
                    val was = pressed
                    pressed = -1
                    invalidate()
                    if (h >= 0 && h == was) {
                        when (h) {
                            0 -> onStartScan?.invoke()
                            1 -> onOpenTab?.invoke(1)
                            2 -> onOpenTab?.invoke(2)
                            3 -> onHealth?.invoke()
                        }
                    }
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

        private fun hit(x: Float, y: Float): Int {
            if (scanRect.contains(x, y)) return 0
            for (i in quickRects.indices) if (quickRects[i].contains(x, y)) return i + 1
            return -1
        }
    }
}
