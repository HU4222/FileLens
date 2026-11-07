package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/** 顶部标题栏：品牌标识 + 页标题 + 本地/回收站徽章。长按 1.5s 留给 FPS HUD（隐藏功能）。 */
class TopBar(context: Context) : View(context) {

    var title: String = "总览"
        set(value) {
            field = value
            invalidate()
        }
    var subtitle: String = "空间透镜 · 实时存储透视"
        set(value) {
            field = value
            invalidate()
        }
    var onLongPress: (() -> Unit)? = null
    var showBrand: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private val longPress = Runnable {
        if (pressed) {
            pressed = false
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPress?.invoke()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = Draw.dp(context, 64f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val density = resources.displayMetrics.density
        val left = Breakpoints.metrics(context).marginDp * density
        val mark = 34f * density
        val markTop = (height - mark) / 2f

        // 品牌标记：手机显示；平板交给左侧 NavRail，避免重复
        val titleX: Float
        if (showBrand) {
            val markRect = RectF(left, markTop, left + mark, markTop + mark)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                    markRect.left, markRect.top, markRect.right, markRect.bottom,
                    intArrayOf(p.brand, p.brand2), null, Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(markRect, 10f * density, 10f * density, paint)
            paint.shader = null
            paint.color = 0xFFFFFFFF.toInt()
            val tile = mark * 0.19f
            val gap = mark * 0.055f
            val ox = markRect.left + mark * 0.20f
            val oy = markRect.top + mark * 0.20f
            val bigW = tile * 2 + gap
            canvas.drawRoundRect(ox, oy, ox + bigW, oy + tile, 2f * density, 2f * density, paint)
            canvas.drawRoundRect(ox, oy + tile + gap, ox + tile, oy + tile * 2 + gap,
                    2f * density, 2f * density, paint)
            canvas.drawRoundRect(
                    ox + tile + gap, oy + tile + gap,
                    ox + tile * 2 + gap, oy + tile * 2 + gap,
                    2f * density, 2f * density, paint
            )
            titleX = left + mark + 12f * density
        } else {
            titleX = left
        }

        val titlePaint = Draw.text(context, 20f, p.text, serif = true)
        canvas.drawText(title, titleX, height / 2f + 1f * density, titlePaint)
        val subPaint = Draw.text(context, 10.5f, p.muted)
        canvas.drawText(subtitle, titleX, height / 2f + 15f * density, subPaint)

        // 本地/回收站徽章
        val chipW = 62f * density
        val chipH = 28f * density
        val chip = RectF(width - left - chipW, (height - chipH) / 2f,
                width - left, (height + chipH) / 2f)
        Draw.card(canvas, chip, chipH / 2f, Theme.alpha(p.brandSoft, if (p.dark) 210 else 255), p.line)
        Draw.icon(canvas, Icon.SHIELD, chip.left + 15f * density, chip.centerY(),
                14f * density, p.brand, 1.6f * density)
        val chipText = Draw.text(context, 10.5f, p.brand, medium = true)
        canvas.drawText("本地", chip.left + 25f * density, chip.centerY() + 3.6f * density, chipText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = true
                handler.postDelayed(longPress, 1500L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed && (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop)) {
                    pressed = false
                    handler.removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                handler.removeCallbacks(longPress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
