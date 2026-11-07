package com.spacelens.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spacelens.app.core.DeletePolicy
import com.spacelens.app.core.Duplicates
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.NodeStore
import com.spacelens.app.core.Trash
import java.io.File
import kotlin.math.max

/** 清理页：重复文件分组 + 回收站删除；垃圾建议留给下一版。 */
class CleanPage(context: Context) : LinearLayout(context) {

    var store: NodeStore? = null
    var rootPath: String = ""

    private val summary = TextView(context)
    private val status = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val start = Button(context)
    private val cancel = Button(context)
    private val body = LinearLayout(context)
    private val delete = Button(context)
    private val handler = Handler(Looper.getMainLooper())

    private var groups: List<Duplicates.Group> = emptyList()
    private val selected = HashSet<Int>()
    private var running = false
    private var started = false
    private var generation = 0
    @Volatile private var cancelFlag = false

    init {
        orientation = VERTICAL
        setPadding(dp(18f), dp(6f), dp(18f), dp(8f))
        setBackgroundColor(Color.TRANSPARENT)

        summary.apply {
            text = "重复文件"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }
        addView(summary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.apply {
            text = "同 size 且 ≥1MB 的文件才会做 SHA-256；可中断。"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, dp(6f))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        progress.max = 100
        progress.progress = 0
        progress.progressTintList = android.content.res.ColorStateList.valueOf(Theme.palette.brand)
        progress.visibility = View.GONE
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(4f)))

