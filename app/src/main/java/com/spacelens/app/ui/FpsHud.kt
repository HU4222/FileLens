package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.WindowManager
import android.view.View

/** 帧率 HUD：长按标题开关；显示实际刷新率 + 实测 FPS + 长帧数。 */
class FpsHudView(context: Context) : View(context) {

    private var showing = false
    private var engine: Motion.Animation? = null
    private var frames = 0
    private var sumMs = 0f
    private var longFrames = 0
    private var windowStart = 0L
    private var fps = 0f
    private var avgMs = 0f
    private var p95Ms = 0f

    private val panel = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    private fun readRefreshRate(context: Context): Float {
        return try {
            val d = if (android.os.Build.VERSION.SDK_INT >= 30) context.display
            else (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            d?.refreshRate ?: 60f
        } catch (_: Throwable) {
            60f
        }
    }

    fun setShowing(show: Boolean) {
        showing = show
        visibility = if (show) VISIBLE else GONE
        if (show) start() else stop()
    }

    fun isShowing(): Boolean = showing

    private fun start() {
        stopEngine()
        frames = 0
        sumMs = 0f
        longFrames = 0
        windowStart = SystemClock.uptimeMillis()
        engine = Motion.add(object : Motion.FrameListener {
            override fun onFrame(frameTimeNanos: Long, dtMs: Float): Boolean {
                if (!showing) return false
                frames++
                sumMs += dtMs
                if (dtMs > 32f) longFrames++
                val now = SystemClock.uptimeMillis()
                if (now - windowStart >= 500L) {
                    fps = FrameStats.fps()
                    avgMs = FrameStats.avgMs()
                    p95Ms = FrameStats.p95Ms()
                    longFrames = FrameStats.longFrames()
                    invalidate()
                    frames = 0
                    sumMs = 0f
                    windowStart = now
                }
                return true
            }
        })
        invalidate()
    }

    private fun stop() {
        stopEngine()
        invalidate()
    }

    private fun stopEngine() {
        engine?.cancel()
        engine = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Draw.dp(context, 198f).toInt(), Draw.dp(context, 62f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        val p = Theme.palette
        val d = resources.displayMetrics.density
        panel.set(0f, 0f, width.toFloat(), height.toFloat())
        Draw.card(canvas, panel, Radius.SM * d, Theme.alpha(p.panel, 238), p.line)
        val refreshHz = readRefreshRate(context)
        val hzText = if (refreshHz >= 100f) "${refreshHz.toInt()}Hz" else "当前 ${refreshHz.toInt()}Hz"
        val title = Draw.text(context, 18f, p.text, medium = true)
        canvas.drawText("FPS " + String.format(java.util.Locale.US, "%.1f", fps), 12f * d, 26f * d, title)
        val sub = Draw.text(context, 9.5f, p.muted)
        canvas.drawText("$hzText · ${String.format(java.util.Locale.US, "%.1f", avgMs)}ms · P95 ${String.format(java.util.Locale.US, "%.1f", p95Ms)} · 长帧 $longFrames",
                12f * d, 46f * d, sub)
    }

    override fun onDetachedFromWindow() {
        stopEngine()
        super.onDetachedFromWindow()
    }
}
