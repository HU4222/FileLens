package com.spacelens.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** 深浅主题模式。持久化在 SharedPreferences。 */
enum class ThemeMode(val stored: Int, val label: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "浅色"),
    DARK(2, "深色");

    fun next(): ThemeMode = when (this) {
        SYSTEM -> LIGHT
        LIGHT -> DARK
        DARK -> SYSTEM
    }

    companion object {
        fun from(v: Int): ThemeMode = entries.firstOrNull { it.stored == v } ?: SYSTEM
    }
}

/**
 * DropLink 视觉 token 的 Android 侧唯一来源。
 * 所有自绘 View 读取 [palette]，禁止在 View 内散落十六进制颜色。
 */
data class Palette(
        val dark: Boolean,
        val bg: Int,
        val panel: Int,
        val panelSoft: Int,
        val panelSunken: Int,
        val text: Int,
        val text2: Int,
        val muted: Int,
        val line: Int,
        val lineStrong: Int,
        val brand: Int,
        val brand2: Int,
        val brandSoft: Int,
        val danger: Int,
        val ok: Int,
        val warn: Int,
        val bgGlow: Int,
        val scrim: Int,
        val category: IntArray,
) {
    fun colorFor(index: Int): Int = category[index % category.size]
}

object Theme {

    val LIGHT = Palette(
            dark = false,
            bg = 0xFFF2F3F5.toInt(),
            panel = 0xFFFFFFFF.toInt(),
            panelSoft = 0xFFF7F8F9.toInt(),
            panelSunken = 0xFFECEEF0.toInt(),
            text = 0xFF121416.toInt(),
            text2 = 0xFF4B5157.toInt(),
            muted = 0xFF8A9096.toInt(),
            line = 0xFFE4E6E8.toInt(),
            lineStrong = 0xFFD8DBDE.toInt(),
            brand = 0xFF0FA98E.toInt(),
            brand2 = 0xFF4EE1C1.toInt(),
            brandSoft = 0xFFD9F7F1.toInt(),
            danger = 0xFFD94A5A.toInt(),
            ok = 0xFF78D800.toInt(),
            warn = 0xFFF0A818.toInt(),
            bgGlow = 0xFFE6F7F4.toInt(),
            scrim = 0x55000000,
            category = intArrayOf(
                    0xFFF0A818.toInt(), // 图片
                    0xFFD94A5A.toInt(), // 视频
                    0xFF9030D8.toInt(), // 音频
                    0xFF4890F0.toInt(), // 文档
                    0xFF78D800.toInt(), // 安装包
                    0xFF22C9B8.toInt(), // 压缩包
                    0xFF6B7076.toInt(), // 其他
            ),
    )

    private fun darkAlpha(base: Int, delta: Int): Int {
        val r = (Color.red(base) + delta).coerceIn(0, 255)
        val g = (Color.green(base) + delta).coerceIn(0, 255)
        val b = (Color.blue(base) + delta).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    val DARK = Palette(
            dark = true,
            bg = 0xFF121416.toInt(),
            panel = 0xFF1E2125.toInt(),
            panelSoft = 0xFF262A2F.toInt(),
            panelSunken = 0xFF16181B.toInt(),
            text = 0xFFE6E8EA.toInt(),
            text2 = 0xFF9AA0A6.toInt(),
            muted = 0xFF6B7076.toInt(),
            line = 0x0FFFFFFF,
            lineStrong = 0xFF2A2E33.toInt(),
            brand = 0xFF4EE1C1.toInt(),
            brand2 = 0xFF7CF0D8.toInt(),
            brandSoft = 0xFF173E38.toInt(),
            danger = 0xFFD94A5A.toInt(),
            ok = 0xFF78D800.toInt(),
            warn = 0xFFF0A818.toInt(),
            bgGlow = 0xFF142B27.toInt(),
            scrim = 0x99000000.toInt(),
            category = intArrayOf(
                    0xFFF0A818.toInt(),
                    0xFFD94A5A.toInt(),
                    0xFF9030D8.toInt(),
                    0xFF4890F0.toInt(),
                    0xFF78D800.toInt(),
                    0xFF22C9B8.toInt(),
                    0xFF6B7076.toInt(),
            ),
    )
    @Volatile
    var mode: ThemeMode = ThemeMode.SYSTEM
        private set

    @Volatile
    var palette: Palette = LIGHT
        private set

    fun init(context: Context, mode: ThemeMode) {
        this.mode = mode
        palette = resolve(context, mode)
    }

    fun resolve(context: Context, mode: ThemeMode): Palette = when (mode) {
        ThemeMode.LIGHT -> LIGHT
        ThemeMode.DARK -> DARK
        ThemeMode.SYSTEM -> if (isSystemDark(context)) DARK else LIGHT
    }

    fun setMode(context: Context, mode: ThemeMode) {
        this.mode = mode
        palette = resolve(context, mode)
    }

    fun systemChanged(context: Context) {
        if (mode == ThemeMode.SYSTEM) palette = resolve(context, mode)
    }

    private fun isSystemDark(context: Context): Boolean {
        val ui = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return ui == Configuration.UI_MODE_NIGHT_YES
    }

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or ((a.coerceIn(0, 255)) shl 24)

    fun blend(from: Int, to: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * u).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * u).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * u).toInt()
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * u).toInt()
        return Color.argb(a, r, g, b)
    }

    /** 层级明度：浅色每层 +8%，深色每层 -8%。 */
    fun level(color: Int, depth: Int): Int {
        val step = if (palette.dark) -8 else 8
        return darkAlpha(color, (depth.coerceIn(0, 6)) * step)
    }
}
