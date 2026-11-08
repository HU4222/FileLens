package com.spacelens.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.spacelens.app.core.AppStats
import com.spacelens.app.core.CacheCleaner
import com.spacelens.app.core.DeletePolicy
import com.spacelens.app.core.Duplicates
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.GarbageAdvisor
import com.spacelens.app.core.NodeStore
import com.spacelens.app.core.Trash
import java.io.File
import kotlin.math.max

/** 清理页：重复文件 + 应用占用排行 + Shizuku 清缓存。 */
class CleanPage(context: Context) : LinearLayout(context) {

    var store: NodeStore? = null
    var rootPath: String = ""

    private val segmentBar = LinearLayout(context)
    private val dupChip = TextView(context)
    private val appChip = TextView(context)
    private val garbageChip = TextView(context)

    private val dupSection = LinearLayout(context)
    private val summary = TextView(context)
    private val status = TextView(context)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val start = Button(context)
    private val cancel = Button(context)
    private val body = LinearLayout(context)
    private val delete = Button(context)

    private val appSection = LinearLayout(context)
    private val appTitle = TextView(context)
    private val appSummary = TextView(context)
    private val appStatus = TextView(context)
    private val appProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val shizukuCard = LinearLayout(context)
    private val shizukuText = TextView(context)
    private val shizukuAction = Button(context)
    private val clearCache = Button(context)
    private val usageGate = LinearLayout(context)
    private val sortRow = LinearLayout(context)
    private val sortTotal = TextView(context)
    private val sortCache = TextView(context)
    private val appList = ListView(context)
    private val appAdapter = AppAdapter()

    private val garbageSection = LinearLayout(context)
    private val garbageTitle = TextView(context)
    private val garbageStatus = TextView(context)
    private val garbageBody = LinearLayout(context)
    private val garbageDelete = Button(context)
    private var garbageCategories: List<GarbageAdvisor.Category> = emptyList()
    private val garbageSelected = HashSet<Int>()
    private val garbageExpanded = HashSet<String>()
    private var garbageStarted = false
    private var garbageLoading = false
    private var garbageGeneration = 0

    private val handler = Handler(Looper.getMainLooper())
    private var segment = -1

    private var groups: List<Duplicates.Group> = emptyList()
    private val selected = HashSet<Int>()
    private var running = false
    private var started = false
    private var generation = 0
    @Volatile private var cancelFlag = false

    private var appEntries: List<AppStats.Entry> = emptyList()
    private var appFailed = 0
    private var appLoading = false
    private var appLoaded = false
    private var appGeneration = 0
    private var sortByCache = false
    private var clearing = false
    private var pendingFreed: Long? = null
    private var shizuku = CacheCleaner.status(context)

    init {
        orientation = VERTICAL
        setPadding(dp(18f), dp(6f), dp(18f), dp(8f))
        setBackgroundColor(Color.TRANSPARENT)
        buildSegments()
        buildDuplicateSection()
        buildAppSection()
        buildGarbageSection()
        selectSegment(0)
    }

