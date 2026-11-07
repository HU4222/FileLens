package com.spacelens.app.ui

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import kotlin.math.max

/** 统一玻璃质感：API 31+ 真 RenderEffect 模糊，26–30 只做磨砂半透明。 */
object Glass {

    const val RADIUS_DP = 18f

    fun radius(): Float = RADIUS_DP

    /** API 31+ 更透，旧系统更实一点补偿没有模糊。 */
    fun fill(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color.argb(26, 255, 255, 255)
    } else {
        Color.argb(36, 255, 255, 255)
    }

    fun stroke(): Int = Color.argb(71, 255, 255, 255)

    fun highlight(): Int = Color.argb(120, 255, 255, 255)

    fun background(
            fill: Int = fill(),
            stroke: Int = stroke(),
            radiusPx: Float,
    ): GradientDrawable = GradientDrawable().apply {
        cornerRadius = max(0f, radiusPx)
        setColor(fill)
        setStroke(1, stroke)
    }

    fun applyBlur(view: View, radiusPx: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val r = radiusPx.coerceIn(18f, 22f)
        view.setRenderEffect(
                RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
    }
}
