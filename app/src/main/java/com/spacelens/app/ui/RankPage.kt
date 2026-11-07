package com.spacelens.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.NodeStore
import java.io.File
import kotlin.math.max

/** 大文件排行：复用 NodeStore，不重新扫盘。 */
class RankPage(
        context: Context,
        private val store: NodeStore,
) : LinearLayout(context) {

    var onClose: (() -> Unit)? = null
    var onPreview: ((String) -> Unit)? = null
    private val body = LinearLayout(context)
    private val title = TextView(context)
    private var limit = 50
    private var dir = 0
    private val topFiles = ArrayList<NodeStore.NodeInfo>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.palette.bg)
        setPadding(dp(18f), dp(10f), dp(18f), 0)
        val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        title.apply {
            text = "最大的文件"
            textSize = 21f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }
        bar.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(action("前 50") { limit = 50; dir = 0; rebuild() },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bar.addView(action("前 100") { limit = 100; dir = 0; rebuild() },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bar.addView(action("关闭") { onClose?.invoke() },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(52f)))
        val scroll = ScrollView(context)
        scroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        rebuild()
    }

    private fun rebuild() {
        body.removeAllViews()
        if (dir == 0) {
            if (topFiles.isEmpty()) collectTop()
            showTop()
        } else {
            showDir(dir)
        }
    }

    private fun collectTop() {
        val stack = ArrayDeque<Int>()
        stack.add(0)
        while (stack.isNotEmpty() && topFiles.size < 40000) {
            val i = stack.removeLast()
            for (c in store.childrenInfo(i)) {
                if (c.flags and NodeStore.DIR != 0) stack.add(c.index)
                else if (c.size > 0) topFiles.add(c)
            }
        }
        topFiles.sortByDescending { it.size }
    }

    private fun showTop() {
        title.text = "最大的文件"
        val top = topFiles.take(limit)
        val used = max(store.sizeOf(0), 1L)
        val sum = top.sumOf { it.size }
        body.addView(info("前 ${top.size} 占 ${Fmt.bytes(sum)} · 占已用 " +
                "%.1f%%".format(sum * 100.0 / used)))
        for (f in top) body.addView(fileRow(f, showFolder = true))
    }

    private fun showDir(index: Int) {
        val path = store.pathOf(index)
        title.text = "文件夹"
        body.addView(crumbs(path))
        val children = store.childrenInfo(index)
        val dirs = children.filter { it.flags and NodeStore.DIR != 0 }
                .sortedByDescending { it.size }.take(10)
        val files = children.filter { it.flags and NodeStore.DIR == 0 }
                .sortedByDescending { it.size }.take(20)
        val total = max(store.sizeOf(index), 1L)
        if (dirs.isNotEmpty()) {
            body.addView(info("子文件夹"))
            for (d in dirs) {
                val row = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(0, dp(7f), 0, dp(7f))
                }
                row.addView(line(d.name, Fmt.bytes(d.size), true))
                val bar = View(context).apply {
                    setBackgroundColor(Theme.palette.brand)
                }
                val width = ((d.size.toFloat() / total) * dp(300f)).toInt().coerceAtLeast(dp(2f))
                row.addView(bar, LayoutParams(width, dp(3f)).apply { topMargin = dp(5f) })
                row.setOnClickListener { dir = d.index; rebuild() }
                body.addView(row)
            }
        }
        body.addView(info("目录内最大文件"))
        for (f in files) body.addView(fileRow(f, showFolder = false))
        if (dirs.isEmpty() && files.isEmpty()) body.addView(info("这个文件夹是空的"))
    }

    private fun crumbs(path: String): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(8f))
        }
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val show = if (parts.size <= 4) parts else listOf(parts[0], "…") + parts.takeLast(2)
        row.addView(action("全部 / ") { dir = 0; rebuild() })
        for (part in show) row.addView(action("$part / ") { })
        return row
    }

    private fun fileRow(f: NodeStore.NodeInfo, showFolder: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        val icon = object : View(context) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                Draw.icon(canvas, if (f.flags and NodeStore.DIR != 0) Icon.FOLDER else Icon.LIST,
                        width / 2f, height / 2f, dp(18f).toFloat(), Theme.palette.muted,
                        dp(1.6f).toFloat())
            }
        }
        row.addView(icon, LayoutParams(dp(26f), dp(30f)))
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply {
            text = f.name
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Theme.palette.text)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        texts.addView(TextView(context).apply {
            text = store.pathOf(f.index)
            textSize = 10f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Theme.palette.muted)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = Fmt.bytes(f.size)
            textSize = 12f
            setTextColor(Theme.palette.text2)
            setPadding(dp(8f), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { onPreview?.invoke(store.pathOf(f.index)) }
        if (showFolder) {
            row.setOnLongClickListener {
                val parent = File(store.pathOf(f.index)).parent
                var idx = 0
                for (c in store.childrenInfo(0)) {
                    if (c.flags and NodeStore.DIR != 0 && c.name == File(parent ?: "").name) {
                        idx = c.index
                    }
                }
                if (idx != 0) { dir = idx; rebuild() }
                true
            }
        }
        return row
    }

    private fun line(left: String, right: String, medium: Boolean): TextView =
            TextView(context).apply {
                text = "$left    $right"
                textSize = 12f
                setTextColor(Theme.palette.text2)
                if (medium) typeface = Draw.SANS_MEDIUM
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }

    private fun info(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Theme.palette.muted)
        setPadding(0, dp(8f), 0, dp(4f))
    }

    private fun action(text: String, click: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Theme.palette.brand)
        setPadding(dp(7f), dp(6f), dp(7f), dp(6f))
        setOnClickListener { click() }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
