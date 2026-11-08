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

    private data class Hit(
            val node: Int,
            val name: String,
            val size: Long,
            val path: String,
            val mtime: Long,
            val dir: String,
    )

    var store: NodeStore? = null
    var rootPath: String = ""
    var onOpenLocation: ((Int) -> Unit)? = null
    var onPreview: ((Int) -> Unit)? = null
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
    private var sizeMode = 0          // 0=全部, 1=<1MB, 2=1–10MB, 3=>10MB, 4=>100MB
    private var timeMode = 0          // 0=全部, 1=7天, 2=30天, 3=更早
    private var sortMode = 1          // 0=名称(升序), 1=大小(降序), 2=时间(降序)
    private var keepScrollPos = -1    // 切排序时保持列表位置
    private var keepScrollTop = 0
    private val sortRow = LinearLayout(context)
    private val clearBtn = TextView(context)

    init {
        orientation = VERTICAL
        setPadding(dp(18f), dp(8f), dp(18f), 0)
        setBackgroundColor(Color.TRANSPARENT)

        query.apply {
            hint = "搜索文件名、后缀或路径"
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

        sortRow.orientation = HORIZONTAL
        sortRow.setPadding(0, dp(2f), 0, 0)
        addView(sortRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(dp(4f), dp(8f), dp(4f), dp(6f))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        clearBtn.apply {
            text = "清空筛选"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text2)
            setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            background = rounded(Theme.palette.panelSunken, Theme.palette.line, Radius.SM, 1f)
            visibility = View.GONE
            setOnClickListener { clearFilters() }
        }
        addView(clearBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(4f)
        })

        list.divider = null
        list.dividerHeight = 0
        list.isVerticalScrollBarEnabled = false
        list.clipToPadding = false
        list.setPadding(0, 0, 0, dp(92f))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            hits.getOrNull(position)?.let {
                if (onPreview != null) onPreview?.invoke(it.node) else onOpenLocation?.invoke(it.node)
            }
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
        buildSortRow()
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

    /** 供空间分布卡跳转：按 Classifier 分类索引选中对应 chip 并立即查询。 */
    fun applyTypeFilter(categoryIndex: Int) {
        val chip = when (categoryIndex) {
            Classifier.IMAGE -> Classifier.IMAGE
            Classifier.VIDEO -> Classifier.VIDEO
            Classifier.AUDIO -> Classifier.AUDIO
            Classifier.DOC -> Classifier.DOC
            Classifier.APK -> Classifier.APK
            Classifier.ARCHIVE -> Classifier.ARCHIVE
            Classifier.OTHER -> Classifier.OTHER
            else -> return
        }
        types.clear()
        types.add(chip)
        buildChips()
        refresh()
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
        addChip("<1MB", sizeMode == 1) {
            sizeMode = if (sizeMode == 1) 0 else 1
            buildChips()
            refresh()
        }
        addChip("1–10MB", sizeMode == 2) {
            sizeMode = if (sizeMode == 2) 0 else 2
            buildChips()
            refresh()
        }
        addChip(">10MB", sizeMode == 3) {
            sizeMode = if (sizeMode == 3) 0 else 3
            buildChips()
            refresh()
        }
        addChip(">100MB", sizeMode == 4) {
            sizeMode = if (sizeMode == 4) 0 else 4
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
        addChipTo(chips, label, active, click)
    }

    private fun addChipTo(parent: LinearLayout, label: String, active: Boolean, click: () -> Unit) {
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
        parent.addView(tv, lp)
    }

    /** 结果区排序：名称升序 / 大小降序 / 时间降序。 */
    private fun buildSortRow() {
        sortRow.removeAllViews()
        val labels = arrayOf("名称", "大小", "时间")
        for (i in labels.indices) {
            addChipTo(sortRow, labels[i], sortMode == i) {
                if (sortMode == i) return@addChipTo
                // 重跑检索时保持列表位置，避免「闪一下回到顶部」。
                keepScrollPos = list.firstVisiblePosition
                keepScrollTop = list.getChildAt(0)?.top ?: 0
                sortMode = i
                buildSortRow()
                refresh()
            }
        }
    }

    private fun clearFilters() {
        types.clear()
        sizeMode = 0
        timeMode = 0
        buildChips()
        refresh()
    }

    private enum class QueryMode { NAME, SUFFIX, PATH }

    /** 三种模式互斥：后缀 > 路径 > 名称。不做正则。 */
    private fun queryMode(q: String): QueryMode = when {
        q.startsWith("*.") || q.startsWith(".") -> QueryMode.SUFFIX
        q.contains("/") -> QueryMode.PATH
        else -> QueryMode.NAME
    }

    private fun suffixOf(q: String): String = if (q.startsWith("*.")) q.substring(1) else q

    /** 绝对路径 → 相对内部存储的父目录；根目录显示「内部存储」，不重复拼前缀。 */
    private fun relativeDir(abs: String, root: String): String {
        val r = root.trimEnd('/')
        val rel = if (r.isNotEmpty() && abs.startsWith(r)) {
            abs.removePrefix(r).trimStart('/')
        } else {
            abs.trimStart('/')
        }
        val i = rel.lastIndexOf('/')
        return if (i <= 0) "内部存储" else rel.substring(0, i)
    }

    private fun sortHits(list: MutableList<Hit>) {
        when (sortMode) {
            0 -> list.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            1 -> list.sortWith { a, b -> b.size.compareTo(a.size) }
            else -> list.sortWith { a, b -> b.mtime.compareTo(a.mtime) }
        }
    }

    private fun betterThan(name: String, size: Long, mtime: Long, other: Hit): Boolean =
            when (sortMode) {
                0 -> name.compareTo(other.name, ignoreCase = true) < 0
                1 -> size > other.size
                else -> mtime > other.mtime
            }

    private fun worse(a: Hit, b: Hit): Boolean = when (sortMode) {
        0 -> a.name.compareTo(b.name, ignoreCase = true) > 0
        1 -> a.size < b.size
        else -> a.mtime < b.mtime
    }

    private fun worstIndex(list: List<Hit>): Int {
        var w = 0
        for (j in 1 until list.size) if (worse(list[j], list[w])) w = j
        return w
    }

    private fun signature(q: String): String =
            "$q|${types.sorted().joinToString(",")}|$sizeMode|$timeMode|$sortMode|${store?.count ?: 0}"

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
            clearBtn.visibility = View.GONE
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
            renderStatus()
            return
        }
        val gen = seq.incrementAndGet()
        val typeSet = HashSet(types)
        val sm = sizeMode
        val tm = timeMode
        val rp = rootPath
        val mode = queryMode(q)
        val suffix = suffixOf(q)
        Thread({
            val found = ArrayList<Hit>(200)
            var n = 0
            val now = System.currentTimeMillis()
            val mb = 1024L * 1024L
            snapshot.read {
                for (i in 1 until snapshot.count) {
                    if (snapshot.flagsOf(i) and NodeStore.FILE == 0) continue
                    val name = snapshot.nameOf(i)
                    if (q.isNotEmpty()) {
                        when (mode) {
                            QueryMode.NAME ->
                                if (!name.contains(q, ignoreCase = true)) continue
                            QueryMode.SUFFIX ->
                                if (!name.endsWith(suffix, ignoreCase = true)) continue
                            QueryMode.PATH ->
                                if (!snapshot.absolutePathOf(rp, i).contains(q, ignoreCase = true)) continue
                        }
                    }
                    val size = snapshot.sizeOf(i)
                    when (sm) {
                        1 -> if (size >= mb) continue
                        2 -> if (size < mb || size >= 10 * mb) continue
                        3 -> if (size < 10 * mb) continue
                        4 -> if (size < 100 * mb) continue
                    }
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
                        val abs = snapshot.absolutePathOf(rp, i)
                        found.add(Hit(i, name, size, abs, mt, relativeDir(abs, rp)))
                    } else {
                        // 只留「按当前排序最好的 200 条」，避免十万级命中全进内存。
                        val w = worstIndex(found)
                        if (betterThan(name, size, mt, found[w])) {
                            val abs = snapshot.absolutePathOf(rp, i)
                            found[w] = Hit(i, name, size, abs, mt, relativeDir(abs, rp))
                        }
                    }
                }
            }
            sortHits(found)
            handler.post {
                if (gen != seq.get() || lastQuery != q) return@post
                hits = found
                total = n
                hasResult = true
                lastSignature = sig
                inFlightSignature = ""
                adapter.notifyDataSetChanged()
                renderStatus()
                val pos = keepScrollPos
                val top = keepScrollTop
                keepScrollPos = -1
                if (pos >= 0 && hits.isNotEmpty()) {
                    list.post { list.setSelectionFromTop(pos.coerceAtMost(hits.size - 1), top) }
                }
            }
        }, "FileLens-Search").start()
    }

    private fun renderStatus() {
        if (store == null) {
            status.text = "先扫描一次，再搜索"
            clearBtn.visibility = View.GONE
            return
        }
        clearBtn.visibility = View.GONE
        if (hits.isEmpty()) {
            val hasKeyword = lastQuery.isNotEmpty()
            val hasFilter = types.isNotEmpty() || sizeMode != 0 || timeMode != 0
            status.text = when {
                hasKeyword -> {
                    val head = if (queryMode(lastQuery) == QueryMode.NAME) {
                        "没有名字包含“$lastQuery”的文件"
                    } else {
                        "没有匹配“$lastQuery”的文件"
                    }
                    "$head\n试试搜路径，比如 DCIM/"
                }
                hasFilter -> "当前筛选条件下没有文件"
                else -> "输入关键词或选择过滤条件"
            }
            if (!hasKeyword && hasFilter) clearBtn.visibility = View.VISIBLE
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
                text = "${Fmt.bytes(hit.size)}  ·  ${hit.dir}"
                textSize = 10f
                setTextColor(Theme.palette.muted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
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
