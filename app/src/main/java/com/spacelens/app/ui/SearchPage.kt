package com.spacelens.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.spacelens.app.core.Classifier
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.NodeStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** 搜索页：只读 NodeStore 内存索引，不触发磁盘扫描。 */
class SearchPage(context: Context) : LinearLayout(context) {

    private data class Hit(val node: Int, val name: String, val size: Long, val path: String, val mtime: Long)

    var store: NodeStore? = null
    var rootPath: String = ""
    var onOpenLocation: ((Int) -> Unit)? = null
    var onDeleteRequested: ((Int) -> Unit)? = null

    private val query = EditText(context)
    private val chips = LinearLayout(context)
    private val status = TextView(context)
    private val list = ListView(context)
    private val adapter = HitAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)
    private var pending: Runnable? = null
    private var hits: List<Hit> = emptyList()
    private var total = 0
    private var lastQuery = ""
    private var lastSignature = ""
    private var inFlightSignature = ""
    private var hasResult = false
    private var rendering = false

    private val types = LinkedHashSet<Int>()
    private var sizeMode = 0          // 0=全部, 1=>10MB, 2=>100MB
    private var timeMode = 0          // 0=全部, 1=7天, 2=30天, 3=更早

    init {
        orientation = VERTICAL
        setPadding(dp(18f), dp(8f), dp(18f), 0)
        setBackgroundColor(Color.TRANSPARENT)

        query.apply {
            hint = "搜索文件名"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Theme.palette.text)
            setHintTextColor(Theme.palette.muted)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = rounded(Theme.palette.panelSoft, Theme.palette.line, Radius.SM, 1f)
            setOnFocusChangeListener { _, focused ->
                background = rounded(Theme.palette.panelSoft,
                        if (focused) Theme.palette.brand else Theme.palette.line,
                        Radius.SM, if (focused) 1.5f else 1f)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    schedule(s?.toString()?.trim().orEmpty())
                }
            })
        }
        addView(query, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        chips.orientation = HORIZONTAL
        chips.setPadding(0, dp(8f), 0, dp(2f))
        scroller.addView(chips, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(dp(4f), dp(8f), dp(4f), dp(6f))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        list.divider = null
        list.dividerHeight = 0
        list.isVerticalScrollBarEnabled = false
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            hits.getOrNull(position)?.let { onOpenLocation?.invoke(it.node) }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val hit = hits.getOrNull(position) ?: return@setOnItemLongClickListener false
            AlertDialog.Builder(context)
                    .setTitle(hit.name)
                    .setItems(arrayOf("删除（进回收站）", "取消")) { _, which ->
                        if (which == 0) onDeleteRequested?.invoke(hit.node)
                    }
                    .show()
            true
        }
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildChips()
        renderStatus()
    }

    fun onShown() {
        val snapshot = store
        val q = query.text?.toString()?.trim().orEmpty()
        if (snapshot == null || snapshot.count <= 1) {
            status.text = "先扫描一次，再搜索"
            dismissImeIfVisible()
            return
        }
        val sig = signature(q)
        // 有缓存先原地显示；同一条件不重复查询，避免先空后满的闪屏。
        if (hasResult && sig == lastSignature) {
            renderStatus()
            dismissImeIfVisible()
            return
        }
        if (hasResult) renderStatus()
        if (sig != inFlightSignature) schedule(q)
        dismissImeIfVisible()
    }

    fun refresh() {
        schedule(query.text?.toString()?.trim().orEmpty())
    }

    private fun buildChips() {
        chips.removeAllViews()
        val labels = Classifier.labels
        for (i in labels.indices) {
            addChip(labels[i], types.contains(i)) {
                if (types.contains(i)) types.remove(i) else types.add(i)
                buildChips()
                refresh()
            }
        }
        addChip(">10MB", sizeMode == 1) {
            sizeMode = if (sizeMode == 1) 0 else 1
            buildChips()
            refresh()
        }
        addChip(">100MB", sizeMode == 2) {
            sizeMode = if (sizeMode == 2) 0 else 2
            buildChips()
            refresh()
        }
        addChip("7 天内", timeMode == 1) {
            timeMode = if (timeMode == 1) 0 else 1
            buildChips()
            refresh()
        }
        addChip("30 天内", timeMode == 2) {
            timeMode = if (timeMode == 2) 0 else 2
            buildChips()
            refresh()
        }
        addChip("更早", timeMode == 3) {
            timeMode = if (timeMode == 3) 0 else 3
            buildChips()
            refresh()
        }
    }

    private fun addChip(label: String, active: Boolean, click: () -> Unit) {
        val tv = TextView(context).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(11f), dp(6f), dp(11f), dp(6f))
            setTextColor(if (active) Color.WHITE else Theme.palette.text2)
            background = rounded(if (active) Theme.palette.brand else Theme.palette.panelSunken,
                    if (active) Theme.palette.brand else Theme.palette.line, Radius.SM, 1f)
            setOnClickListener { click() }
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = dp(6f)
        chips.addView(tv, lp)
    }

    private fun signature(q: String): String =
            "$q|${types.sorted().joinToString(",")}|$sizeMode|$timeMode|${store?.count ?: 0}"

    private fun dismissImeIfVisible() {
        val imeVisible = ViewCompat.getRootWindowInsets(this)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (!imeVisible) return
        query.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(query.windowToken, 0)
    }

    private fun schedule(q: String) {
        val sig = signature(q)
        if (hasResult && sig == lastSignature) {
            renderStatus()
            return
        }
        pending?.let { handler.removeCallbacks(it) }
        inFlightSignature = sig
        val job = Runnable { perform(q) }
        pending = job
        handler.postDelayed(job, 250L)
    }

    private fun perform(q: String) {
        lastQuery = q
        val sig = signature(q)
        if (hasResult && sig == lastSignature) return
        inFlightSignature = sig
        val snapshot = store
        if (snapshot == null || snapshot.count <= 1) {
            hits = emptyList()
            total = 0
            hasResult = true
            lastSignature = sig
            inFlightSignature = ""
            adapter.notifyDataSetChanged()
            status.text = if (snapshot == null) "先扫描一次，再搜索" else "还没扫描，先到「空间」页扫描"
            return
        }
        if (q.isEmpty() && types.isEmpty() && sizeMode == 0 && timeMode == 0) {
            hits = emptyList()
            total = 0
            hasResult = true
            lastSignature = sig
            inFlightSignature = ""
            adapter.notifyDataSetChanged()
            status.text = "输入关键词或选择过滤条件"
            return
        }
        val gen = seq.incrementAndGet()
        val typeSet = HashSet(types)
        val sm = sizeMode
        val tm = timeMode
        val rp = rootPath
        Thread({
            val found = ArrayList<Hit>(200)
            var n = 0
            val now = System.currentTimeMillis()
            snapshot.read {
                for (i in 1 until snapshot.count) {
                    if (snapshot.flagsOf(i) and NodeStore.FILE == 0) continue
                    val name = snapshot.nameOf(i)
                    if (q.isNotEmpty() && !name.contains(q, ignoreCase = true)) continue
                    val size = snapshot.sizeOf(i)
                    if (sm == 1 && size < 10L * 1024 * 1024) continue
                    if (sm == 2 && size < 100L * 1024 * 1024) continue
                    val mt = snapshot.mtimeOf(i)
                    if (tm == 1 && mt < now - 7L * 24 * 3600 * 1000) continue
                    if (tm == 2 && mt < now - 30L * 24 * 3600 * 1000) continue
                    if (tm == 3 && mt >= now - 30L * 24 * 3600 * 1000) continue
                    val cat = snapshot.categoryOf(i)
                    if (typeSet.isNotEmpty()) {
                        var ok = false
                        for (t in typeSet) {
                            if (cat == t) {
                                ok = true
                                break
                            }
                        }
                        if (!ok) continue
                    }
                    n++
                    if (found.size < 200) {
                        found.add(Hit(i, name, size, snapshot.absolutePathOf(rp, i), mt))
                    }
                }
            }
            handler.post {
                if (gen != seq.get() || lastQuery != q) return@post
                hits = found
                total = n
                hasResult = true
                lastSignature = sig
                inFlightSignature = ""
                adapter.notifyDataSetChanged()
                renderStatus()
            }
        }, "FileLens-Search").start()
    }

    private fun renderStatus() {
        if (store == null) {
            status.text = "先扫描一次，再搜索"
            return
        }
        if (hits.isEmpty()) {
            status.text = if (total == 0 && (lastQuery.isNotEmpty() || types.isNotEmpty() || sizeMode != 0 || timeMode != 0)) {
                "没找到匹配的文件"
            } else {
                "输入关键词或选择过滤条件"
            }
            return
        }
        status.text = if (total > hits.size) {
            "找到 $total 项 · 显示前 ${hits.size} 条，还有 ${total - hits.size} 条，请细化条件"
        } else {
            "找到 $total 项"
        }
    }

    private inner class HitAdapter : BaseAdapter() {
        override fun getCount(): Int = hits.size
        override fun getItem(position: Int): Any? = hits.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val hit = hits.getOrNull(position) ?: return View(context)
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                minimumHeight = dp(56f)
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                background = rounded(Theme.palette.panel, Theme.palette.line, Radius.SM, 1f)
            }
            row.addView(TextView(context).apply {
                text = hit.name
                textSize = 13.5f
                setTextColor(Theme.palette.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            row.addView(TextView(context).apply {
                text = "${Fmt.bytes(hit.size)}  ·  ${hit.path}"
                textSize = 10f
                setTextColor(Theme.palette.muted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(3f), 0, 0)
            })
            val lp = AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            row.layoutParams = lp
            if (position < 8) {
                row.alpha = 0f
                row.translationY = dp(6f).toFloat()
                row.postDelayed({
                    Motion.tween(MotionDurations.ITEM, Ease::softOut, { t ->
                        row.alpha = t
                        row.translationY = (1f - t) * dp(6f).toFloat()
                    })
                }, position * 20L)
            }
            return row
        }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float, strokeWidth: Float = 1f): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(max(1, dp(strokeWidth)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
