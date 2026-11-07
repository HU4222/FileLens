package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup

/** 启动标识：420ms，缩放 0.86→1 + 淡入，结束后自移除。 */
class SplashView(context: Context) : View(context) {

    private var alpha = 0f
    private var scale = 0.86f
    private var loggedDraw = false
    var onDone: (() -> Unit)? = null

    init {
        setBackgroundColor(Theme.palette.bg)
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            Motion.tween(420, Ease::outCubic, { t ->
                alpha = t
                scale = 0.86f + 0.14f * t
                invalidate()
            }, {
                (parent as? ViewGroup)?.removeView(this)
                onDone?.invoke()
            })
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!loggedDraw) {
            loggedDraw = true
            android.util.Log.i("SpaceLens", "splash first draw at " +
                    android.os.SystemClock.uptimeMillis() + "ms")
        }
        val p = Theme.palette
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * 0.46f
        val size = 84f * d * scale

        canvas.save()
        canvas.translate(cx, cy)
        val layer = canvas.saveLayerAlpha(-size, -size, size, size, (alpha * 255).toInt())
        val mark = RectF(-size / 2f, -size / 2f, size / 2f, size / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(mark.left, mark.top, mark.right, mark.bottom,
                intArrayOf(p.brand, p.brand2), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(mark, 24f * d * scale, 24f * d * scale, paint)
        paint.shader = null
        paint.color = 0xFFFFFFFF.toInt()
        val t = size * 0.19f
        val g = size * 0.055f
        val ox = -size * 0.30f
        val oy = -size * 0.30f
        val r = 4f * d
        canvas.drawRoundRect(ox, oy, ox + t * 2 + g, oy + t, r, r, paint)
        canvas.drawRoundRect(ox, oy + t + g, ox + t, oy + t * 2 + g, r, r, paint)
        canvas.drawRoundRect(ox + t + g, oy + t + g, ox + t * 2 + g, oy + t * 2 + g, r, r, paint)
        canvas.restoreToCount(layer)

        val name = Draw.text(context, 24f, p.text, medium = true, align = Paint.Align.CENTER)
        name.alpha = (alpha * 255).toInt()
        canvas.drawText("SpaceLens", cx, cy + size * 0.5f + 42f * d, name)
        val sub = Draw.text(context, 11f, p.muted, align = Paint.Align.CENTER)
        sub.alpha = (alpha * 200).toInt()
        canvas.drawText("空间透镜", cx, cy + size * 0.5f + 62f * d, sub)
        canvas.restore()
    }
}
