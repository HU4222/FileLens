package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Environment
import android.os.StatFs
import android.view.MotionEvent
import android.view.View
import com.spacelens.app.core.DeletePolicy
import com.spacelens.app.core.Fmt
import kotlin.math.max
import com.spacelens.app.core.NodeStore
import com.spacelens.app.core.ScanSummary
import kotlin.math.max

/**
 * 平板右侧详情 / 统计栏（8.5）：
 * 树图页常驻选中节点信息；其他页展示存储统计与上次扫描摘要。
 * 手机不显示；600~839dp 只在树图页显示。
 */
class DetailPane(context: Context) : View(context) {

    var onCopyPath: ((String) -> Unit)? = null
    var onOpen: ((Int) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    var onStartScan: (() -> Unit)? = null
    var onTrash: (() -> Unit)? = null
    var onDeleteSelection: (() -> Unit)? = null
    var onCancelSelection: (() -> Unit)? = null

    private var store: NodeStore? = null
    private var node = -1
    private var decision: DeletePolicy.Decision = DeletePolicy.Decision.OK
    private var storage = false
    private var rootPath = ""
    private var version = "1.0.0"
    private var summary: ScanSummary? = null
    private var catCache = LongArray(7)
    private var catCacheCount = -1
    private var totalBytes = 0L
    private var freeBytes = 0L

    private val d = resources.displayMetrics.density
    private val padding = 20f * d
    private val selectRect = RectF()
    private val copyRect = RectF()
    private val openRect = RectF()
    private val scanRect = RectF()
    private val trashRect = RectF()
    private var pressed = -1
    private var selActive = false
    private var selCount = 0
    private var selBytes = 0L
    private val selDeleteRect = RectF()
    private val selCancelRect = RectF()

    fun setSelection(active: Boolean, count: Int, bytes: Long) {
        selActive = active
        selCount = count
        selBytes = bytes
        invalidate()
    }

    fun showNode(store: NodeStore, node: Int, rootPath: String) {
        this.store = store
        this.node = node
        this.storage = false
        this.rootPath = rootPath
        this.decision = DeletePolicy.decide(context, store, node, rootPath)
        invalidate()
    }

    fun showStorage(store: NodeStore?, summary: ScanSummary?, version: String) {
        this.store = store
        this.node = -1
        this.storage = true
        this.summary = summary
        this.version = version
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
            totalBytes = stat.totalBytes
            freeBytes = stat.availableBytes
        } catch (_: Throwable) {
            totalBytes = 0L
            freeBytes = 0L
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(p.panel)
        canvas.drawLine(0.5f * d, 0f, 0.5f * d, h, Draw.stroke(p.line, 1f * d))

        val header = Draw.text(context, 10.5f, p.muted, medium = true)
        canvas.drawText("详情", padding, padding + 10f * d, header)

        if (selActive) {
            drawSelection(canvas, p, w)
            return
        }
        if (storage) {
            drawStorage(canvas, p, w, h)
        } else {
            drawNode(canvas, p, w, h)
        }
    }

    private fun drawSelection(canvas: Canvas, p: Palette, w: Float) {
        val top = padding + 22f * d
        val box = RectF(padding, top, w - padding, top + 92f * d)
        canvas.drawRoundRect(box, 14f * d, 14f * d, Draw.fill(p.panelSoft))
        canvas.drawText("已选 $selCount 项", padding + 14f * d, top + 28f * d,
                Draw.text(context, 16f, p.text, medium = true))
        canvas.drawText("合计 " + Fmt.bytes(selBytes), padding + 14f * d, top + 50f * d,
                Draw.text(context, 11f, p.muted))
        selDeleteRect.set(box.right - 132f * d, box.bottom - 34f * d,
                box.right - 76f * d, box.bottom - 8f * d)
        selCancelRect.set(box.right - 68f * d, box.bottom - 34f * d,
                box.right - 12f * d, box.bottom - 8f * d)
        canvas.drawRoundRect(selDeleteRect, 9f * d, 9f * d, Draw.fill(p.brand))
        canvas.drawText("删除", selDeleteRect.centerX(), selDeleteRect.centerY() + 4f * d,
                Draw.text(context, 10.5f, android.graphics.Color.WHITE, medium = true, align = Paint.Align.CENTER))
        canvas.drawRoundRect(selCancelRect, 9f * d, 9f * d, Draw.fill(p.panelSunken))
        canvas.drawText("取消", selCancelRect.centerX(), selCancelRect.centerY() + 4f * d,
                Draw.text(context, 10.5f, p.text2, align = Paint.Align.CENTER))
    }

    private fun drawNode(canvas: Canvas, p: Palette, w: Float, h: Float) {
        val s = store
        if (s == null || node !in 0 until s.count) {
            val t = Draw.text(context, 12f, p.muted)
            canvas.drawText("在空间树中长按一个方块", padding, padding + 46f * d, t)
            return
        }
        val name = s.nameOf(node)
        val path = s.absolutePathOf(rootPath, node)
        val size = s.sizeOf(node)
        val parent = s.parentOf(node)
        val parentSize = if (parent >= 0) max(s.sizeOf(parent), 1L) else max(size, 1L)
        val share = if (parentSize > 0) size.toFloat() / parentSize else 0f

        var y = padding + 40f * d
        val cat = s.categoryOf(node)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = p.category[cat.coerceIn(0, 6)]
        canvas.drawCircle(padding + 4f * d, y - 5f * d, 4f * d, dot)
        val title = Draw.text(context, 16f, p.text, medium = true)
        canvas.drawText(Draw.ellipsize(name, title, w - padding * 2 - 12f * d),
                padding + 14f * d, y, title)
        y += 22f * d
        val pathPaint = Draw.text(context, 9.5f, p.muted)
        canvas.drawText(Draw.ellipsize(path, pathPaint, w - padding * 2), padding, y, pathPaint)
        y += 30f * d

        val sizeLabel = Draw.text(context, 9.5f, p.muted)
        canvas.drawText("体积", padding, y, sizeLabel)
        val sizePaint = Draw.text(context, 25f, p.text, medium = true)
        canvas.drawText(Fmt.bytes(size), padding, y + 30f * d, sizePaint)
        y += 56f * d

        val track = RectF(padding, y, w - padding, y + 6f * d)
        canvas.drawRoundRect(track, 3f * d, 3f * d, Draw.fill(p.panelSunken))
        canvas.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * share.coerceIn(0f, 1f),
                track.bottom), 3f * d, 3f * d, Draw.fill(p.brand))
        val sharePaint = Draw.text(context, 9.5f, p.text2)
        canvas.drawText("占父级（已扫描）" + Fmt.pct(share), padding, y + 22f * d, sharePaint)
        y += 40f * d