        val tools = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(6f))
        }
        start.text = "开始检查"
        start.textSize = 12f
        start.setTextColor(Color.WHITE)
        start.background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
        start.setOnClickListener { startScan() }
        cancel.text = "取消"
        cancel.textSize = 12f
        cancel.setTextColor(Theme.palette.brand)
        cancel.background = rounded(Theme.palette.brandSoft, Theme.palette.brandSoft, Radius.PILL)
        cancel.visibility = View.GONE
        cancel.setOnClickListener {
            cancelFlag = true
            cancel.isEnabled = false
            status.text = "正在取消…"
        }
        tools.addView(start, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        tools.addView(cancel, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        body.orientation = VERTICAL
        scroll.addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        delete.apply {
            text = "删除选中（进回收站）"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.danger, Theme.palette.danger, Radius.PILL)
            setOnClickListener { confirmDelete() }
        }
        val dlp = LayoutParams(LayoutParams.MATCH_PARENT, dp(42f))
        dlp.topMargin = dp(6f)
        addView(delete, dlp)
        updateSummary()
    }

    fun onShown() {
        if (!started && !running) {
            started = true
            if ((store?.count ?: 0) > 1) startScan()
        }
    }

    fun refresh() {
        if (!running) startScan()
    }

    /** 扫描/删除后丢旧结果；下次进入清理页再算，避免后台重复哈希。 */
    fun reset() {
        generation++
        cancelFlag = true
        running = false
        started = false
        groups = emptyList()
        selected.clear()
        body.removeAllViews()
        start.isEnabled = true
        cancel.visibility = View.GONE
        progress.visibility = View.GONE
        status.text = "同 size 且 ≥1MB 的文件才会做 SHA-256；可中断。"
        updateSummary()
    }

    private fun startScan() {
        val snapshot = store
        if (snapshot == null || snapshot.count <= 1) {
            status.text = "先到「空间」页扫描一次"
            return
        }
        if (running) return
        val my = ++generation
        running = true
        cancelFlag = false
        groups = emptyList()
        selected.clear()
        body.removeAllViews()
        start.isEnabled = false
        cancel.isEnabled = true
        cancel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "准备检查…"
        updateSummary()

        Thread({
            val result = Duplicates.find(
                    snapshot,
                    rootPath,
                    onProgress = { done, total ->
                        handler.post {
                            if (!running || my != generation) return@post
                            progress.max = max(1, total)
                            progress.progress = done
                            status.text = "已检查 $done/$total 组…"
                        }
                    },
                    cancelled = { cancelFlag },
            )
            handler.post {
                if (my != generation) return@post
                running = false
                start.isEnabled = true
                cancel.visibility = View.GONE
                progress.visibility = View.GONE
                groups = result
                selected.clear()
                for (g in result) for (i in 1 until g.files.size) selected.add(g.files[i].node)
                renderGroups()
                status.text = if (cancelFlag) "已取消，结果不完整" else
                    "检查完成：${result.size} 组重复文件"
                updateSummary()
            }
        }, "FileLens-Duplicates").start()
    }

    private fun renderGroups() {
        body.removeAllViews()
        if (groups.isEmpty()) {
            val empty = TextView(context).apply {
                text = "没有发现重复文件"
                textSize = 12f
                setTextColor(Theme.palette.muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(36f), 0, 0)
            }
            body.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }
        for ((gi, group) in groups.withIndex()) {
            val header = TextView(context).apply {
                text = "第 ${gi + 1} 组 · ${Fmt.bytes(group.size)} × ${group.files.size} 份 · 可释放 ${Fmt.bytes(group.reclaim)}"
                textSize = 15f
                typeface = Draw.SANS_MEDIUM
                setTextColor(Theme.palette.text)
                setPadding(dp(4f), dp(10f), dp(4f), dp(4f))
            }
            body.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            for ((fi, file) in group.files.withIndex()) {
                val row = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                    background = rounded(Theme.palette.panel, Theme.palette.line, Radius.SM)
                }
                if (fi == 0) {
                    row.addView(TextView(context).apply {
                        text = "保留 · ${file.name}"
                        textSize = 12f
                        setTextColor(Theme.palette.text)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    })
                } else {
                    val check = CheckBox(context).apply {
                        text = file.name
                        textSize = 12f
                        setTextColor(Theme.palette.text)
                        isChecked = selected.contains(file.node)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected.add(file.node) else selected.remove(file.node)
                            updateSummary()
                        }
                    }
                    row.addView(check, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
                row.addView(TextView(context).apply {
                    text = file.path
                    textSize = 9.5f
                    setTextColor(Theme.palette.muted)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(if (fi == 0) 0 else dp(28f), 0, 0, 0)
                })
                val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4f)
                body.addView(row, lp)
            }
        }
    }

    private fun updateSummary() {
        val reclaim = groups.sumOf { it.reclaim }
        val text = "重复文件 · ${groups.size} 组 · 可释放 ${Fmt.bytes(reclaim)}"
        val span = android.text.SpannableString(text)
        val at = text.indexOf("可释放")
        if (at >= 0) {
            span.setSpan(android.text.style.ForegroundColorSpan(Theme.palette.ok),
                    at, text.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        summary.text = span
        delete.isEnabled = selected.isNotEmpty() && !running
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) {
            toast("没有选中文件")
            return
        }
        val nodes = selected.toIntArray()
        Dialogs.showDeleteConfirm(this.context as android.app.Activity,
                "删除选中文件",
                "将把 ${nodes.size} 个文件移入回收站，可恢复。\n每组第一份永远保留，不做删除。") {
            doDelete(nodes)
        }
    }

    private fun doDelete(nodes: IntArray) {
        val snapshot = store ?: return
        val targets = ArrayList<Triple<File, String, Int>>()
        for (node in nodes) {
            val decision = DeletePolicy.decide(context, snapshot, node, rootPath)
            if (!decision.selectable) {
                toast("跳过：${decision.reason}")
                continue
            }
            val abs = snapshot.absolutePathOf(rootPath, node)
            val rel = snapshot.relativePathOf(node)
            if (rel.isEmpty()) continue
            targets.add(Triple(File(abs), rel, node))
        }
        if (targets.isEmpty()) return
        delete.isEnabled = false
        status.text = "正在移入回收站…"
        Thread({
            val result = Trash.move(context, targets.map { it.first to it.second })
            val moved = targets.filter { !it.first.exists() }.map { it.third }.toIntArray()
            if (moved.isNotEmpty()) snapshot.markDeleted(moved)
            handler.post {
                toast(if (result.skipped > 0) "已移入 ${result.moved} 个，跳过 ${result.skipped} 个" else "已移入回收站 ${result.moved} 个")
                startScan()
            }
        }, "FileLens-CleanTrash").start()
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(max(1, dp(1f)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
