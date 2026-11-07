package com.spacelens.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 传输占位页：下一版复用 DropLink，不展示任何假进度或假设备。 */
class TransferPage(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32f), dp(24f), dp(32f), dp(24f))
        setBackgroundColor(Color.TRANSPARENT)

        addView(TextView(context).apply {
            text = "局域网互传将在下一版接入（复用 DropLink）"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text)
            setLineSpacing(dp(5f).toFloat(), 1f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(TextView(context).apply {
            text = "这一版先把扫描、搜索、清理三条链路跑通。"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(10f), 0, dp(24f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(Button(context).apply {
            text = "选择设备（未开放）"
            textSize = 12f
            isEnabled = false
            setTextColor(Theme.palette.muted)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Theme.palette.panelSunken)
                setStroke(dp(1f), Theme.palette.line)
            }
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
