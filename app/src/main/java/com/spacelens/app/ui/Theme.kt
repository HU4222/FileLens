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
 * FileLens 视觉 token 的 Android 侧唯一来源。
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

/** FileLens 视觉圆角（dp）。卡片/列表/按钮只从这里取，不散落魔法数字。 */
object Radius {
    const val PANEL = 18f
    const val SM = 12f
    const val PILL = 999f
}

object Theme {

    val LIGHT = Palette(
            dark = false,
            bg = 0xFFF3F5FB.toInt(),
            panel = 0xFFFFFFFF.toInt(),
            panelSoft = 0xFFF7F9FD.toInt(),
            panelSunken = 0xFFEEF1F8.toInt(),
            text = 0xFF0F172A.toInt(),
            text2 = 0xFF334155.toInt(),
            muted = 0xFF8B97AD.toInt(),
            line = 0xFFE7EBF4.toInt(),
            lineStrong = 0xFFD9E0EE.toInt(),
            brand = 0xFF4F46E5.toInt(),
            brand2 = 0xFF7C3AED.toInt(),
            brandSoft = 0xFFEEF0FF.toInt(),
            danger = 0xFFE11D48.toInt(),
            ok = 0xFF10B981.toInt(),
            warn = 0xFFF59E0B.toInt(),
            bgGlow = 0xFFE9EDFF.toInt(),
            scrim = 0x660F172A,
            category = intArrayOf(
                    0xFFF59E0B.toInt(), // 图片
                    0xFFE11D48.toInt(), // 视频
                    0xFF8B5CF6.toInt(), // 音频
                    0xFF3B82F6.toInt(), // 文档
                    0xFF10B981.toInt(), // 安装包
                    0xFF0EA5E9.toInt(), // 压缩包
                    0xFF8B97AD.toInt(), // 其他
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
            bg = 0xFF0B1020.toInt(),
            panel = 0xFF141A2E.toInt(),
            panelSoft = 0xFF19203A.toInt(),
            panelSunken = 0xFF101627.toInt(),
            text = 0xFFEEF2FF.toInt(),
            text2 = 0xFFB9C2DA.toInt(),
            muted = 0xFF7F8AA6.toInt(),
            line = 0xFF232B45.toInt(),
            lineStrong = 0xFF2F395A.toInt(),
            brand = 0xFF8B87FF.toInt(),
            brand2 = 0xFFB18CFF.toInt(),
            brandSoft = 0xFF1E2450.toInt(),
            danger = 0xFFFF6B8A.toInt(),
            ok = 0xFF10B981.toInt(),
            warn = 0xFFF59E0B.toInt(),
            bgGlow = 0xFF111832.toInt(),
            scrim = 0x990B1020.toInt(),
            category = intArrayOf(
                    0xFFFBBF24.toInt(),
                    0xFFFF6B8A.toInt(),
                    0xFFB18CFF.toInt(),
                    0xFF60A5FA.toInt(),
                    0xFF34D399.toInt(),
                    0xFF38BDF8.toInt(),
                    0xFF7F8AA6.toInt(),
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
