package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

enum class Icon {
    OVERVIEW, TREE, GAUGE, USER, FOLDER, SPEED, HEALTH, CHEVRON, SHIELD,
    MOON, SUN, INFO, TRASH, SEARCH, LIST, RING, BACK, LOCK, CALENDAR, REFRESH, CLOSE,
}

object Draw {

    val SANS: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val SANS_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val SANS_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density
    fun sp(context: Context, v: Float): Float = v * context.resources.displayMetrics.scaledDensity

    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun text(
            context: Context,
            sizeSp: Float,
            color: Int,
            medium: Boolean = false,
            align: Paint.Align = Paint.Align.LEFT,
            serif: Boolean = false,
    ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = when {
            serif -> android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
            medium -> SANS_MEDIUM
            else -> SANS
        }
        textSize = sp(context, sizeSp)
        this.color = color
        textAlign = align
        fontFeatureSettings = "tnum"
    }

    fun lineText(
            canvas: Canvas,
            text: String,
            x: Float,
            baseline: Float,
            paint: Paint,
    ) {
        canvas.drawText(text, x, baseline, paint)
    }

    fun card(canvas: Canvas, rect: RectF, radius: Float, fillColor: Int, lineColor: Int, lineWidth: Float = 1f) {
        val fill = fill(fillColor)
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (lineWidth > 0f) {
            val stroke = stroke(lineColor, lineWidth)
            val inset = lineWidth / 2f
            val r = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            canvas.drawRoundRect(r, radius - inset, radius - inset, stroke)
        }
    }