        y = row(canvas, "文件数", Fmt.count(s.filesOf(node).toLong()), y, p)
        y = row(canvas, "子目录", Fmt.count(s.dirsOf(node).toLong()), y, p)
        y = row(canvas, "修改时间", Fmt.dateTime(s.mtimeOf(node)), y, p)
        y += 6f * d

        val pathCard = RectF(padding, y, w - padding, y + 64f * d)
        Draw.card(canvas, pathCard, 12f * d, p.panel, p.line)
        val pl = Draw.text(context, 9f, p.muted)
        canvas.drawText("完整路径", pathCard.left + 10f * d, pathCard.top + 16f * d, pl)
        val pp = Draw.text(context, 9.5f, p.text2)
        canvas.drawText(Draw.ellipsize(path, pp, pathCard.width() - 20f * d),
                pathCard.left + 10f * d, pathCard.top + 34f * d, pp)

        // 底部动作
        val bottom = h - padding
        val primaryH = 44f * d
        val secondaryH = 36f * d
        selectRect.set(padding, bottom - primaryH, w - padding, bottom)
        val enabled = decision.selectable
        canvas.drawRoundRect(selectRect, 12f * d, 12f * d,
                Draw.fill(if (enabled) p.brand else p.panelSunken))
        val sp = Draw.text(context, 12f, if (enabled) 0xFFFFFFFF.toInt() else p.muted,
                medium = true, align = Paint.Align.CENTER)
        val primaryLabel = if (enabled) "选择" else decision.reason.ifEmpty { "不可选择" }
        canvas.drawText(Draw.ellipsize(primaryLabel, sp, selectRect.width() - 16f * d),
                selectRect.centerX(), selectRect.centerY() + 4f * d, sp)

