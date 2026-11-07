package com.spacelens.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/** 伪玻璃：半透明底色 + 顶部高光渐变 + 底部内阴影 + 亮边。 */
object Glass {

    const val RADIUS_DP = 18f

    fun radius(): Float = RADIUS_DP

    fun fill(dark: Boolean = Theme.palette.dark): Int =
            if (dark) Color.argb(26, 255, 255, 255) else Color.argb(158, 255, 255, 255)

    fun stroke(dark: Boolean = Theme.palette.dark): Int =
            if (dark) Color.argb(71, 255, 255, 255) else Color.argb(20, 15, 23, 42)

    fun highlight(dark: Boolean = Theme.palette.dark): Int =
            if (dark) Color.argb(77, 255, 255, 255) else Color.argb(217, 255, 255, 255)

    fun draw(
            canvas: Canvas,
            rect: RectF,
            radiusPx: Float,
            dark: Boolean,
            baseFill: Int? = null,
            density: Float = 1f,
    ) {
        val fill = baseFill ?: fill(dark)
        val border = stroke(dark)
        val topLight = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(120, 255, 255, 255)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = fill
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        canvas.save()
        val clip = Path().apply { addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW) }
        canvas.clipPath(clip)

        // 顶部 30% 高光带
        val top = RectF(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.30f)
        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, top.top, 0f, top.bottom,
                    topLight, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(top, topPaint)

        // 底部 2dp 内阴影
        val bottomH = max(1f, rect.height() * 0.03f)
        val bottom = RectF(rect.left, rect.bottom - bottomH, rect.right, rect.bottom)
        val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, bottom.top, 0f, bottom.bottom,
                    Color.TRANSPARENT, Color.argb(26, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(bottom, bottomPaint)
        canvas.restore()

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 0.5f * density)
            color = border
        }
        canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)

        // 顶部 1px 亮边
        val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = highlight(dark)
            strokeWidth = 1f
        }
        canvas.drawLine(rect.left + radiusPx, rect.top + 0.5f,
                rect.right - radiusPx, rect.top + 0.5f, hi)
    }

    fun drawable(
            radiusPx: Float,
            dark: Boolean = Theme.palette.dark,
            density: Float = 1f,
    ): Drawable =
            object : Drawable() {
                override fun draw(canvas: Canvas) {
                    val r = RectF(bounds)
                    if (!r.isEmpty) Glass.draw(canvas, r, radiusPx, dark, density = density)
                }

                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
}