    fun icon(
            canvas: Canvas,
            icon: Icon,
            cx: Float,
            cy: Float,
            size: Float,
            color: Int,
            strokeWidthPx: Float = size / 12f,
    ) {
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        val s = size / 24f
        canvas.scale(s, s)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = (strokeWidthPx / s).coerceAtLeast(1.2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        drawIconPath(canvas, icon, paint)
        canvas.restore()
    }

    private fun drawIconPath(canvas: Canvas, icon: Icon, p: Paint) {
        val path = Path()
        when (icon) {
            Icon.OVERVIEW -> {
                canvas.drawRoundRect(3f, 3f, 10.5f, 10.5f, 2.4f, 2.4f, p)
                canvas.drawRoundRect(13.5f, 3f, 21f, 10.5f, 2.4f, 2.4f, p)
                canvas.drawRoundRect(3f, 13.5f, 10.5f, 21f, 2.4f, 2.4f, p)
                canvas.drawRoundRect(13.5f, 13.5f, 21f, 21f, 2.4f, 2.4f, p)
            }
            Icon.TREE -> {
                canvas.drawRoundRect(3f, 4f, 21f, 20f, 2.5f, 2.5f, p)
                canvas.drawLine(3f, 9f, 21f, 9f, p)
                canvas.drawLine(10f, 9f, 10f, 20f, p)
                canvas.drawLine(10f, 14f, 21f, 14f, p)
            }
            Icon.GAUGE -> {
                val r = RectF(3f, 5f, 21f, 23f)
                canvas.drawArc(r, 180f, 180f, false, p)
                canvas.drawLine(12f, 14f, 16.5f, 10.5f, p)
                canvas.drawCircle(12f, 14f, 1.6f, p)
            }
            Icon.USER -> {
                canvas.drawCircle(12f, 8f, 3.6f, p)
                val r = RectF(4.5f, 14f, 19.5f, 26f)
                canvas.drawArc(r, 180f, 180f, false, p)
            }
            Icon.FOLDER -> {
                path.moveTo(3f, 7f)
                path.lineTo(9f, 7f)
                path.lineTo(11f, 9.5f)
                path.lineTo(21f, 9.5f)
                path.lineTo(21f, 19f)
                path.lineTo(3f, 19f)
                path.close()
                canvas.drawPath(path, p)
            }
            Icon.SPEED -> {
                path.moveTo(13f, 2.5f)
                path.lineTo(5.5f, 13.5f)
                path.lineTo(11f, 13.5f)
                path.lineTo(10f, 21.5f)
                path.lineTo(18.5f, 10f)
                path.lineTo(12.8f, 10f)
                path.close()
                canvas.drawPath(path, p)
            }
            Icon.HEALTH -> {
                path.moveTo(3f, 13f)
                path.lineTo(7.5f, 13f)
                path.lineTo(9.5f, 9f)
                path.lineTo(12.5f, 17f)
                path.lineTo(15f, 13f)
                path.lineTo(21f, 13f)
                canvas.drawPath(path, p)
            }
            Icon.CHEVRON -> {
                path.moveTo(9f, 5f)
                path.lineTo(16f, 12f)
                path.lineTo(9f, 19f)
                canvas.drawPath(path, p)
            }
            Icon.BACK -> {
                path.moveTo(15f, 5f)
                path.lineTo(8f, 12f)
                path.lineTo(15f, 19f)
                canvas.drawPath(path, p)
            }
            Icon.SHIELD -> {
                path.moveTo(12f, 2.5f)
                path.lineTo(20f, 6f)
                path.lineTo(20f, 12f)
                path.cubicTo(20f, 17f, 16.5f, 20f, 12f, 21.5f)
                path.cubicTo(7.5f, 20f, 4f, 17f, 4f, 12f)
                path.lineTo(4f, 6f)
                path.close()
                canvas.drawPath(path, p)
            }
            Icon.MOON -> {
                path.moveTo(20f, 15f)
                path.cubicTo(18.7f, 15.6f, 17.3f, 16f, 15.8f, 16f)
                path.cubicTo(10.4f, 16f, 6f, 11.6f, 6f, 6.2f)
                path.cubicTo(6f, 5.3f, 6.1f, 4.4f, 6.4f, 3.6f)
                path.cubicTo(3.5f, 5.2f, 2f, 8.3f, 2f, 11.7f)
                path.cubicTo(2f, 17.4f, 6.6f, 22f, 12.3f, 22f)
                path.cubicTo(16f, 22f, 19.2f, 20f, 20f, 15f)
                path.close()
                path.fillType = Path.FillType.EVEN_ODD
                canvas.drawPath(path, p)
            }
            Icon.SUN -> {
                canvas.drawCircle(12f, 12f, 4.4f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val x1 = 12f + (6.6f * Math.cos(a)).toFloat()
                    val y1 = 12f + (6.6f * Math.sin(a)).toFloat()
                    val x2 = 12f + (9.5f * Math.cos(a)).toFloat()
                    val y2 = 12f + (9.5f * Math.sin(a)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, p)
                }
            }
            Icon.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, p)
                canvas.drawLine(12f, 11f, 12f, 17f, p)
                canvas.drawPoint(12f, 7.6f, p)
            }
            Icon.TRASH -> {
                canvas.drawLine(4f, 6.5f, 20f, 6.5f, p)
                canvas.drawRoundRect(6.5f, 6.5f, 17.5f, 20f, 2f, 2f, p)
                canvas.drawLine(9.5f, 3.5f, 14.5f, 3.5f, p)
                canvas.drawLine(10f, 10.5f, 10f, 17f, p)
                canvas.drawLine(14f, 10.5f, 14f, 17f, p)
            }
            Icon.SEARCH -> {
                canvas.drawCircle(10.5f, 10.5f, 6.2f, p)
                canvas.drawLine(15f, 15f, 20.5f, 20.5f, p)
            }
            Icon.LIST -> {
                canvas.drawLine(9f, 6f, 20.5f, 6f, p)
                canvas.drawLine(9f, 12f, 20.5f, 12f, p)
                canvas.drawLine(9f, 18f, 20.5f, 18f, p)
                canvas.drawCircle(4.6f, 6f, 1.1f, p)
                canvas.drawCircle(4.6f, 12f, 1.1f, p)
                canvas.drawCircle(4.6f, 18f, 1.1f, p)
            }
            Icon.RING -> {
                canvas.drawCircle(12f, 12f, 8f, p)
                canvas.drawCircle(12f, 12f, 4.2f, p)
            }
            Icon.LOCK -> {
                canvas.drawRoundRect(5f, 10f, 19f, 21f, 2.5f, 2.5f, p)
                path.moveTo(8f, 10f)
                path.lineTo(8f, 7.5f)
                path.cubicTo(8f, 3.8f, 16f, 3.8f, 16f, 7.5f)
                path.lineTo(16f, 10f)
                canvas.drawPath(path, p)
            }
            Icon.CALENDAR -> {
                canvas.drawRoundRect(3.5f, 5f, 20.5f, 21f, 2.5f, 2.5f, p)
                canvas.drawLine(3.5f, 10f, 20.5f, 10f, p)
                canvas.drawLine(8f, 2.8f, 8f, 6.5f, p)
                canvas.drawLine(16f, 2.8f, 16f, 6.5f, p)
            }
            Icon.REFRESH -> {
                val r = RectF(4f, 4f, 20f, 20f)
                canvas.drawArc(r, -40f, 280f, false, p)
                path.moveTo(19f, 3.5f)
                path.lineTo(19.6f, 8.6f)
                path.lineTo(14.6f, 7.6f)
                canvas.drawPath(path, p)
            }
            Icon.CLOSE -> {
                canvas.drawLine(5f, 5f, 19f, 19f, p)
                canvas.drawLine(19f, 5f, 5f, 19f, p)
            }
        }
    }

    /** 文本裁剪，尾部用省略号。 */
    fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val dots = "…"
        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end) + dots
            if (paint.measureText(candidate) <= maxWidth) return candidate
            end--
        }
        return dots
    }

    /** 分类色降饱和：factor=0.86 时向灰轴收 14%，避免纯色刺眼。 */
    fun desaturate(color: Int, factor: Float): Int {
        val lum = (Color.red(color) * 0.299f + Color.green(color) * 0.587f +
                Color.blue(color) * 0.114f).toInt().coerceIn(0, 255)
        val gray = Color.rgb(lum, lum, lum)
        return Theme.blend(color, gray, (1f - factor).coerceIn(0f, 1f))
    }
    fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color),
    )

    fun mix(a: Int, b: Int, t: Float): Int = Theme.blend(a, b, t)
}
