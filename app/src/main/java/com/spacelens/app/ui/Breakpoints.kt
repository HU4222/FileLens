package com.spacelens.app.ui

import android.content.Context

/** 8.5 平板断点：按可用宽度（wdp），不按物理尺寸。 */
enum class WindowClass {
    COMPACT,   // < 600dp 手机
    MEDIUM,    // 600 ~ 839dp
    EXPANDED,  // >= 840dp
}

data class UiMetrics(
        val marginDp: Float,
        val gapDp: Float,
        val radiusDp: Float,
        val navWidthDp: Float,
        val detailWidthDp: Float,
        val maxContentWidthDp: Float,
        val maxBlocks: Int,
        val maxDepth: Int,
) {
    val isTablet: Boolean get() = navWidthDp > 0f
}

object Breakpoints {

    const val MEDIUM_DP = 600
    const val EXPANDED_DP = 840
    const val WIDE_DP = 1200

    fun of(widthDp: Int): WindowClass = when {
        widthDp >= EXPANDED_DP -> WindowClass.EXPANDED
        widthDp >= MEDIUM_DP -> WindowClass.MEDIUM
        else -> WindowClass.COMPACT
    }

    fun of(context: Context): WindowClass =
            of(context.resources.configuration.screenWidthDp)

    fun metrics(widthDp: Int): UiMetrics = when (of(widthDp)) {
        WindowClass.COMPACT -> UiMetrics(
                marginDp = 18f,
                gapDp = 10f,
                radiusDp = 18f,
                navWidthDp = 0f,
                detailWidthDp = 0f,
                maxContentWidthDp = 0f,
                maxBlocks = 1200,
                maxDepth = 1,
        )
        WindowClass.MEDIUM -> UiMetrics(
                marginDp = 24f,
                gapDp = 16f,
                radiusDp = 20f,
                navWidthDp = 88f,
                detailWidthDp = 280f,
                maxContentWidthDp = 0f,
                maxBlocks = 1200,
                maxDepth = 1,
        )
        WindowClass.EXPANDED -> UiMetrics(
                marginDp = 24f,
                gapDp = 16f,
                radiusDp = 20f,
                navWidthDp = 88f,
                detailWidthDp = 320f,
                maxContentWidthDp = 1100f,
                maxBlocks = 1800,
                maxDepth = 2,
        )
    }

    fun metrics(context: Context): UiMetrics =
            metrics(context.resources.configuration.screenWidthDp)

    /** 详情栏：≥840 常驻；600~839 仅空间树页。 */
    fun showDetail(widthDp: Int, tab: Int): Boolean = when (of(widthDp)) {
        WindowClass.COMPACT -> false
        WindowClass.MEDIUM -> tab == 1
        WindowClass.EXPANDED -> true
    }
}
