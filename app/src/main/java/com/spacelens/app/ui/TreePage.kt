package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.NodeStore
import com.spacelens.app.core.Scanner

/** 空间树页：面包屑 + 视图切换 + 自绘树图。 */
class TreePage(context: Context) : FrameLayout(context) {

    var onStartScan: (() -> Unit)? = null
    var onCancelScan: (() -> Unit)? = null
    var onNodeLongPress: ((Int) -> Unit)? = null
    var onAggregateTap: ((IntArray) -> Unit)? = null
    var onUnavailableView: ((String) -> Unit)? = null
    var onRootChanged: ((Int) -> Unit)? = null
    var onSelectionToggle: ((Int) -> Unit)? = null
    var onCancelSelection: (() -> Unit)? = null
    var onDeleteSelection: (() -> Unit)? = null
    var onSelectAllSelection: (() -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    var onFileTap: ((Int) -> Unit)? = null
    /** 1.4.1：列表长按弹原生菜单（改名/移动/新建/多选）。 */
    var onItemMenu: ((Int) -> Unit)? = null

    private val top = TopArea(context)
    private val selectionBar = SelectionBar(context)
    private val treemap = TreemapView(context)
    private val list = ListView(context)
    private var activeView = 0
    private var selectedNodes: Set<Int> = emptySet()
    private var selectionActive = false
    private var sortMode = 0
    private val sortHeader = TextView(context)
    private var metrics = Breakpoints.metrics(context)
    private lateinit var treemapLp: LinearLayout.LayoutParams

    var store: NodeStore? = null
        private set
    var rootIndex: Int = 0
        private set
    private var scanning = false
    private var progress: Scanner.ScanProgress? = null

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, (92 * resources.displayMetrics.density).toInt())
        }
        column.addView(top, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        selectionBar.visibility = View.GONE
        column.addView(selectionBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        treemapLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        treemapLp.leftMargin = (metrics.marginDp * resources.displayMetrics.density).toInt()
        treemapLp.rightMargin = (metrics.marginDp * resources.displayMetrics.density).toInt()
        treemapLp.bottomMargin = (40 * resources.displayMetrics.density).toInt()
        column.addView(treemap, treemapLp)
        list.divider = null
        list.dividerHeight = (6 * resources.displayMetrics.density).toInt()
        list.visibility = View.GONE
        sortHeader.visibility = View.GONE
        sortHeader.textSize = 11f
        sortHeader.gravity = android.view.Gravity.CENTER_VERTICAL
        sortHeader.setPadding((18 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt(),
                (18 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt())
        sortHeader.setTextColor(Theme.palette.muted)
        sortHeader.setOnClickListener { sortMode = (sortMode + 1) % 3; showList() }
        column.addView(sortHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        treemap.setLayoutMode(metrics.maxDepth, metrics.maxBlocks)

        treemap.onDrill = { node ->
            val depth = store?.depthOf(node) ?: 0
            android.util.Log.i("FileLensTree", "drill node=$node depth=$depth")
            if (depth <= 6) showRoot(node)
        }
        treemap.onNodeTap = { node -> onFileTap?.invoke(node) }
        treemap.onLongPressNode = { node -> onNodeLongPress?.invoke(node) }
        treemap.onAggregateTap = { nodes -> onAggregateTap?.invoke(nodes) }
        treemap.onSelectionToggle = { node -> onSelectionToggle?.invoke(node) }
        selectionBar.onCancel = { onCancelSelection?.invoke() }
        selectionBar.onDelete = { onDeleteSelection?.invoke() }
        selectionBar.onSelectAll = { onSelectAllSelection?.invoke() }
        top.onScan = {
            if (scanning) onCancelScan?.invoke() else onStartScan?.invoke()
        }
        top.onBack = { goUp() }
        top.onCrumb = { node -> showRoot(node) }
        top.onUnavailable = { name ->
            when (name) {
                "列表视图" -> showList()
                else -> onUnavailableView?.invoke(name)
            }
        }
        top.onTreeView = { showTree() }
        top.onSearch = { onSearch?.invoke() }
    }

    fun setWindowClass() {
        metrics = Breakpoints.metrics(context)
        treemap.setLayoutMode(metrics.maxDepth, metrics.maxBlocks)
        val d = resources.displayMetrics.density
        treemapLp.leftMargin = (metrics.marginDp * d).toInt()
        treemapLp.rightMargin = (metrics.marginDp * d).toInt()
        treemapLp.bottomMargin = (40 * d).toInt()
        treemap.requestLayout()
        top.setMetrics(metrics)
        top.invalidate()
    }

    fun setEmpty(rootName: String) {
        store = null
        rootIndex = 0
        scanning = false
        progress = null
        top.rootName = rootName
        treemap.setScanning(false)
        treemap.setEmpty("还没有空间树")
        top.invalidate()
        onRootChanged?.invoke(0)
    }

    fun setTree(store: NodeStore, root: Int) {
        this.store = store
        this.rootIndex = root
        scanning = false
        progress = null
        top.rootName = store.nameOf(0)
        treemap.setScanning(false)
        treemap.setTree(store, root)
        top.invalidate()
        onRootChanged?.invoke(root)
    }

    fun setScanning(store: NodeStore, rootName: String) {
        this.store = store
        this.rootIndex = 0
        scanning = true
        progress = null
        top.rootName = rootName
        treemap.setScanning(true)
        treemap.setTree(store, 0)
        treemap.setEmptyHint("正在扫描…")
        top.invalidate()
        onRootChanged?.invoke(0)
    }

    fun updateProgress(p: Scanner.ScanProgress) {
        progress = p
        if (scanning) {
            treemap.onScanProgress(p.items)
            treemap.refresh()
            top.invalidate()
        }
    }

    fun finishScan(store: NodeStore, summaryRootName: String) {
        this.store = store
        scanning = false
        progress = null
        rootIndex = 0
        top.rootName = summaryRootName
        treemap.setScanning(false)
        treemap.setTree(store, 0)
        top.invalidate()
        onRootChanged?.invoke(0)
    }

    fun setCanceled(store: NodeStore) {
        this.store = store
        scanning = false
        progress = null
        top.invalidate()
        treemap.refresh()
    }

    fun showTree() {
        activeView = 0
        treemap.visibility = View.VISIBLE
        list.visibility = View.GONE
        sortHeader.visibility = View.GONE
        top.setView(0)
        top.invalidate()
    }

    private fun showList() {
        val s = store ?: return
        activeView = 1
        treemap.visibility = View.GONE
        list.visibility = View.VISIBLE
        sortHeader.visibility = View.VISIBLE
        val items = when (sortMode) {
            1 -> s.childrenInfo(rootIndex).sortedBy { it.name.lowercase() }
            2 -> s.childrenInfo(rootIndex).sortedByDescending { it.files }
            else -> s.childrenInfo(rootIndex).sortedByDescending { it.size }
        }
        val sum = items.sumOf { it.size }.coerceAtLeast(1L)
        sortHeader.text = when (sortMode) {
            1 -> "排序：名称 A→Z  ▾"
            2 -> "排序：文件数  ▾"
            else -> "排序：体积  ▾"
        }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): Any = items[position]
            override fun getItemId(position: Int): Long = items[position].index.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val item = items[position]
                val p = Theme.palette
                val row = (convertView as? android.widget.LinearLayout)
                        ?: android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.AbsListView.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    (64 * resources.displayMetrics.density).toInt())
                        }
                row.removeAllViews()
                val isSel = selectionActive && selectedNodes.contains(item.index)
                row.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = Radius.SM * resources.displayMetrics.density
                    setColor(if (isSel) p.panelSoft else p.panel)
                }
                val content = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((16 * resources.displayMetrics.density).toInt(), 0,
                            (14 * resources.displayMetrics.density).toInt(), 0)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val dot = View(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                            ((if (isSel) 6 else 4) * resources.displayMetrics.density).toInt(),
                            (28 * resources.displayMetrics.density).toInt())
                    setBackgroundColor(p.category[item.category.coerceIn(0, 6)])
                }
                content.addView(dot)
                val name = TextView(context).apply {
                    text = item.name
                    setTextColor(p.text)
                    textSize = 14f
                    setPadding((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, -1, 1f)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                content.addView(name)
                val size = TextView(context).apply {
                    text = Fmt.bytes(item.size) + " · " + Fmt.count(item.files.toLong()) + " 文件"
                    setTextColor(p.muted)
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                content.addView(size)
                val check = CheckView(context).apply {
                    checked = isSel
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                            (20 * resources.displayMetrics.density).toInt(),
                            (20 * resources.displayMetrics.density).toInt())
                    setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                }
                if (selectionActive) content.addView(check)
                row.addView(content)
                val bar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000
                    progress = (1000f * item.size / sum).toInt().coerceIn(0, 1000)
                    progressTintList = android.content.res.ColorStateList.valueOf(
                            p.category[item.category.coerceIn(0, 6)])
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (2 * resources.displayMetrics.density).toInt())
                }
                row.addView(bar)
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _: android.widget.AdapterView<*>?, _: View?, position: Int, _: Long ->
            val child = items.getOrNull(position) ?: return@setOnItemClickListener
            if (onSelectionToggle != null && list.tag == true) {
                onSelectionToggle?.invoke(child.index)
            } else {
                showRoot(child.index)
                showList()
            }
        }
        list.setOnItemLongClickListener { _: android.widget.AdapterView<*>?, _: View?, position: Int, _: Long ->
            val child = items.getOrNull(position) ?: return@setOnItemLongClickListener false
            val menu = onItemMenu
            if (menu != null) {
                menu.invoke(child.index)
            } else {
                list.tag = true
                onSelectionToggle?.invoke(child.index)
            }
            true
        }
        top.setView(1)
        top.invalidate()
    }

    private fun refreshViews() {
        if (activeView == 1) showList()
    }
    fun setForbidden(nodes: Map<Int, String>) {
        treemap.setForbidden(nodes)
    }

    fun refreshTree() {
        top.invalidate()
        treemap.refresh()
        refreshViews()
    }

    /** 从长按菜单进入多选模式，保留原来的长按多选能力。 */
    fun selectFromMenu(node: Int) {
        list.tag = true
        onSelectionToggle?.invoke(node)
    }

    fun showRoot(node: Int) {        val s = store ?: return
        if (node !in 0 until s.count) return
        if (s.depthOf(node) > 6) return
        rootIndex = node
        treemap.setRoot(node)
        top.invalidate()
        onRootChanged?.invoke(node)
    }

    fun setSelectedNode(node: Int) {
        treemap.setSelectedNode(node)
    }

    fun setSelectionState(active: Boolean, count: Int, bytes: Long, selected: Set<Int>) {
        selectionActive = active
        selectedNodes = selected
        list.tag = active
        if (list.adapter != null) (list.adapter as? android.widget.BaseAdapter)?.notifyDataSetChanged()
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        if (active) selectionBar.setState(count, bytes)
        treemap.setSelection(selected, active)
        requestLayout()
    }

    fun hitNodeAt(x: Float, y: Float): Int = treemap.hitNodeAt(x, y)

    fun goUp(): Boolean {
        val s = store ?: return false
        if (rootIndex == 0) return false
        val parent = s.parentOf(rootIndex)
        if (parent < 0) return false
        showRoot(parent)
        return true
    }

    fun isScanning(): Boolean = scanning

    fun nodeDescription(index: Int, rootPath: String): String {
        val s = store ?: return ""
        if (index !in 0 until s.count) return ""
        return buildString {
            append("路径：").append(s.absolutePathOf(rootPath, index)).append("\n")
            append("体积：").append(Fmt.bytes(s.sizeOf(index))).append("\n")
            append("文件：").append(Fmt.count(s.filesOf(index).toLong()))
            append(" 个 · 目录：").append(Fmt.count(s.dirsOf(index).toLong())).append(" 个\n")
            if (s.mtimeOf(index) > 0L) append("修改时间：").append(Fmt.dateTime(s.mtimeOf(index)))
        }
    }

    private inner class TopArea(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var margin = 18f * d
        private val rootH = 60f * d
        private val crumbH = 30f * d
        private val chipH = 34f * d
        private val totalH = 8f * d + rootH + 2f * d + crumbH + chipH + 8f * d

        fun setMetrics(metrics: UiMetrics) {
            margin = metrics.marginDp * d
        }

        var rootName: String = "内部存储"
        var onScan: (() -> Unit)? = null
        var onBack: (() -> Unit)? = null
        var onCrumb: ((Int) -> Unit)? = null
        var onUnavailable: ((String) -> Unit)? = null
        var onTreeView: (() -> Unit)? = null
        var onSearch: (() -> Unit)? = null
    var onFileTap: ((Int) -> Unit)? = null
        private var viewMode = 0

        fun setView(mode: Int) {
            viewMode = mode.coerceIn(0, 1)
            invalidate()
        }

        private val scanRect = RectF()
        private val backRect = RectF()
        private val chipRects = Array(3) { RectF() }
        private val crumbRects = HashMap<Int, RectF>()
        private var pressed = -1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), totalH.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val p = Theme.palette
            val w = width.toFloat()
            val off = IntArray(2)
            getLocationOnScreen(off)
            com.spacelens.app.debug.ControlRegistry.clear("tree")
            fun reg(id: String, label: String, r: RectF) {
                com.spacelens.app.debug.ControlRegistry.put("tree", id, label,
                        RectF(r.left + off[0], r.top + off[1], r.right + off[0], r.bottom + off[1]))
            }
            var y = 8f * d
            val root = RectF(margin, y, w - margin, y + rootH)
            Draw.card(canvas, root, Radius.PANEL * d, p.panel, p.line, shadow = true)

            val s = store
            val drilled = s != null && rootIndex != 0
            if (drilled) {
                backRect.set(root.left + 6f * d, root.centerY() - 18f * d,
                        root.left + 42f * d, root.bottom - 2f * d)
                if (pressed == 1) canvas.drawCircle(backRect.centerX(), backRect.centerY(), 16f * d,
                        Draw.fill(Theme.alpha(p.brand, 18)))
                Draw.icon(canvas, Icon.BACK, backRect.centerX(), backRect.centerY(), 17f * d, p.text, 2f * d)
                reg("back", "返回", backRect)
            } else {
                canvas.drawCircle(root.left + 31f * d, root.centerY(), 16f * d,
                        Draw.fill(Theme.alpha(p.brand, if (p.dark) 40 else 20)))
                Draw.icon(canvas, Icon.FOLDER, root.left + 31f * d, root.centerY(), 18f * d, p.brand, 1.9f * d)
            }

            val nameX = root.left + if (drilled) 50f * d else 55f * d
            val namePaint = Draw.text(context, 15f, p.text, medium = true)
            val displayName = if (s != null && rootIndex in 0 until s.count) s.nameOf(rootIndex) else rootName
            val compact = root.width() < 340f * d
            val scanW = if (compact) 44f * d else 78f * d
            val textMax = (root.right - scanW - 12f * d) - nameX
            canvas.drawText(Draw.ellipsize(displayName, namePaint, textMax),
                    nameX, root.centerY() + 1.5f * d, namePaint)

            val statPaint = Draw.text(context, 11f, p.muted)
            val stat = when {
                scanning -> progress?.let {
                    "扫描中 · ${Fmt.count(it.files.toLong())} 文件 · ${Fmt.bytes(it.bytes)}"
                } ?: "扫描中…"
                s != null && s.count > 0 -> {
                    if (compact) "${Fmt.count(s.filesOf(rootIndex).toLong())} 文件 · ${Fmt.bytes(s.sizeOf(rootIndex))}"
                    else "${Fmt.count(s.filesOf(rootIndex).toLong())} 文件 · " +
                            "${Fmt.count(s.dirsOf(rootIndex).toLong())} 目录 · ${Fmt.bytes(s.sizeOf(rootIndex))}"
                }
                else -> "尚未扫描"
            }
            canvas.drawText(Draw.ellipsize(stat, statPaint, textMax),
                    nameX, root.centerY() + 17f * d, statPaint)

            scanRect.set(root.right - scanW - 12f * d, root.centerY() - 16f * d,
                    root.right - 12f * d, root.centerY() + 16f * d)
            reg("scan", if (scanning) "停止" else "重扫", scanRect)
            if (compact) {
                canvas.drawCircle(scanRect.centerX(), scanRect.centerY(), 16f * d,
                        Draw.fill(if (scanning) Theme.alpha(p.danger, 32) else p.brand))
                if (scanning) {
                    Draw.icon(canvas, Icon.CLOSE, scanRect.centerX(), scanRect.centerY(),
                            14f * d, p.danger, 1.8f * d)
                } else {
                    Draw.icon(canvas, Icon.REFRESH, scanRect.centerX(), scanRect.centerY(),
                            16f * d, 0xFFFFFFFF.toInt(), 1.8f * d)
                }
            } else {
                canvas.drawRoundRect(scanRect, scanRect.height() / 2f, scanRect.height() / 2f,
                        Draw.fill(if (scanning) Theme.alpha(p.danger, 32) else p.brand))
                val sp = Draw.text(context, 10.5f,
                        if (scanning) p.danger else 0xFFFFFFFF.toInt(), medium = true, align = Paint.Align.CENTER)
                canvas.drawText(if (scanning) "停止" else if (s != null && s.count > 1) "重扫" else "扫描",
                        scanRect.centerX(), scanRect.centerY() + 3.8f * d, sp)
            }
            y = root.bottom + 2f * d

            // 面包屑
            crumbRects.clear()
            if (s != null && s.count > 0) {
                val chain = s.chainOf(rootIndex)
                var totalW = 0f
                val paints = ArrayList<Pair<Int, Paint>>()
                val shown = chain.takeLast(4)
                for ((i, node) in shown.withIndex()) {
                    val last = i == shown.size - 1
                    val paint = Draw.text(context, 10f, if (last) p.text2 else p.muted, medium = last)
                    paints.add(node to paint)
                    totalW += paint.measureText(s.nameOf(node))
                    if (i > 0) totalW += 12f * d
                }
                var cx = margin + 2f * d
                val overflow = totalW > w - margin * 2
                if (overflow) {
                    val ell = Draw.text(context, 10f, p.muted)
                    canvas.drawText("…", cx, y + 19f * d, ell)
                    cx += 12f * d
                }
                for ((i, pair) in paints.withIndex()) {
                    val (node, paint) = pair
                    if (i > 0) {
                        val sep = Draw.text(context, 10f, p.muted)
                        canvas.drawText("/", cx, y + 19f * d, sep)
                        cx += 12f * d
                    }
                    val nm = s.nameOf(node)
                    val ew = Draw.ellipsize(nm, paint, w - margin - cx - 4f * d)
                    canvas.drawText(ew, cx, y + 19f * d, paint)
                    val rect = RectF(cx - 4f * d, y - 7f * d,
                            cx + paint.measureText(ew) + 4f * d, y + crumbH + 7f * d)
                    crumbRects[node] = rect
                    reg("crumb:$node", nm, rect)
                    cx += paint.measureText(ew) + 6f * d
                }
            }
            y += crumbH

            // 视图切换
            val names = arrayOf("树图", "列表", "搜索")
            val icons = arrayOf(Icon.TREE, Icon.LIST, Icon.SEARCH)
            var cx = margin + 2f * d
            for (i in 0 until 3) {
                val r = RectF(cx, y + 1f * d, cx + 64f * d, y + chipH - 1f * d)
                chipRects[i].set(r)
                reg("chip:" + names[i], names[i], r)
                val active = i == viewMode
                canvas.drawRoundRect(r, Radius.SM * d, Radius.SM * d,
                        Draw.fill(if (active) p.brandSoft else p.panelSunken))
                Draw.icon(canvas, icons[i], r.left + 16f * d, r.centerY(), 13f * d,
                        if (active) p.brand else p.muted, 1.6f * d)
                val cp = Draw.text(context, 10f, if (active) p.brand else p.muted, medium = active)
                canvas.drawText(names[i], r.left + 27f * d, r.centerY() + 3.5f * d, cp)
                cx += 70f * d
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hit(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pressed >= 0 && hit(event.x, event.y) != pressed) {
                        pressed = -1
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val h = hit(event.x, event.y)
                    val was = pressed
                    pressed = -1
                    invalidate()
                    if (h >= 0 && h == was) {
                        when (h) {
                            0 -> onScan?.invoke()
                            1 -> onBack?.invoke()
                            2 -> onCrumb?.invoke(crumbHit(event.x, event.y))
                            3 -> onUnavailable?.invoke("列表视图")
                            5 -> onTreeView?.invoke()
                            6 -> onSearch?.invoke()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = -1
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun crumbHit(x: Float, y: Float): Int {
            for ((node, rect) in crumbRects) if (rect.contains(x, y)) return node
            return -1
        }

        private fun hit(x: Float, y: Float): Int {
            if (scanRect.contains(x, y)) return 0
            // 面包屑优先：它和返回箭头在左上角相邻，先判箭头会把第一段吃掉。
            for (rect in crumbRects.values) if (rect.contains(x, y)) return 2
            if (backRect.contains(x, y) && store?.let { rootIndex != 0 } == true) return 1
            if (chipRects[0].contains(x, y)) return 5
            if (chipRects[1].contains(x, y)) return 3
            if (chipRects[2].contains(x, y)) return 6
            return -1
        }
    }

    private inner class CheckView(context: Context) : View(context) {
        var checked = false
        private val d = resources.displayMetrics.density
        override fun onDraw(canvas: Canvas) {
            val p = Theme.palette
            val cx = width / 2f
            val cy = height / 2f
            val r = 8f * d
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f * d
                color = if (checked) p.brand else Theme.alpha(p.muted, 150)
            }
            canvas.drawCircle(cx, cy, r, paint)
            if (checked) {
                canvas.drawCircle(cx, cy, r, Draw.fill(p.brand))
                val path = android.graphics.Path().apply {
                    moveTo(cx - 3.2f * d, cy)
                    lineTo(cx - 0.8f * d, cy + 2.8f * d)
                    lineTo(cx + 3.8f * d, cy - 3.2f * d)
                }
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.8f * d
                    strokeCap = Paint.Cap.ROUND
                    color = android.graphics.Color.BLACK
                })
            }
        }
    }}