    private fun buildSegments() {
        segmentBar.orientation = HORIZONTAL
        segmentBar.gravity = Gravity.CENTER_VERTICAL
        segmentBar.setPadding(0, 0, 0, dp(8f))
        for ((i, chip) in arrayOf(dupChip, appChip, garbageChip).withIndex()) {
            chip.text = when (i) {
                0 -> "重复文件"
                1 -> "应用占用"
                else -> "垃圾建议"
            }
            chip.textSize = 12f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14f), dp(7f), dp(14f), dp(7f))
            chip.setOnClickListener { selectSegment(i) }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(8f)
            segmentBar.addView(chip, lp)
        }
        addView(segmentBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun styleSegments() {
        for ((i, chip) in arrayOf(dupChip, appChip, garbageChip).withIndex()) {
            val active = i == segment
            chip.setTextColor(if (active) Color.WHITE else Theme.palette.text2)
            chip.background = rounded(if (active) Theme.palette.brand else Theme.palette.panelSunken,
                    if (active) Theme.palette.brand else Theme.palette.line, Radius.PILL)
        }
    }

    private fun selectSegment(index: Int) {
        if (index == segment) return
        val old = segment
        segment = index
        styleSegments()
        val sections = arrayOf(dupSection, appSection, garbageSection)
        for (i in sections.indices) {
            if (i != index) {
                sections[i].animate().cancel()
                sections[i].visibility = View.GONE
            }
        }
        val incoming = sections[index]
        incoming.animate().cancel()
        incoming.visibility = View.VISIBLE
        val start = if (index > old) 1f else -1f
        val width = if (incoming.width > 0) incoming.width.toFloat()
            else resources.displayMetrics.widthPixels.toFloat()
        incoming.translationX = start * width * 0.18f
        incoming.alpha = 0.4f
        incoming.animate()
                .translationX(0f).alpha(1f)
                .setDuration(200L)
                .setInterpolator(android.view.animation.PathInterpolator(
                        0.22f, 0.9f, 0.3f, 1f))
                .start()
        when (index) {
            1 -> onAppSelected()
            2 -> if (!garbageStarted) startGarbageScan()
            else -> if (!started && !running) {
                started = true
                if ((store?.count ?: 0) > 1) startScan()
            }
        }
    }

    private fun buildDuplicateSection() {
        dupSection.orientation = VERTICAL
        summary.apply {
            text = "重复文件"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }
        dupSection.addView(summary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.apply {
            text = "同 size 且 ≥1MB 的文件才会做 SHA-256；可中断。"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, dp(6f))
        }
        dupSection.addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        progress.max = 100
        progress.progress = 0
        progress.progressTintList = android.content.res.ColorStateList.valueOf(Theme.palette.brand)
        progress.visibility = View.GONE
        dupSection.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(4f)))

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
        dupSection.addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        body.orientation = VERTICAL
        body.setPadding(0, 0, 0, dp(92f))
        scroll.addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dupSection.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        delete.apply {
            text = "删除选中（进回收站）"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.danger, Theme.palette.danger, Radius.PILL)
            setOnClickListener { confirmDelete() }
        }
        val dlp = LayoutParams(LayoutParams.MATCH_PARENT, dp(42f))
        dlp.topMargin = dp(6f)
        dlp.bottomMargin = dp(92f)
        dupSection.addView(delete, dlp)
        updateSummary()
        addView(dupSection, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildAppSection() {
        appSection.orientation = VERTICAL
        appSection.visibility = View.GONE
        appTitle.apply {
            text = "应用占用排行"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }
        appSection.addView(appTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        appSummary.apply {
            text = "只读系统 StorageStats，不遍历文件"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, dp(3f))
        }
        appSection.addView(appSummary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        appStatus.apply {
            text = "首次进入需要「使用情况访问」授权"
            textSize = 12f
            setTextColor(Theme.palette.muted)
            setPadding(0, 0, 0, dp(5f))
        }
        appSection.addView(appStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        appProgress.max = 100
        appProgress.progress = 0
        appProgress.progressTintList = android.content.res.ColorStateList.valueOf(Theme.palette.brand)
        appProgress.visibility = View.GONE
        appSection.addView(appProgress, LayoutParams(LayoutParams.MATCH_PARENT, dp(4f)))

        usageGate.orientation = VERTICAL
        usageGate.gravity = Gravity.CENTER
        usageGate.setPadding(dp(16f), dp(26f), dp(16f), dp(26f))
        usageGate.background = rounded(Theme.palette.panelSoft, Theme.palette.line, Radius.PANEL)
        usageGate.visibility = View.GONE
        usageGate.addView(TextView(context).apply {
            text = "需要「使用情况访问」权限才能读取各应用占用"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setLineSpacing(dp(4f).toFloat(), 1f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val goto = Button(context).apply {
            text = "去授权"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
            setOnClickListener { openUsageSettings() }
        }
        val glp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        glp.gravity = Gravity.CENTER
        glp.topMargin = dp(12f)
        usageGate.addView(goto, glp)
        appSection.addView(usageGate, LayoutParams(LayoutParams.MATCH_PARENT, dp(210f)))

        buildShizukuCard()
        appSection.addView(shizukuCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        sortRow.orientation = HORIZONTAL
        sortRow.gravity = Gravity.CENTER_VERTICAL
        sortRow.setPadding(0, dp(8f), 0, dp(4f))
        for ((i, chip) in arrayOf(sortTotal, sortCache).withIndex()) {
            chip.text = if (i == 0) "按占用" else "按缓存"
            chip.textSize = 11f
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            chip.setOnClickListener {
                if (sortByCache == (i == 1)) return@setOnClickListener
                sortByCache = i == 1
                styleSort()
                renderAppData()
                appList.animate().cancel()
                appList.translationY = dp(8f)
                appList.alpha = 0.5f
                appList.animate()
                        .translationY(0f).alpha(1f)
                        .setDuration(160L)
                        .setInterpolator(android.view.animation.PathInterpolator(
                                0.22f, 0.9f, 0.3f, 1f))
                        .start()
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6f)
            sortRow.addView(chip, lp)
        }
        appSection.addView(sortRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        styleSort()

        appList.divider = null
        appList.dividerHeight = 0
        appList.isVerticalScrollBarEnabled = false
        appList.clipToPadding = false
        appList.setPadding(0, 0, 0, dp(92f))
        appList.adapter = appAdapter
        appList.setOnItemClickListener { _, _, position, _ ->
            val e = appAdapter.items.getOrNull(position) ?: return@setOnItemClickListener
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${e.packageName}")))
            } catch (_: Throwable) {
                toast("无法打开应用信息")
            }
        }
        appSection.addView(appList, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(appSection, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refreshShizuku()
    }

    private fun buildGarbageSection() {
        garbageSection.orientation = VERTICAL
        garbageSection.visibility = View.GONE
        garbageTitle.apply {
            text = "垃圾建议"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }
        garbageSection.addView(garbageTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        garbageStatus.apply {
            text = "复用现有分类与删除策略；不可删项会置灰"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, dp(6f))
        }
        garbageSection.addView(garbageStatus, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val tools = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        for ((label, action) in arrayOf(
                "全选" to { selectAllGarbage(true) },
                "全不选" to { selectAllGarbage(false) },
                "反选" to { invertGarbage() },
        )) {
            val tv = TextView(context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.brand)
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                background = rounded(Theme.palette.brandSoft, Theme.palette.brandSoft, Radius.PILL)
                setOnClickListener { action() }
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6f)
            tools.addView(tv, lp)
        }
        garbageSection.addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        garbageBody.orientation = VERTICAL
        garbageBody.setPadding(0, 0, 0, dp(92f))
        scroll.addView(garbageBody, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        garbageSection.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        garbageDelete.apply {
            text = "删除选中（进回收站）"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.danger, Theme.palette.danger, Radius.PILL)
            isEnabled = false
            setOnClickListener { confirmGarbageDelete() }
        }
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, dp(42f))
        lp.topMargin = dp(6f)
        lp.bottomMargin = dp(92f)
        garbageSection.addView(garbageDelete, lp)
        addView(garbageSection, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun startGarbageScan() {
        val snapshot = store
        if (snapshot == null || snapshot.count <= 1) {
            garbageStatus.text = "先到「空间」页扫描一次"
            return
        }
        if (garbageLoading) return
        garbageStarted = true
        garbageLoading = true
        val gen = ++garbageGeneration
        garbageSelected.clear()
        garbageStatus.text = "正在整理垃圾建议…"
        garbageBody.removeAllViews()
        garbageDelete.isEnabled = false
        Thread({
            val result = GarbageAdvisor.scan(snapshot, rootPath)
            handler.post {
                if (gen != garbageGeneration) return@post
                garbageLoading = false
                garbageCategories = result
                renderGarbage()
            }
        }, "FileLens-Garbage").start()
    }

    private fun renderGarbage() {
        garbageBody.removeAllViews()
        val totalItems = garbageCategories.sumOf { it.count }
        val totalBytes = garbageCategories.sumOf { it.bytes }
        garbageStatus.text = "共 $totalItems 项 · ${Fmt.bytes(totalBytes)} · 不可删项已置灰"
        if (totalItems == 0) {
            garbageBody.addView(TextView(context).apply {
                text = "没有发现可建议清理的垃圾"
                textSize = 13f
                setTextColor(Theme.palette.muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(36f), 0, 0)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            garbageDelete.isEnabled = false
            return
        }
        for (cat in garbageCategories) {
            if (cat.count == 0) continue
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = rounded(Theme.palette.panelSoft, Theme.palette.line, Radius.PANEL)
            }
            val clp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            clp.bottomMargin = dp(8f)
            garbageBody.addView(card, clp)

            val head = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(context).apply {
                text = "${cat.label} · ${cat.count} 项 · ${Fmt.bytes(cat.bytes)}"
                textSize = 15f
                typeface = Draw.SANS_MEDIUM
                setTextColor(Theme.palette.text)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val riskColor = if (cat.risk == "谨慎") Theme.palette.danger else Theme.palette.warn
            val badge = TextView(context).apply {
                text = cat.risk
                textSize = 11f
                typeface = Draw.SANS_MEDIUM
                gravity = Gravity.CENTER
                setTextColor(riskColor)
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                background = rounded(Theme.alpha(riskColor, 24), Theme.alpha(riskColor, 80), Radius.PILL)
            }
            head.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            val selectableItems = cat.items.count { garbageSelectable(it.node) }
            val catCheck = CheckBox(context).apply {
                isChecked = selectableItems > 0 && cat.items.all {
                    !garbageSelectable(it.node) || garbageSelected.contains(it.node)
                }
                isEnabled = selectableItems > 0
                alpha = if (selectableItems > 0) 1f else 0.4f
                setOnCheckedChangeListener { _, checked ->
                    selectGarbageCategory(cat, checked)
                    renderGarbage()
                }
            }
            head.addView(catCheck, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            card.addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            card.setOnClickListener {
                if (garbageExpanded.contains(cat.key)) garbageExpanded.remove(cat.key)
                else garbageExpanded.add(cat.key)
                renderGarbage()
            }

            if (!garbageExpanded.contains(cat.key)) continue
            for (item in cat.items) {
                val decision = DeletePolicy.decide(context, store = store ?: return,
                        node = item.node, rootPath = rootPath)
                val row = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    background = rounded(Theme.palette.panel, Theme.palette.line, Radius.SM)
                }
                val check = CheckBox(context).apply {
                    text = item.name
                    textSize = 12f
                    isEnabled = decision.selectable
                    isChecked = garbageSelected.contains(item.node)
                    setTextColor(if (decision.selectable) Theme.palette.text else Theme.palette.muted)
                    alpha = if (decision.selectable) 1f else 0.55f
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) garbageSelected.add(item.node) else garbageSelected.remove(item.node)
                        updateGarbageSummary()
                    }
                }
                row.addView(check, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                row.addView(TextView(context).apply {
                    text = if (decision.selectable) item.path else "${item.path} · ${decision.reason}"
                    textSize = 9.5f
                    setTextColor(Theme.palette.muted)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(dp(28f), 0, 0, 0)
                })
                val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4f)
                card.addView(row, lp)
            }
            if (cat.truncated) {
                card.addView(TextView(context).apply {
                    text = "还有 ${cat.count - cat.items.size} 条未展开"
                    textSize = 11f
                    setTextColor(Theme.palette.muted)
                    setPadding(dp(28f), dp(4f), 0, 0)
                })
            }
        }
        updateGarbageSummary()
    }

    private fun garbageSelectable(node: Int): Boolean {
        val snapshot = store ?: return false
        return DeletePolicy.decide(context, store = snapshot,
                node = node, rootPath = rootPath).selectable
    }

    private fun selectAllGarbage(selected: Boolean) {
        for (cat in garbageCategories) {
            for (item in cat.items) {
                if (garbageSelectable(item.node)) {
                    if (selected) garbageSelected.add(item.node)
                    else garbageSelected.remove(item.node)
                }
            }
        }
        renderGarbage()
    }

    private fun invertGarbage() {
        for (cat in garbageCategories) {
            for (item in cat.items) {
                if (!garbageSelectable(item.node)) continue
                if (garbageSelected.contains(item.node)) garbageSelected.remove(item.node)
                else garbageSelected.add(item.node)
            }
        }
        renderGarbage()
    }

    private fun selectGarbageCategory(cat: GarbageAdvisor.Category, selected: Boolean) {
        for (item in cat.items) {
            if (!garbageSelectable(item.node)) continue
            if (selected) garbageSelected.add(item.node) else garbageSelected.remove(item.node)
        }
    }

    private fun updateGarbageSummary() {
        var bytes = 0L
        for (cat in garbageCategories) {
            for (item in cat.items) {
                if (garbageSelected.contains(item.node)) bytes += item.size
            }
        }
        garbageStatus.text = "已选 ${garbageSelected.size} 项 · 可释放 ${Fmt.bytes(bytes)}"
        garbageDelete.isEnabled = garbageSelected.isNotEmpty()
    }

    private fun confirmGarbageDelete() {
        if (garbageSelected.isEmpty()) return
        val nodes = garbageSelected.toIntArray()
        Dialogs.showDeleteConfirm(context as android.app.Activity, "删除垃圾建议",
                "将把 ${nodes.size} 项移入回收站，可恢复。\n只删除可选项；不可删项不会进入这里。") {
            doGarbageDelete(nodes)
        }
    }

    private fun doGarbageDelete(nodes: IntArray) {
        val snapshot = store ?: return
        val targets = ArrayList<Triple<File, String, Int>>()
        for (node in nodes) {
            val decision = DeletePolicy.decide(context, snapshot, node, rootPath)
            if (!decision.selectable) continue
            val abs = snapshot.absolutePathOf(rootPath, node)
            val rel = snapshot.relativePathOf(node)
            if (rel.isEmpty()) continue
            targets.add(Triple(File(abs), rel, node))
        }
        if (targets.isEmpty()) return
        garbageDelete.isEnabled = false
        Thread({
            val result = Trash.move(context, targets.map { it.first to it.second })
            val moved = targets.filter { !it.first.exists() }.map { it.third }.toIntArray()
            if (moved.isNotEmpty()) snapshot.markDeleted(moved)
            handler.post {
                toast(if (result.skipped > 0) "已移入 ${result.moved} 项，跳过 ${result.skipped} 项" else "已移入回收站 ${result.moved} 项")
                startGarbageScan()
            }
        }, "FileLens-GarbageTrash").start()
    }

    private fun buildShizukuCard() {
        shizukuCard.orientation = VERTICAL
        shizukuCard.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        shizukuCard.background = rounded(Theme.palette.panelSoft, Theme.palette.line, Radius.PANEL)
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8f)
        shizukuCard.layoutParams = lp

        shizukuText.textSize = 12f
        shizukuText.setTextColor(Theme.palette.muted)
        shizukuText.setLineSpacing(dp(3f).toFloat(), 1f)
        shizukuCard.addView(shizukuText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8f), 0, 0)
        }
        shizukuAction.textSize = 11.5f
        shizukuAction.setTextColor(Theme.palette.brand)
        shizukuAction.background = rounded(Theme.palette.brandSoft, Theme.palette.brandSoft, Radius.PILL)
        shizukuAction.setOnClickListener { onShizukuAction() }
        clearCache.text = "清缓存"
        clearCache.textSize = 11.5f
        clearCache.setTextColor(Color.WHITE)
        clearCache.background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
        clearCache.isEnabled = false
        clearCache.setOnClickListener { confirmClearCache() }
        row.addView(shizukuAction, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val clp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        clp.leftMargin = dp(8f)
        row.addView(clearCache, clp)
        shizukuCard.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun onShown() {
        if (segment == 1) {
            onAppSelected()
            return
        }
        if (segment == 2) {
            if (!garbageStarted) startGarbageScan()
            return
        }
        if (!started && !running) {
            started = true
            if ((store?.count ?: 0) > 1) startScan()
        }
    }

    fun onResume() {
        if (segment != 1) return
        refreshShizuku()
        if (!AppStats.hasUsageAccess(context)) {
            showUsageGate()
        } else {
            hideUsageGate()
            if (!appLoaded && !appLoading) loadApps()
        }
    }

    fun refresh() {
        if (segment == 0 && !running) startScan()
    }

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
        garbageGeneration++
        garbageLoading = false
        garbageStarted = false
        garbageCategories = emptyList()
        garbageSelected.clear()
        garbageBody.removeAllViews()
        garbageDelete.isEnabled = false
        garbageStatus.text = "复用现有分类与删除策略；不可删项会置灰"
        if (segment == 2) startGarbageScan()
    }

    fun onShizukuPermissionResult() {
        refreshShizuku()
        val st = CacheCleaner.status(context)
        if (st.state == CacheCleaner.State.AUTHORIZED) toast("Shizuku 已授权")
    }

    private fun onAppSelected() {
        refreshShizuku()
        if (!AppStats.hasUsageAccess(context)) {
            showUsageGate()
        } else {
            hideUsageGate()
            if (!appLoaded && !appLoading) loadApps()
        }
    }

    private fun showUsageGate() {
        appEntries = emptyList()
        appLoaded = false
        appAdapter.items = emptyList()
        appAdapter.notifyDataSetChanged()
        usageGate.visibility = View.VISIBLE
        appSummary.visibility = View.GONE
        appStatus.visibility = View.GONE
        appProgress.visibility = View.GONE
        shizukuCard.visibility = View.GONE
        sortRow.visibility = View.GONE
        appList.visibility = View.GONE
    }

    private fun hideUsageGate() {
        usageGate.visibility = View.GONE
        appSummary.visibility = View.VISIBLE
        appStatus.visibility = View.VISIBLE
        shizukuCard.visibility = if (shizuku.state == CacheCleaner.State.AUTHORIZED)
            View.VISIBLE else View.GONE
        sortRow.visibility = View.VISIBLE
        appList.visibility = View.VISIBLE
    }

    private fun openUsageSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Throwable) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Throwable) {
                toast("无法打开系统设置")
            }
        }
    }

    private fun loadApps() {
        if (appLoading) return
        hideUsageGate()
        val gen = ++appGeneration
        appLoading = true
        appLoaded = false
        appProgress.visibility = View.VISIBLE
        appProgress.max = 1
        appProgress.progress = 0
        appStatus.visibility = View.VISIBLE
        appStatus.text = "正在统计 0/0"
        Thread({
            val result = AppStats.load(context) { done, total ->
                handler.post {
                    if (gen != appGeneration) return@post
                    appProgress.max = max(1, total)
                    appProgress.progress = done
                    appStatus.text = "正在统计 $done/$total"
                }
            }
            handler.post {
                if (gen != appGeneration) return@post
                appEntries = result.entries
                appFailed = result.failed
                appLoading = false
                appLoaded = true
                appProgress.visibility = View.GONE
                renderAppData()
                refreshShizuku()
            }
        }, "FileLens-AppStats").start()
    }

    private fun renderAppData() {
        val sorted = AppStats.sort(appEntries, sortByCache)
        appAdapter.items = sorted
        appAdapter.notifyDataSetChanged()
        val data = appEntries.sumOf { it.dataBytes }
        val cache = appEntries.sumOf { it.cacheBytes }
        appSummary.text = "共 ${appEntries.size} 个应用 · 数据 ${Fmt.bytes(data)} · 缓存 ${Fmt.bytes(cache)}"
        val failedText = if (appFailed > 0) " · $appFailed 个应用无法统计" else ""
        val freed = pendingFreed
        pendingFreed = null
        appStatus.text = when {
            freed != null && cache < freed -> "已刷新：缓存减少 ${Fmt.bytes(freed - cache)}$failedText"
            freed != null -> "已执行清理；系统统计未报告减少量$failedText"
            appEntries.isEmpty() -> "没有可统计的应用$failedText"
            else -> "点应用打开系统信息$failedText"
        }
    }

    private fun styleSort() {
        for ((i, chip) in arrayOf(sortTotal, sortCache).withIndex()) {
            val active = (i == 1) == sortByCache
            chip.setTextColor(if (active) Color.WHITE else Theme.palette.text2)
            chip.background = rounded(if (active) Theme.palette.brand else Theme.palette.panelSunken,
                    if (active) Theme.palette.brand else Theme.palette.line, Radius.SM)
        }
    }

    private fun refreshShizuku() {
        shizuku = CacheCleaner.status(context)
        shizukuText.text = when (shizuku.state) {
            CacheCleaner.State.NOT_INSTALLED ->
                "Shizuku 未安装。普通应用无法清理其它应用缓存；安装并激活 Shizuku 后才可用。"
            CacheCleaner.State.NOT_RUNNING ->
                "Shizuku 已安装，但当前未运行。请打开 Shizuku 并完成激活。"
            CacheCleaner.State.NOT_AUTHORIZED ->
                "Shizuku 已运行，尚未授权。授权后才能执行缓存清理。"
            CacheCleaner.State.AUTHORIZED ->
                "Shizuku 已授权。会先全局回收所有应用缓存，再清外部 cache；不会丢登录。"
        }
        shizukuAction.text = when (shizuku.state) {
            CacheCleaner.State.NOT_INSTALLED -> "去了解 Shizuku"
            CacheCleaner.State.NOT_RUNNING -> "打开 Shizuku"
            CacheCleaner.State.NOT_AUTHORIZED -> "请求授权"
            CacheCleaner.State.AUTHORIZED -> "重新检测"
        }
        shizukuCard.visibility = if (shizuku.state == CacheCleaner.State.AUTHORIZED)
            View.VISIBLE else View.GONE
        clearCache.isEnabled = shizuku.state == CacheCleaner.State.AUTHORIZED && !clearing
        clearCache.alpha = if (clearCache.isEnabled) 1f else 0.45f
    }

    private fun onShizukuAction() {
        when (shizuku.state) {
            CacheCleaner.State.NOT_INSTALLED -> openShizukuSite()
            CacheCleaner.State.NOT_RUNNING -> openShizukuApp()
            CacheCleaner.State.NOT_AUTHORIZED -> {
                if (!CacheCleaner.requestPermission()) openShizukuApp()
            }
            CacheCleaner.State.AUTHORIZED -> {
                refreshShizuku()
                toast("Shizuku 已授权")
            }
        }
    }

    private fun openShizukuSite() {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
        } catch (_: Throwable) {
            toast("无法打开浏览器")
        }
    }

    private fun openShizukuApp() {
        try {
            val intent = context.packageManager
                    .getLaunchIntentForPackage(CacheCleaner.SHIZUKU_PACKAGE)
            if (intent != null) context.startActivity(intent) else toast("未找到 Shizuku")
        } catch (_: Throwable) {
            toast("无法打开 Shizuku")
        }
    }

    private fun confirmClearCache() {
        val activity = context as? android.app.Activity ?: return
        if (shizuku.state != CacheCleaner.State.AUTHORIZED) return
        Dialogs.showMessage(activity, "清缓存",
                "只清缓存，不会丢登录；首次打开会慢一点。\n\n" +
                        "先执行系统全局缓存回收（影响所有应用），再只删除各应用外部 cache 目录。",
                positive = "清缓存", onPositive = { doClearCache() },
                negative = "取消", onNegative = {})
    }

    private fun doClearCache() {
        if (clearing || appEntries.isEmpty()) {
            toast("没有可清理的应用数据")
            return
        }
        clearing = true
        pendingFreed = appEntries.sumOf { it.cacheBytes }
        clearCache.isEnabled = false
        appStatus.text = "正在清理…"
        val packages = appEntries.map { it.packageName }
        Thread({
            val result = CacheCleaner.clearAll(packages) { done, total ->
                handler.post {
                    if (clearing) appStatus.text = "正在清理 $done/$total"
                }
            }
            handler.post {
                clearing = false
                if (!result.ok) {
                    pendingFreed = null
                    toast("清理失败：${result.message}")
                } else {
                    toast("清理完成，正在刷新统计")
                }
                loadApps()
            }
        }, "FileLens-CacheClean").start()
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
                textSize = 13f
                setTextColor(Theme.palette.muted)
                gravity = Gravity.CENTER
                setPadding(0, dp(36f), 0, 0)
            }
            body.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            return
        }
        for ((gi, group) in groups.withIndex()) {
            val groupCard = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(8f), dp(6f), dp(8f), dp(8f))
                background = rounded(Theme.palette.panelSoft, Theme.palette.line, Radius.PANEL)
            }
            val groupLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            groupLp.bottomMargin = dp(10f)
            body.addView(groupCard, groupLp)

            val header = TextView(context).apply {
                text = "第 ${gi + 1} 组 · ${Fmt.bytes(group.size)} × ${group.files.size} 份 · 可释放 ${Fmt.bytes(group.reclaim)}"
                textSize = 15f
                typeface = Draw.SANS_MEDIUM
                setTextColor(Theme.palette.text)
                setPadding(dp(4f), dp(8f), dp(4f), dp(4f))
            }
            groupCard.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
                groupCard.addView(row, lp)
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

    private inner class AppAdapter : BaseAdapter() {
        var items: List<AppStats.Entry> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items.getOrNull(position) ?: return View(context)
            val row: LinearLayout
            val icon: ImageView
            val name: TextView
            val pkg: TextView
            val right: TextView
            if (convertView == null) {
                row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64f)
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    background = rounded(Theme.palette.panel, Theme.palette.line, Radius.SM)
                }
                icon = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = rounded(Theme.palette.panelSunken, Theme.palette.line, Radius.SM)
                }
                row.addView(icon, LayoutParams(dp(48f), dp(48f)))
                val middle = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10f), 0, dp(6f), 0)
                }
                name = TextView(context).apply {
                    textSize = 15f
                    setTextColor(Theme.palette.text)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                pkg = TextView(context).apply {
                    textSize = 12f
                    setTextColor(Theme.palette.muted)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }
                middle.addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                middle.addView(pkg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                row.addView(middle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                right = TextView(context).apply {
                    textSize = 13f
                    setTextColor(Theme.palette.text2)
                    gravity = Gravity.END
                    setLineSpacing(dp(1.5f).toFloat(), 1f)
                }
                row.addView(right, LayoutParams(dp(108f), LayoutParams.WRAP_CONTENT))
                row.tag = arrayOf(icon, name, pkg, right)
            } else {
                row = convertView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                val views = row.tag as Array<View>
                icon = views[0] as ImageView
                name = views[1] as TextView
                pkg = views[2] as TextView
                right = views[3] as TextView
            }
            name.text = item.label
            pkg.text = if (item.system) "${item.packageName} · 系统" else item.packageName
            icon.setImageDrawable(item.icon ?: context.getDrawable(android.R.drawable.sym_def_app_icon))
            right.text = "数据 ${Fmt.bytes(item.dataBytes)}\n缓存 ${Fmt.bytes(item.cacheBytes)}\n本体 ${Fmt.bytes(item.appBytes)}"
            return row
        }
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
