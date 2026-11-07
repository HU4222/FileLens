package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView

/** 我的页：扫描范围、权限、主题、缓存、导出、关于。 */
class MinePage(context: Context) : FrameLayout(context) {

    enum class Action {
        SCOPE, PERMISSION, RATE, THEME, TRASH, CLEAR_CACHE, EXPORT, VERSION, ABOUT,
    }

    data class State(
            val theme: String = "跟随系统",
            val rate: String = "均衡",
            val permission: String = "未授权",
            val scope: String = "内部存储",
            val cache: String = "—",
            val trash: String = "0 项",
            val version: String = "1.0.0",
    )

    var onAction: ((Action) -> Unit)? = null
    private var state = State()
    private val content = Content(context)

    init {
        val scroll = ScrollView(context)
        scroll.isFillViewport = true
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setState(state: State) {
        this.state = state
        content.invalidate()
    }

    private inner class Content(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val margin = Breakpoints.metrics(context).marginDp * d
        private val headerH = 104f * d
        private val rowH = 54f * d
        private val sectionGap = 16f * d
        private val sectionHead = 24f * d
        private var pressed = -1
        private val rowRects = ArrayList<RectF>()
        private val actions = ArrayList<Action>()

        private val section1 = arrayOf(
                Triple(Icon.FOLDER, "扫描范围", Action.SCOPE),
                Triple(Icon.SHIELD, "权限状态", Action.PERMISSION),
                Triple(Icon.SPEED, "扫描速度", Action.RATE),
        )
        private val section2 = arrayOf(
                Triple(Icon.MOON, "主题", Action.THEME),
                Triple(Icon.TRASH, "回收站", Action.TRASH),
                Triple(Icon.TRASH, "缓存清理", Action.CLEAR_CACHE),
                Triple(Icon.INFO, "导出数据", Action.EXPORT),
        )
        private val section3 = arrayOf(
                Triple(Icon.INFO, "版本信息", Action.VERSION),
                Triple(Icon.SHIELD, "关于 SpaceLens", Action.ABOUT),
        )

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (8f * d + headerH + sectionGap + sectionHead + rowH * 3 + sectionGap +
                    sectionHead + rowH * 4 + sectionGap + sectionHead + rowH * 2 + 76f * d).toInt()
            setMeasuredDimension(w, h)
        }

        private fun valueFor(action: Action): String = when (action) {
            Action.SCOPE -> state.scope
            Action.PERMISSION -> state.permission
            Action.RATE -> state.rate
            Action.THEME -> state.theme
            Action.TRASH -> state.trash
            Action.CLEAR_CACHE -> state.cache
            Action.EXPORT -> "JSON / CSV / PNG"
            Action.VERSION -> "v" + state.version
            Action.ABOUT -> "署名与版本"
        }

        override fun onDraw(canvas: Canvas) {
            val p = Theme.palette
            val w = width.toFloat()
            rowRects.clear()
            actions.clear()
            var y = 8f * d

            // 头部
            val head = RectF(margin, y, w - margin, y + headerH)
            Draw.card(canvas, head, 18f * d, p.panel, p.line)
            val mark = RectF(head.left + 18f * d, head.centerY() - 23f * d,
                    head.left + 64f * d, head.centerY() + 23f * d)
            val grad = Paint(Paint.ANTI_ALIAS_FLAG)
            grad.shader = android.graphics.LinearGradient(mark.left, mark.top, mark.right, mark.bottom,
                    intArrayOf(p.brand, p.brand2), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(mark, 13f * d, 13f * d, grad)
            grad.shader = null
            grad.color = 0xFFFFFFFF.toInt()
            val t = mark.width() * 0.18f
            val g = mark.width() * 0.06f
            val ox = mark.left + mark.width() * 0.20f
            val oy = mark.top + mark.width() * 0.20f
            canvas.drawRoundRect(ox, oy, ox + t * 2 + g, oy + t, 2f * d, 2f * d, grad)
            canvas.drawRoundRect(ox, oy + t + g, ox + t, oy + t * 2 + g, 2f * d, 2f * d, grad)
            canvas.drawRoundRect(ox + t + g, oy + t + g, ox + t * 2 + g, oy + t * 2 + g,
                    2f * d, 2f * d, grad)

            val name = Draw.text(context, 19f, p.text, medium = true)
            canvas.drawText("SpaceLens", head.left + 78f * d, head.centerY() - 3f * d, name)
            val sub = Draw.text(context, 10f, p.muted)
            canvas.drawText("空间透镜 · 回收站删除 · 纯本地", head.left + 78f * d, head.centerY() + 15f * d, sub)
            val chip = RectF(head.right - 74f * d, head.centerY() - 13f * d,
                    head.right - 16f * d, head.centerY() + 13f * d)
            canvas.drawRoundRect(chip, 13f * d, 13f * d, Draw.fill(p.brandSoft))
            val cp = Draw.text(context, 9.5f, p.brand, medium = true, align = Paint.Align.CENTER)
            canvas.drawText("v" + state.version, chip.centerX(), chip.centerY() + 3.2f * d, cp)
            y = head.bottom + sectionGap

            y = drawSection(canvas, y, "扫描与权限", section1, p)
            y += sectionGap
            y = drawSection(canvas, y, "外观与数据", section2, p)
            y += sectionGap
            y = drawSection(canvas, y, "关于", section3, p)

            // 页脚署名（G-16：应用内第 3 处）
            y += 30f * d
            val footer = Draw.text(context, 9.5f, p.muted, align = Paint.Align.CENTER)
            canvas.drawText("SpaceLens v" + state.version + " · HU4222", w / 2f, y, footer)
            val footer2 = Draw.text(context, 8.5f, Theme.alpha(p.muted, 170), align = Paint.Align.CENTER)
            canvas.drawText("不申请 INTERNET 权限 · 删除只走回收站", w / 2f, y + 15f * d, footer2)
            val hz = try {
                (if (android.os.Build.VERSION.SDK_INT >= 30) context.display?.refreshRate
                else (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate) ?: 60f
            } catch (_: Throwable) { 60f }
            val footer3 = Draw.text(context, 8.5f, Theme.alpha(p.muted, 150), align = Paint.Align.CENTER)
            val note = if (hz < 90f) "当前屏幕 ${hz.toInt()}Hz，动画按 ${hz.toInt()}fps 渲染"
            else "当前屏幕 ${hz.toInt()}Hz，动画按 120fps 渲染"
            canvas.drawText(note, w / 2f, y + 29f * d, footer3)
        }

        private fun drawSection(
                canvas: Canvas, top: Float, title: String,
                rows: Array<Triple<Icon, String, Action>>, p: Palette,
        ): Float {
            val w = width.toFloat()
            val head = Draw.text(context, 11f, p.muted, medium = true)
            canvas.drawText(title, margin + 2f * d, top + 13f * d, head)
            var y = top + sectionHead
            val card = RectF(margin, y, w - margin, y + rowH * rows.size)
            Draw.card(canvas, card, 18f * d, p.panel, p.line)
            for (i in rows.indices) {
                val (icon, label, action) = rows[i]
                val rowTop = y + i * rowH
                val row = RectF(margin, rowTop, w - margin, rowTop + rowH)
                rowRects.add(row)
                actions.add(action)
                if (pressed == rowRects.size - 1) {
                    canvas.drawRoundRect(row, 16f * d, 16f * d, Draw.fill(Theme.alpha(p.brand, 10)))
                }
                val cx = row.left + 32f * d
                canvas.drawCircle(cx, row.centerY(), 15f * d, Draw.fill(p.panelSoft))
                Draw.icon(canvas, icon, cx, row.centerY(), 16f * d, p.text2, 1.7f * d)
                val lp = Draw.text(context, 12.5f, p.text)
                canvas.drawText(label, row.left + 54f * d, row.centerY() + 4.2f * d, lp)

                val value = valueFor(action)
                val vp = Draw.text(context, 10.5f, p.muted, align = Paint.Align.RIGHT)
                val maxV = row.width() - 54f * d - 32f * d
                canvas.drawText(Draw.ellipsize(value, vp, maxV), row.right - 30f * d,
                        row.centerY() + 3.8f * d, vp)
                Draw.icon(canvas, Icon.CHEVRON, row.right - 16f * d, row.centerY(), 12f * d, p.muted, 1.6f * d)
                if (i < rows.size - 1) {
                    canvas.drawLine(row.left + 54f * d, row.bottom, row.right - 16f * d, row.bottom,
                            Draw.stroke(p.line, 1f))
                }
            }
            return card.bottom
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
                    if (h >= 0 && h == was && h < actions.size) onAction?.invoke(actions[h])
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
            for (i in rowRects.indices) if (rowRects[i].contains(x, y)) return i
            return -1
        }
    }
}
