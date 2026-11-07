package com.spacelens.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** 传输占位页：下一版复用 DropLink，不展示任何假进度或假设备。 */
class TransferPage(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28f), dp(24f), dp(28f), dp(24f))
        setBackgroundColor(Color.TRANSPARENT)

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(26f), dp(24f), dp(26f))
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.PANEL).toFloat()
                setColor(Theme.palette.panelSoft)
                setStroke(max(1, dp(1f)), Theme.palette.line)
            }
        }

        card.addView(TextView(context).apply {
            text = "局域网互传将在下一版接入（复用 DropLink）"
            textSize = 15f
            typeface = Draw.SANS_MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text)
            setLineSpacing(dp(5f).toFloat(), 1f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        card.addView(TextView(context).apply {
            text = "这一版先把扫描、搜索、清理三条链路跑通。"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(10f), 0, dp(22f))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        card.addView(Button(context).apply {
            text = "选择设备（未开放）"
            textSize = 12f
            isEnabled = false
            setTextColor(Theme.palette.muted)
            background = GradientDrawable().apply {
                cornerRadius = dp(Radius.PILL).toFloat()
                setColor(Theme.palette.panelSunken)
                setStroke(max(1, dp(1f)), Theme.palette.line)
            }
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