        val top2 = selectRect.top - secondaryH - 8f * d
        val half = (w - padding * 2 - 8f * d) / 2f
        copyRect.set(padding, top2, padding + half, top2 + secondaryH)
        openRect.set(padding + half + 8f * d, top2, w - padding, top2 + secondaryH)
        drawSecondary(canvas, copyRect, "复制路径", p)
        drawSecondary(canvas, openRect, "打开", p)
    }

    private fun drawStorage(canvas: Canvas, p: Palette, w: Float, h: Float) {
        var y = padding + 40f * d
        val title = Draw.text(context, 18f, p.text, medium = true)
        canvas.drawText("存储统计", padding, y, title)
        y += 22f * d
        val sub = Draw.text(context, 9.5f, p.muted)
        canvas.drawText("SpaceLens v" + version, padding, y, sub)
        y += 30f * d

        val used = (totalBytes - freeBytes).coerceAtLeast(0L)
        val frac = if (totalBytes > 0) used.toFloat() / totalBytes else 0f
        val bar = RectF(padding, y, w - padding, y + 10f * d)
        canvas.drawRoundRect(bar, 5f * d, 5f * d, Draw.fill(p.panelSunken))
        canvas.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * frac, bar.bottom),
                5f * d, 5f * d, Draw.fill(p.brand))
        y += 28f * d
        y = row(canvas, "已用", Fmt.bytes(used), y, p)
        y = row(canvas, "可用", Fmt.bytes(freeBytes), y, p)
        y = row(canvas, "总计", Fmt.bytes(totalBytes), y, p)
        y += 8f * d

        val nodes = store?.count ?: 0
        y = row(canvas, "节点", Fmt.count(nodes.toLong()), y, p)
        val sum = summary
        if (sum != null) {
            y = row(canvas, "上次扫描", Fmt.dateTime(sum.finishedAt), y, p)
            y = row(canvas, "文件 / 耗时",
                    Fmt.count(sum.files.toLong()) + " / " + Fmt.duration(sum.elapsedMs), y, p)
        } else {
            y = row(canvas, "上次扫描", "—", y, p)
        }

        val s = store
        if (s != null && s.count > 1 && y < h - 140f * d) {
            y += 12f * d
            val ch = Draw.text(context, 9.5f, p.muted, medium = true)
            canvas.drawText("分类占比（已扫描）", padding, y + 10f * d, ch)
            y += 22f * d
            if (catCacheCount != s.count) {
                catCache = s.categoryTotals().copyOf(7)
                catCacheCount = s.count
            }
            val denom = max((sum?.bytes ?: 0L), 1L).toFloat()
            val names = arrayOf("图片", "视频", "音频", "文档", "应用", "压缩", "其他")
            for (i in 0 until 7) {
                if (y > h - 120f * d) break
                val lp = Draw.text(context, 9f, p.text2)
                canvas.drawText(names[i], padding, y + 8f * d, lp)
                val track = RectF(padding + 38f * d, y + 5f * d, w - padding - 46f * d, y + 8f * d)
                canvas.drawRoundRect(track, 2f * d, 2f * d, Draw.fill(p.panelSunken))
                val ratio = (catCache[i].toFloat() / denom).coerceIn(0f, 1f)
                if (ratio > 0f) {
                    canvas.drawRoundRect(RectF(track.left, track.top,
                            track.left + track.width() * ratio, track.bottom),
                            2f * d, 2f * d, Draw.fill(p.category[i]))
                }
                val vp = Draw.text(context, 8.5f, p.muted, align = Paint.Align.RIGHT)
                canvas.drawText(Fmt.bytesShort(catCache[i]), w - padding, y + 8f * d, vp)
                y += 25f * d
            }
        }

        val bottom = h - padding
        scanRect.set(padding, bottom - 44f * d, w - padding, bottom)
        canvas.drawRoundRect(scanRect, 12f * d, 12f * d, Draw.fill(p.brand))
        val sp = Draw.text(context, 12f, 0xFFFFFFFF.toInt(), medium = true, align = Paint.Align.CENTER)
        canvas.drawText("重新扫描", scanRect.centerX(), scanRect.centerY() + 4f * d, sp)
        val top2 = scanRect.top - 44f * d
        trashRect.set(padding, top2, w - padding, top2 + 36f * d)
        drawSecondary(canvas, trashRect, "回收站（M5 接入）", p)
    }

    private fun row(canvas: Canvas, label: String, value: String, top: Float, p: Palette): Float {
        val lp = Draw.text(context, 9.5f, p.muted)
        canvas.drawText(label, padding, top + 10f * d, lp)
        val vp = Draw.text(context, 10.5f, p.text2, medium = true, align = Paint.Align.RIGHT)
        canvas.drawText(Draw.ellipsize(value, vp, width - padding * 2 - 70f * d),
                width - padding, top + 10f * d, vp)
        canvas.drawLine(padding, top + 24f * d, width - padding, top + 24f * d, Draw.stroke(p.line, 1f))
        return top + 32f * d
    }

    private fun drawSecondary(canvas: Canvas, r: RectF, text: String, p: Palette) {
        canvas.drawRoundRect(r, 11f * d, 11f * d, Draw.fill(p.panel))
        val stroke = Draw.stroke(p.line, 1f)
        canvas.drawRoundRect(r, 11f * d, 11f * d, stroke)
        val tp = Draw.text(context, 10f, p.text2, medium = true, align = Paint.Align.CENTER)
        canvas.drawText(text, r.centerX(), r.centerY() + 3.5f * d, tp)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selActive) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (selDeleteRect.contains(event.x, event.y)) onDeleteSelection?.invoke()
                else if (selCancelRect.contains(event.x, event.y)) onCancelSelection?.invoke()
            }
            return true
        }
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
                        0 -> if (storage) onStartScan?.invoke() else if (decision.selectable && node >= 0) onSelect?.invoke(node)
                        1 -> if (!storage && node >= 0) onCopyPath?.invoke(store?.absolutePathOf(rootPath, node) ?: "")
                        2 -> if (!storage && node >= 0) onOpen?.invoke(node)
                        3 -> onTrash?.invoke()
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
        if (storage) {
            if (scanRect.contains(x, y)) return 0
            if (trashRect.contains(x, y)) return 3
            return -1
        }
        if (selectRect.contains(x, y)) return 0
        if (copyRect.contains(x, y)) return 1
        if (openRect.contains(x, y)) return 2
        return -1
    }
}
