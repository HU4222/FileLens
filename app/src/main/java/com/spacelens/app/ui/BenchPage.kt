package com.spacelens.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.spacelens.app.bench.BenchEngine
import java.util.Locale
import kotlin.math.max

/** 测速页：5/10 秒顺序读写，实时速度 + 平均速度 + BenchGrade 分级。 */
class BenchPage(context: Context) : FrameLayout(context) {

    var onStart: ((Int) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val speedText = TextView(context)
    private val speedUnit = TextView(context)
    private val phaseText = TextView(context)
    private val timeText = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val writeValue = TextView(context)
    private val readValue = TextView(context)
    private val randomValue = TextView(context)
    private val randomUnit = TextView(context)
    private val gradeText = TextView(context)
    private val conclusion = TextView(context)
    private val note = TextView(context)
    private val startBtn = Button(context)
    private val chip5 = TextView(context)
    private val chip10 = TextView(context)

    private var durationSec = 10
    private var running = false
    private var result: BenchEngine.Result? = null
    private var lastTotalMs = 10000L

    init {
        setBackgroundColor(Theme.palette.bg)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(12f), dp(18f), dp(16f))
        }
        scroll.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content.addView(TextView(context).apply {
            text = "存储测速"
            textSize = 20f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        })
        content.addView(TextView(context).apply {
            text = "顺序读写 + 4K 随机 · 按时间跑满"
            textSize = 12f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(4f), 0, dp(10f))
        })

        content.addView(TextView(context).apply {
            text = "测试时长"
            textSize = 12f
            setTextColor(Theme.palette.text2)
        })
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, dp(10f))
        }
        for ((label, seconds, chip) in arrayOf(
                Triple("5 秒", 5, chip5),
                Triple("10 秒", 10, chip10),
        )) {
            chip.text = label
            chip.textSize = 11.5f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(16f), dp(7f), dp(16f), dp(7f))
            chip.setOnClickListener {
                if (running) return@setOnClickListener
                durationSec = seconds
                styleChips()
                if (result == null) {
                    lastTotalMs = seconds * 1000L
                    timeText.text = String.format(Locale.US, "0.0 / %.1f s", seconds.toFloat())
                }
            }
            val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(8f)
            chips.addView(chip, lp)
        }
        content.addView(chips)
        styleChips()

        val speedCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(14f))
            background = rounded(Theme.palette.panel, Theme.palette.line, Radius.PANEL)
        }
        phaseText.apply {
            text = "准备"
            textSize = 12f
            setTextColor(Theme.palette.muted)
        }
        speedCard.addView(phaseText)
        speedText.apply {
            text = "—"
            textSize = 44f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.brand)
            gravity = Gravity.CENTER
        }
        val slp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(2f)
        speedCard.addView(speedText, slp)
        speedUnit.apply {
            text = "MB/s · 实时"
            textSize = 11f
            setTextColor(Theme.palette.muted)
            gravity = Gravity.CENTER
        }
        speedCard.addView(speedUnit)
        timeText.apply {
            text = "0.0 / 10.0 s"
            textSize = 12f
            setTextColor(Theme.palette.text2)
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, dp(4f))
        }
        speedCard.addView(timeText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        progress.max = 1000
        progress.progress = 0
        progress.progressTintList = android.content.res.ColorStateList.valueOf(Theme.palette.brand)
        speedCard.addView(progress, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5f)))
        val speedLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        speedLp.topMargin = dp(10f)
        content.addView(speedCard, speedLp)

        val results = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12f), 0, 0)
        }
        content.addView(results, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        results.addView(resultCard("平均写入", writeValue))
        results.addView(resultCard("平均读取", readValue))
        results.addView(resultCard("4K 随机（IOPS）", randomValue, randomUnit, "IOPS"))

        gradeText.apply {
            text = "尚未测试"
            textSize = 13f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(12f), 0, 0)
        }
        content.addView(gradeText)
        conclusion.apply {
            text = "选择 5 秒或 10 秒，点开始测速。"
            textSize = 12f
            setTextColor(Theme.palette.text2)
            setPadding(0, dp(4f), 0, 0)
        }
        content.addView(conclusion)
        note.apply {
            text = "测试文件只写在 cacheDir，结束即删。"
            textSize = 10.5f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(4f), 0, dp(12f))
        }
        content.addView(note)

        startBtn.apply {
            text = "开始测速"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
            setOnClickListener {
                if (running) onCancel?.invoke() else onStart?.invoke(durationSec)
            }
        }
        content.addView(startBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46f)))
    }

    fun setRunning(running: Boolean) {
        this.running = running
        startBtn.text = if (running) "取消测速" else "开始测速"
        startBtn.background = rounded(
                if (running) Theme.palette.danger else Theme.palette.brand,
                if (running) Theme.palette.danger else Theme.palette.brand,
                Radius.PILL)
        chip5.isEnabled = !running
        chip10.isEnabled = !running
        chip5.alpha = if (running) 0.45f else 1f
        chip10.alpha = if (running) 0.45f else 1f
        if (running) {
            progress.progress = 0
            phaseText.text = "准备"
            speedText.text = "0.0"
            speedUnit.text = "MB/s · 实时"
        }
    }

    fun setProgress(p: BenchEngine.Progress) {
        lastTotalMs = p.totalMs
        phaseText.text = p.phase
        speedText.text = String.format(Locale.US, "%.1f", p.instantMBps)
        timeText.text = String.format(Locale.US, "%.1f / %.1f s",
                p.elapsedMs / 1000f, p.totalMs / 1000f)
        progress.progress = (p.fraction * 1000f).toInt().coerceIn(0, 1000)
    }

    fun setResult(r: BenchEngine.Result) {
        result = r
        running = false
        startBtn.text = "再测一次"
        startBtn.background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
        chip5.isEnabled = true
        chip10.isEnabled = true
        chip5.alpha = 1f
        chip10.alpha = 1f
        phaseText.text = "完成"
        speedText.text = String.format(Locale.US, "%.1f", r.seqWriteMb)
        speedUnit.text = "MB/s · 平均写入"
        timeText.text = String.format(Locale.US, "%.1f / %.1f s",
                r.elapsedMs / 1000f, lastTotalMs / 1000f)
        progress.progress = 1000
        writeValue.text = String.format(Locale.US, "%.1f", r.seqWriteMb)
        readValue.text = String.format(Locale.US, "%.1f", r.seqReadMb)
        randomValue.text = String.format(Locale.US, "%.0f", r.randReadIops)
        randomUnit.text = String.format(Locale.US, "IOPS · %.1f MB/s", r.randReadMb)
        gradeText.text = "分级 · ${r.grade}"
        gradeText.setTextColor(Theme.palette.brand)
        conclusion.text = if (r.seqWriteMb >= 80.0) {
            String.format(Locale.US, "这块盘传大文件大概 %.0f MB/s，够用。", r.seqWriteMb)
        } else {
            String.format(Locale.US, "这块盘偏慢，传大文件约 %.0f MB/s。", r.seqWriteMb)
        }
        note.text = if (r.cacheNote.isNotEmpty()) r.cacheNote
        else "测试文件只写在 cacheDir，结束即删。"
    }

    fun clearResult() {
        result = null
        speedText.text = "—"
        speedUnit.text = "MB/s · 实时"
        phaseText.text = "准备"
        timeText.text = String.format(Locale.US, "0.0 / %.1f s", durationSec.toFloat())
        progress.progress = 0
        writeValue.text = "—"
        readValue.text = "—"
        randomValue.text = "—"
        randomUnit.text = "IOPS"
        gradeText.text = "尚未测试"
        gradeText.setTextColor(Theme.palette.muted)
        conclusion.text = "选择 5 秒或 10 秒，点开始测速。"
        note.text = "测试文件只写在 cacheDir，结束即删。"
    }

    private fun resultCard(
            label: String,
            value: TextView,
            unitView: TextView? = null,
            unitLabel: String = "MB/s",
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = rounded(Theme.palette.panelSoft, Theme.palette.line, Radius.PANEL)
            val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8f)
            layoutParams = lp
        }
        card.addView(TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(Theme.palette.muted)
        })
        value.apply {
            text = "—"
            textSize = 26f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
            setPadding(0, dp(2f), 0, 0)
        }
        card.addView(value)
        val unit = unitView ?: TextView(context)
        unit.apply {
            text = unitLabel
            textSize = 10f
            setTextColor(Theme.palette.muted)
        }
        card.addView(unit)
        return card
    }

    private fun styleChips() {
        for ((seconds, chip) in arrayOf(5 to chip5, 10 to chip10)) {
            val active = seconds == durationSec
            chip.setTextColor(if (active) Color.WHITE else Theme.palette.text2)
            chip.background = rounded(
                    if (active) Theme.palette.brand else Theme.palette.panelSunken,
                    if (active) Theme.palette.brand else Theme.palette.line,
                    Radius.PILL)
        }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(max(1, dp(1f)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
