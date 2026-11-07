package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** 全局背景：DropLink 的径向渐变 + 底部品牌微光。 */
class BackdropView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val p = Theme.palette
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(p.bg)

    }
}
