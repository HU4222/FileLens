package com.spacelens.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.spacelens.app.core.CacheCleaner
import com.spacelens.app.core.Classifier
import com.spacelens.app.core.DeletePolicy
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.spacelens.app.core.Export
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.SystemTrash
import com.spacelens.app.core.Health
import com.spacelens.app.core.FileOps
import com.spacelens.app.core.NodeStore
import com.spacelens.app.core.RecvDir
import com.spacelens.app.core.Scanner
import com.spacelens.app.core.SelectionState
import com.spacelens.app.core.SnapshotStore
import com.spacelens.app.core.Trash
import com.spacelens.app.data.Prefs
import com.spacelens.app.bench.BenchEngine
import com.spacelens.app.ui.BackdropView
import com.spacelens.app.ui.BenchPage
import com.spacelens.app.ui.Breakpoints
import com.spacelens.app.ui.CleanPage
import com.spacelens.app.ui.CrashLogPage
import com.spacelens.app.ui.DetailPane
import com.spacelens.app.ui.Dialogs
import com.spacelens.app.ui.DirPicker
import com.spacelens.app.ui.FpsHudView
import com.spacelens.app.ui.FrameStats
import com.spacelens.app.ui.Motion
import com.spacelens.app.ui.MinePage
import com.spacelens.app.ui.NavRail
import com.spacelens.app.ui.OverviewPage
import com.spacelens.app.ui.ResponsiveShell
import com.spacelens.app.ui.SearchPage
import com.spacelens.app.ui.SplashView
import com.spacelens.app.ui.SwipePager
import com.spacelens.app.ui.TabBar
import com.spacelens.app.ui.Theme
import com.spacelens.app.ui.ThemeMode
import com.spacelens.app.ui.TopBar
import com.spacelens.app.ui.PreviewPage
import com.spacelens.app.ui.SettingsPage
import com.spacelens.app.ui.TransferPage
import com.spacelens.app.ui.TrashPage
import com.spacelens.app.ui.TreePage
import com.spacelens.app.ui.WindowClass
import java.io.File

/**
 * 单 Activity + 4 页。状态只从这里进，页面不各自持有业务状态机。
 * M2：主题 / 页签 / 扫描 / 快照 / 树图；M3 起接动效与 FPS HUD。
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var shell: ResponsiveShell
    private lateinit var navRail: NavRail
    private lateinit var detailPane: DetailPane
    private lateinit var fpsHud: FpsHudView
    private lateinit var undoBar: android.widget.TextView
    private lateinit var undoLayer: FrameLayout
    private var lastTrashBatch: String? = null
    @Volatile private var deleteCancelled = false
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingUndo: (() -> Unit)? = null
    private lateinit var topBar: TopBar
    private lateinit var pager: SwipePager
    private lateinit var tabBar: TabBar
    private lateinit var overviewPage: OverviewPage
    private lateinit var treePage: TreePage
    private lateinit var benchPage: BenchPage
    private lateinit var minePage: MinePage
    private lateinit var searchPage: SearchPage
    private lateinit var cleanPage: CleanPage
    private lateinit var transferPage: TransferPage
    private lateinit var settingsPage: SettingsPage
    private lateinit var treeOverlay: FrameLayout
    private lateinit var benchOverlay: FrameLayout
    private var treeOrigin: Rect? = null
    private var benchOrigin: Rect? = null
    private var benchRunning = false

    private var pendingScan = false
    private var awaitingSettings = false
    private var visibleTab = -1
    private var previousTab = 0

    private val store = NodeStore()
    private var scanner: Scanner? = null
    private var scanGeneration = 0
    private var snapshotLoading = false
    /** 索引是否可查：快照加载/扫描完成前为 true，搜索页据此显示「正在载入」。 */
    @Volatile private var indexLoading = true
    private var pendingStartAfterIo = false
    private var saveThread: Thread? = null
    private val selection = SelectionState()
    private var detailNode = 0

    private val titles = arrayOf("空间", "搜索", "清理", "传输", "设置")
    private val subtitles = arrayOf(
            "存储总览 · 空间树",
            "只搜内存索引 · 不重扫磁盘",
            "重复文件 · 删除进回收站",
            "本机文件互传",
            "权限 · 回收站",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bootAt = android.os.SystemClock.uptimeMillis()
        prefs = Prefs(this)
        Theme.init(this, prefs.themeMode)
        FrameStats.reset()
        try {
            Motion.powerSave = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode
        } catch (_: Throwable) {
            Motion.powerSave = false
        }
        applyWindowTheme()
        buildUi()
        requestHighRefresh()
        Thread({ Trash.purgeExpired(this) }, "SpaceLens-TrashExpire").start()
        setContentView(root)
        installInsets()
        applyBreakpoint()
        selectTab(0, animate = false)
        updateMine()
        updateOverview()
        loadSnapshot()
        cleanupExpiredTrash()

        // 启动标识（420ms），首帧即品牌页，不阻塞交互初始化
        root.addView(SplashView(this), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        if (prefs.fpsHud) fpsHud.setShowing(true)
        android.util.Log.i("SpaceLens", "onCreate done +${android.os.SystemClock.uptimeMillis() - bootAt}ms " +
                "width=${resources.configuration.screenWidthDp}dp activity=${hashCode()} " +
                "displayHz=${currentRefreshRate()}")
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Theme.palette.bg)
        root.addView(BackdropView(this), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        shell = ResponsiveShell(this)
        navRail = NavRail(this)
        topBar = TopBar(this)
        pager = SwipePager(this)
        tabBar = TabBar(this)
        detailPane = DetailPane(this)
        overviewPage = OverviewPage(this)
        treePage = TreePage(this)
        benchPage = BenchPage(this)
        minePage = MinePage(this)
        searchPage = SearchPage(this)
        searchPage.indexLoading = { indexLoading }
        cleanPage = CleanPage(this)
        settingsPage = SettingsPage(this)
        settingsPage.onTrash = { showTrashPage() }
        settingsPage.onCrashLog = { showCrashLogPage() }
        settingsPage.onClose = { selectTab(if (previousTab in 0..3) previousTab else 0, true) }
        settingsPage.onRequestCamera = {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        transferPage = TransferPage(this)
        transferPage.onPanelVisibility = { open -> tabBar.isEnabled = !open }
        transferPage.onPickRecvDir = { pickRecvDir() }
        transferPage.onSheetVisibility = { open ->
            tabBar.isEnabled = !open
            if (open) {
                tabBar.animate().alpha(0f).setDuration(150L)
                        .withEndAction { tabBar.visibility = View.GONE }.start()
            } else {
                tabBar.animate().cancel()
                tabBar.visibility = View.VISIBLE
                tabBar.alpha = 0f
                tabBar.animate().alpha(1f).setDuration(150L).start()
            }
        }

        pager.addView(overviewPage, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pager.addView(searchPage, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pager.addView(cleanPage, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pager.addView(transferPage, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pager.addView(settingsPage, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        shell.bind(navRail, topBar, pager, tabBar, detailPane)
        root.addView(shell, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        fpsHud = FpsHudView(this)
        fpsHud.visibility = View.GONE
        val hudLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        hudLp.gravity = Gravity.TOP or Gravity.END
        hudLp.topMargin = (80 * resources.displayMetrics.density).toInt()
        hudLp.rightMargin = (12 * resources.displayMetrics.density).toInt()
        root.addView(fpsHud, hudLp)
        undoLayer = FrameLayout(this).apply {
            isClickable = true
            elevation = 24f * resources.displayMetrics.density
            visibility = View.GONE
        }
        undoBar = android.widget.TextView(this).apply {
            textSize = 11f
            setTextColor(Theme.palette.text)
            gravity = android.view.Gravity.CENTER
            setPadding((16 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * resources.displayMetrics.density
                setColor(Theme.palette.brandSoft)
                setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), Theme.palette.brand)
            }
            isClickable = true
            setOnClickListener { runPendingUndo() }
        }
        undoLayer.addView(undoBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        // 独立浮层：最后添加 + 显示时 bringToFront，保证在 pager/TabBar 之上并自己吃触摸。
        undoLayer.setOnClickListener { runPendingUndo() }
        val undoLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        undoLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        undoLp.bottomMargin = (120 * resources.displayMetrics.density).toInt()
        root.addView(undoLayer, undoLp)

        // 空间树保留为「空间」页内的全屏二级页，不占第 5 个 Tab。
        treeOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Theme.palette.bg)
        }
        treeOverlay.addView(treePage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val backToSpace = TextView(this).apply {
            text = "返回空间总览"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.brand)
            setPadding((16 * resources.displayMetrics.density).toInt(),
                    (9 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (9 * resources.displayMetrics.density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * resources.displayMetrics.density
                setColor(Theme.palette.brandSoft)
                setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                        Theme.palette.brand)
            }
            setOnClickListener { hideTreeOverlay() }
        }
        val backLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        backLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        backLp.bottomMargin = (22 * resources.displayMetrics.density).toInt()
        treeOverlay.addView(backToSpace, backLp)
        root.addView(treeOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 测速保留为空间页的二级全屏页，不占第 5 个 Tab。
        benchOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Theme.palette.bg)
        }
        benchOverlay.addView(benchPage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val backFromBench = TextView(this).apply {
            text = "返回空间总览"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.brand)
            setPadding((16 * resources.displayMetrics.density).toInt(),
                    (9 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (9 * resources.displayMetrics.density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * resources.displayMetrics.density
                setColor(Theme.palette.brandSoft)
                setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                        Theme.palette.brand)
            }
            setOnClickListener { hideBenchOverlay() }
        }
        val bblp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        bblp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        bblp.bottomMargin = (22 * resources.displayMetrics.density).toInt()
        benchOverlay.addView(backFromBench, bblp)
        root.addView(benchOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        topBar.onLongPress = { toggleFpsHud() }
        tabBar.onSelect = { selectTab(it, animate = true) }
        navRail.onSelect = { selectTab(it, animate = true) }
        pager.onProgress = { progress -> tabBar.setSwipeProgress(progress) }
        pager.onPageChanged = { onPageSettled(it) }

        overviewPage.onStartScan = { requestScan() }
        overviewPage.onOpenTree = { showTreeOverlay(overviewPage.lastQuickCardRectOnScreen()) }
        overviewPage.onHealth = {
            selectTab(2, animate = true)
            cleanPage.onShown()
        }
        overviewPage.onBench = { showBenchOverlay(overviewPage.lastQuickCardRectOnScreen()) }
        overviewPage.onSettings = { selectTab(4, animate = true) }
        overviewPage.onCategory = { category ->
            searchPage.applyTypeFilter(category)
            selectTab(1, animate = true)
        }
        treePage.onStartScan = { requestScan() }
        treePage.onCancelScan = { cancelScan() }
        treePage.onNodeLongPress = { node -> onTreeNodeLongPress(node) }
        treePage.onAggregateTap = { nodes -> showAggregate(nodes) }
        treePage.onUnavailableView = { name -> toast(name + "将在后续阶段接入") }
        treePage.onRootChanged = { _ -> detailNode = -1; updateDetail() }
        treePage.onFileTap = { node -> showNodeDetail(node) }
        treePage.onItemMenu = { node -> showItemMenu(node, true) }
        treePage.onSelectionToggle = { node -> toggleSelection(node) }
        treePage.onCancelSelection = { cancelSelection() }
        treePage.onDeleteSelection = { showDeletePreview() }
        treePage.onSelectAllSelection = {
            if (store.count > 1) {
                selection.selectAllSiblings(store, treePage.rootIndex)
                updateSelectionUi()
            }
        }
        treePage.onSearch = {
            hideTreeOverlay()
            selectTab(1, animate = true)
            searchPage.onShown()
        }
        searchPage.store = store
        searchPage.onPreview = { node ->
            showPreview(listOf(store.absolutePathOf(rootPath(), node)), 0)
        }
        searchPage.rootPath = rootPath()
        searchPage.onOpenLocation = { node -> showTreeAt(node) }
        searchPage.onDeleteRequested = { node -> deleteSingle(node) }
        searchPage.onItemMenu = { node -> showItemMenu(node, false) }
        cleanPage.store = store
        cleanPage.onBatchDeleted = { batch ->
            lastTrashBatch = batch
            showUndoBar()
        }
        cleanPage.rootPath = rootPath()
        benchPage.onStart = { seconds -> startBench(seconds) }
        benchPage.onCancel = {
            BenchEngine.cancel()
            benchRunning = false
            benchPage.setRunning(false)
            toast("已取消测速")
        }
        minePage.onAction = { onMineAction(it) }

        detailPane.onCopyPath = { path -> copyPath(path) }
        detailPane.onOpen = { toast("用其他应用打开将在 M5 接入") }
        detailPane.onSelect = { node -> onDetailSelect(node) }
        detailPane.onStartScan = { requestScan() }
        detailPane.onTrash = { showTrash() }
        detailPane.onDeleteSelection = { showDeletePreview() }
        detailPane.onCancelSelection = { cancelSelection() }
    }

    private fun installInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            shell.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            treeOverlay.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            benchOverlay.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun applyBreakpoint() {
        val cls = Breakpoints.of(this)
        shell.setWindowClass(cls, pager.currentPage)
        topBar.showBrand = cls == WindowClass.COMPACT
        navRail.setSelected(pager.currentPage)
        tabBar.setPage(pager.currentPage, animate = false)
        treePage.setWindowClass()
        if (cls == WindowClass.COMPACT) {
            detailPane.visibility = View.GONE
        }
        updateDetail()
        root.requestLayout()
    }

    private fun selectTab(index: Int, animate: Boolean) {
        val i = index.coerceIn(0, titles.size - 1)
        if (visibleTab >= 0 && i != visibleTab) previousTab = visibleTab
        visibleTab = i
        hideTreeOverlay()
        hideBenchOverlay()
        updateTopBar(i)
        shell.setTab(i)
        navRail.setSelected(i.coerceIn(0, 3))
        val animateNow = animate && shell.windowClass != WindowClass.MEDIUM
        pager.setCurrent(i, animateNow)
        tabBar.setPage(i, animate)
        when (i) {
            0 -> {
                overviewPage.refreshStorage()
                overviewPage.onShown()
                syncScanGate()
            }
            1 -> searchPage.onShown()
            2 -> cleanPage.onShown()
            3 -> transferPage.onShown()
            4 -> settingsPage.refresh()
        }
        updateDetail()
    }

    private fun onPageSettled(index: Int) {
        if (visibleTab >= 0 && index != visibleTab) previousTab = visibleTab
        visibleTab = index
        updateTopBar(index)
        shell.setTab(index)
        navRail.setSelected(index.coerceIn(0, 3))
        tabBar.finishSwipe(index)
        when (index) {
            0 -> {
                overviewPage.refreshStorage()
                overviewPage.onShown()
                syncScanGate()
            }
            1 -> searchPage.onShown()
            2 -> cleanPage.onShown()
            3 -> transferPage.onShown()
            4 -> settingsPage.refresh()
        }
        updateDetail()
    }

    private fun isTreeVisible(): Boolean =
            ::treeOverlay.isInitialized && treeOverlay.visibility == View.VISIBLE

    private fun showSettingsPage() {
        val dialog = android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val page = SettingsPage(this)
        page.onClose = { dialog.dismiss() }
        page.onTrash = { showTrashPage() }
        page.onCrashLog = { showCrashLogPage() }
        dialog.setContentView(page)
        dialog.show()
    }


    private var previewDialog: android.app.Dialog? = null

    private fun showPreview(paths: List<String>, start: Int) {
        if (paths.isEmpty()) return
        val dialog = android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val page = PreviewPage(this, paths, start)
        page.onClose = { dialog.dismiss() }
        page.onRenamed = { oldPath, newPath ->
            val idx = store.indexOfPath(rootPath(), oldPath)
            val newName = File(newPath).name
            if (idx > 0) store.renameNode(idx, newName)
            refreshAfterFileOp()
            val oldName = File(oldPath).name
            val parent = File(oldPath).parentFile
            showActionUndo("已重命名为 $newName · 撤销") {
                val moved = File(newPath)
                if (moved.renameTo(File(parent, oldName))) {
                    if (idx > 0) store.renameNode(idx, oldName)
                    refreshAfterFileOp()
                    previewDialog?.dismiss()
                    toast("已撤销改名")
                } else {
                    toast("撤销失败：$oldName 可能已被占用")
                }
            }
        }
        dialog.setOnDismissListener { previewDialog = null }
        dialog.setContentView(page)
        previewDialog = dialog
        dialog.show()
    }

    // ---------------------------------------------------------------- 1.4.1 原生文件操作

    private fun showItemMenu(node: Int, allowSelect: Boolean) {
        hideImeForUndo()
        if (node !in 0 until store.count) {
            toast("节点不存在，先重新扫描")
            return
        }
        if (scanner != null) {
            toast("正在扫描，稍后再操作")
            return
        }
        val isDir = store.flagsOf(node) and NodeStore.DIR != 0
        val file = File(store.absolutePathOf(rootPath(), node))
        val actions = ArrayList<Dialogs.Action>()
        actions.add(Dialogs.Action("重命名") { showRenameDialog(file, node) })
        actions.add(Dialogs.Action("移动到…") { showMovePicker(file, node) })
        if (isDir && allowSelect && treePage.rootIndex != 0) {
            // 新建文件夹创建在「当前正在看的目录」，成功后当前列表能看到；根层不给。
            val currentDir = File(store.absolutePathOf(rootPath(), treePage.rootIndex))
            actions.add(Dialogs.Action("新建文件夹") {
                showNewFolderDialog(currentDir, treePage.rootIndex)
            })
        } else if (!isDir) {
            actions.add(Dialogs.Action("删除（进回收站）", danger = true) { deleteSingle(node) })
        }
        if (allowSelect) {
            actions.add(Dialogs.Action("多选") { treePage.selectFromMenu(node) })
        }
        Dialogs.showActions(this, store.nameOf(node), actions)
    }

    private fun showRenameDialog(file: File, node: Int, onDone: (() -> Unit)? = null) {
        Dialogs.showInput(this, "重命名", "新名字", file.name, "重命名") { raw ->
            when (val check = FileOps.checkName(raw, file.name)) {
                FileOps.NameResult.Same -> return@showInput
                is FileOps.NameResult.Bad -> {
                    toast(check.message)
                    return@showInput
                }
                FileOps.NameResult.Ok -> {
                    val newName = raw.trim()
                    val dup = FileOps.checkTarget(file.parentFile, newName)
                    if (dup != null) {
                        toast(dup)
                        return@showInput
                    }
                    val result = FileOps.rename(file, newName)
                    if (!result.ok) {
                        toast(result.message)
                        return@showInput
                    }
                    val idx = if (node > 0) node else store.indexOfPath(rootPath(), file.absolutePath)
                    if (idx > 0) store.renameNode(idx, newName)
                    refreshAfterFileOp()
                    toast(result.message)
                    onDone?.invoke()
                    val oldName = file.name
                    showActionUndo("已重命名为 $newName · 撤销") {
                        val renamed = File(file.parentFile, newName)
                        if (renamed.renameTo(file)) {
                            if (idx > 0) store.renameNode(idx, oldName)
                            refreshAfterFileOp()
                            toast("已撤销改名")
                        } else {
                            toast("撤销失败：$oldName 可能已被占用")
                        }
                    }
                }
            }
        }
    }

    private fun showMovePicker(file: File, node: Int) {
        val snap: NodeStore? = store
        DirPicker.show(this, snap, rootPath(), file.parentFile, false, onPick = pick@{ target ->
            if (FileOps.sameDir(file, target)) return@pick
            val result = FileOps.move(file, target)
            if (!result.ok) {
                toast(result.message)
                return@pick
            }
            val idx = if (node > 0) node else store.indexOfPath(rootPath(), file.absolutePath)
            val targetIdx = store.indexOfPath(rootPath(), target.absolutePath)
            if (idx > 0 && targetIdx >= 0) store.moveNode(idx, targetIdx)
            refreshAfterFileOp()
            toast(result.message)
            val moved = File(target, file.name)
            val oldParent = file.parentFile
            showActionUndo("已移动到 ${target.name} · 撤销") {
                val back = FileOps.move(moved, oldParent)
                if (!back.ok) {
                    toast("撤销失败：${back.message}")
                } else {
                    if (idx > 0) {
                        val backIdx = store.indexOfPath(rootPath(), oldParent.absolutePath)
                        if (backIdx >= 0) store.moveNode(idx, backIdx)
                    }
                    refreshAfterFileOp()
                    toast("已撤销移动")
                }
            }
        })
    }

    private fun showNewFolderDialog(dir: File, node: Int) {
        Dialogs.showInput(this, "新建文件夹", "文件夹名字", "", "新建") { raw ->
            when (val check = FileOps.checkName(raw, null)) {
                FileOps.NameResult.Same -> return@showInput
                is FileOps.NameResult.Bad -> {
                    toast(check.message)
                    return@showInput
                }
                FileOps.NameResult.Ok -> {
                    val name = raw.trim()
                    val dup = FileOps.checkTarget(dir, name)
                    if (dup != null) {
                        toast(dup)
                        return@showInput
                    }
                    val result = FileOps.newFolder(dir, name)
                    if (!result.ok) {
                        toast(result.message)
                        return@showInput
                    }
                    store.addDir(node, name, System.currentTimeMillis())
                    refreshAfterFileOp()
                    toast(result.message)
                }
            }
        }
    }

    /** P5：局部刷新。NodeStore 已就地打补丁，这里只让页面重读同一份数据。 */
    private fun refreshAfterFileOp() {
        if (::treePage.isInitialized) treePage.refreshTree()
        if (::searchPage.isInitialized) searchPage.refreshNow()
        updateOverview()
        updateMine()
        updateDetail()
    }

    private fun pickRecvDir() {
        val snap: NodeStore? = store
        val start = RecvDir.resolve(this)
        DirPicker.show(this, snap, rootPath(), start, true,
                onPick = { dir ->
                    val error = RecvDir.probe(dir)
                    if (error != null) {
                        toast(error)
                    } else {
                        RecvDir.save(this, dir)
                        toast("接收目录已改为 ${dir.absolutePath}")
                        transferPage.refreshRecvDir(dir.absolutePath)
                    }
                },
                onRestoreDefault = {
                    RecvDir.clear(this)
                    toast("已恢复默认接收目录 Download/FileLens")
                    transferPage.refreshRecvDir(RecvDir.defaultDir().absolutePath)
                })
    }

    private fun checkRecvDirFallback() {
        if (!::prefs.isInitialized) return
        if (RecvDir.needsFallback(this)) {
            RecvDir.clear(this)
            toast("原接收目录不可用，已切回 Download/FileLens")
        }
    }

    private var trashDialog: android.app.Dialog? = null
    private var trashPage: TrashPage? = null

    private fun showTrashPage() {
        try {
            if (trashDialog?.isShowing == true) {
                trashPage?.onShown()
                return
            }
            if (trashDialog == null) {
                val dialog = android.app.Dialog(this,
                        android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val page = TrashPage(this)
                page.onClose = { dialog.dismiss() }
                dialog.setContentView(page)
                trashDialog = dialog
                trashPage = page
            }
            trashPage?.onShown()
            trashDialog?.show()
        } catch (t: Throwable) {
            android.util.Log.e("FileLensTrash", "open trash failed", t)
            toast("回收站打开失败")
        }
    }

    private var crashDialog: android.app.Dialog? = null
    private var crashPage: CrashLogPage? = null

    private fun showCrashLogPage() {
        try {
            if (crashDialog?.isShowing == true) {
                crashPage?.onShown()
                return
            }
            if (crashDialog == null) {
                val dialog = android.app.Dialog(this,
                        android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val page = CrashLogPage(this)
                page.onClose = { dialog.dismiss() }
                dialog.setContentView(page)
                dialog.setCancelable(false)
                // 返回键只处理本页：先退全文，再关页；不能直接退到桌面。
                dialog.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) page.handleBack()
                        true
                    } else {
                        false
                    }
                }
                dialog.setOnDismissListener {
                    if (::settingsPage.isInitialized) settingsPage.refresh()
                }
                crashDialog = dialog
                crashPage = page
            }
            crashPage?.onShown()
            crashDialog?.show()
        } catch (t: Throwable) {
            android.util.Log.e("FileLensCrash", "open crash log failed", t)
            toast("崩溃日志打开失败")
        }
    }

    private fun cleanupExpiredTrash() {
        Thread {
            val n = try {
                Trash.purgeExpired(this)
            } catch (_: Throwable) {
                0
            }
            if (n > 0) runOnUiThread { toast("已自动清理 $n 项过期文件") }
        }.start()
    }

    private fun showTreeOverlay(origin: Rect? = null) {
        if (!::treeOverlay.isInitialized) return
        tabBar.visibility = View.GONE
        treePage.setWindowClass()
        treeOverlay.animate().cancel()
        treeOverlay.visibility = View.VISIBLE
        treeOverlay.bringToFront()
        treeOrigin = origin
        if (origin == null) {
            treeOverlay.alpha = 1f
            treeOverlay.scaleX = 1f
            treeOverlay.scaleY = 1f
            updateDetail()
            return
        }
        val loc = IntArray(2)
        treeOverlay.getLocationOnScreen(loc)
        treeOverlay.alpha = 0f
        treeOverlay.scaleX = 0.2f
        treeOverlay.scaleY = 0.2f
        treeOverlay.pivotX = (origin.centerX() - loc[0]).toFloat()
        treeOverlay.pivotY = (origin.centerY() - loc[1]).toFloat()
        treeOverlay.post {
            if (treeOverlay.visibility != View.VISIBLE) return@post
            treeOverlay.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(280L)
                    .setInterpolator(android.view.animation.PathInterpolator(
                            0.22f, 0.9f, 0.3f, 1f))
                    .start()
        }
        updateDetail()
    }

    private fun hideTreeOverlay() {
        if (!::treeOverlay.isInitialized) return
        val origin = treeOrigin
        treeOrigin = null
        if (origin == null) {
            treeOverlay.visibility = View.GONE
            restoreTabBar()
            return
        }
        treeOverlay.animate().cancel()
        treeOverlay.animate()
                .alpha(0f).scaleX(0.2f).scaleY(0.2f)
                .setDuration(200L)
                .setInterpolator(android.view.animation.PathInterpolator(
                        0.22f, 0.9f, 0.3f, 1f))
                .withEndAction {
                    treeOverlay.visibility = View.GONE
                    treeOverlay.alpha = 1f
                    treeOverlay.scaleX = 1f
                    treeOverlay.scaleY = 1f
                    restoreTabBar()
                }.start()
    }

    private fun restoreTabBar() {
        if (::tabBar.isInitialized && shell.windowClass == WindowClass.COMPACT) {
            tabBar.visibility = View.VISIBLE
        }
    }

    private fun isBenchVisible(): Boolean =
            ::benchOverlay.isInitialized && benchOverlay.visibility == View.VISIBLE

    private fun showBenchOverlay(origin: Rect? = null) {
        if (!::benchOverlay.isInitialized) return
        tabBar.visibility = View.GONE
        benchOverlay.animate().cancel()
        benchOverlay.visibility = View.VISIBLE
        benchOverlay.bringToFront()
        benchOverlay.requestLayout()
        benchPage.requestLayout()
        benchPage.invalidate()
        benchOrigin = origin
        if (origin == null) {
            benchOverlay.alpha = 1f
            benchOverlay.scaleX = 1f
            benchOverlay.scaleY = 1f
            return
        }
        val loc = IntArray(2)
        benchOverlay.getLocationOnScreen(loc)
        benchOverlay.alpha = 0f
        benchOverlay.scaleX = 0.2f
        benchOverlay.scaleY = 0.2f
        benchOverlay.pivotX = (origin.centerX() - loc[0]).toFloat()
        benchOverlay.pivotY = (origin.centerY() - loc[1]).toFloat()
        benchOverlay.post {
            if (benchOverlay.visibility != View.VISIBLE) return@post
            benchOverlay.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(280L)
                    .setInterpolator(android.view.animation.PathInterpolator(
                            0.22f, 0.9f, 0.3f, 1f))
                    .start()
        }
    }

    private fun hideBenchOverlay() {
        if (!::benchOverlay.isInitialized) return
        val origin = benchOrigin
        benchOrigin = null
        if (origin == null) {
            benchOverlay.visibility = View.GONE
            restoreTabBar()
            return
        }
        benchOverlay.animate().cancel()
        benchOverlay.animate()
                .alpha(0f).scaleX(0.2f).scaleY(0.2f)
                .setDuration(200L)
                .setInterpolator(android.view.animation.PathInterpolator(
                        0.22f, 0.9f, 0.3f, 1f))
                .withEndAction {
                    benchOverlay.visibility = View.GONE
                    benchOverlay.alpha = 1f
                    benchOverlay.scaleX = 1f
                    benchOverlay.scaleY = 1f
                    restoreTabBar()
                }.start()
    }

    private fun showTreeAt(node: Int) {
        val target = if (node in 0 until store.count
                && store.flagsOf(node) and NodeStore.DIR != 0) node else store.parentOf(node)
        if (target >= 0) treePage.showRoot(target)
        showTreeOverlay()
    }

    private fun deleteSingle(node: Int) {
        hideImeForUndo()
        if (node <= 0 || node >= store.count) return
        val d = DeletePolicy.decide(this, store, node, rootPath())
        if (!d.selectable) {
            toast(d.reason)
            return
        }
        val abs = store.absolutePathOf(rootPath(), node)
        val rel = store.relativePathOf(node)
        if (rel.isEmpty()) return
        Dialogs.showDeleteConfirm(this, "删除文件",
                "将把「${store.nameOf(node)}」移入回收站，可恢复。") {
            val batch = Trash.batchId()
            Thread({
                val result = Trash.move(this, listOf(File(abs) to rel), batch)
                runOnUiThread {
                    if (result.moved > 0) {
                        lastTrashBatch = batch
                        store.markDeleted(intArrayOf(node))
                        treePage.refreshTree()
                        searchPage.refreshNow()
                        cleanPage.reset()
                        updateOverview()
                        updateDetail()
                        showUndoBar()
                    } else {
                        toast("移动失败，文件未删除")
                    }
                }
            }, "FileLens-DeleteOne").start()
        }
    }

    private fun updateTopBar(index: Int) {
        topBar.title = titles[index]
        topBar.subtitle = subtitles[index]
    }

    // ---------------------------------------------------------------- 权限与扫描

    private fun hasAllFilesAccess(): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else false

    private fun hasReadAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return hasAllFilesAccess()
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAnyAccess(): Boolean = hasReadAccess() || prefs.safUri != null

    /** 提示条的可见性只跟真实权限走，不跟「有没有点过扫描」走。 */
    private fun syncScanGate() {
        if (hasAnyAccess()) overviewPage.hideScanPermissionGate()
        else overviewPage.showScanPermissionGate()
    }

    private fun requestScan() {
        if (hasAnyAccess()) {
            startScan()
            return
        }
        overviewPage.showScanPermissionGate()
        toast("需要文件访问权限才能扫描，请到设置里授权")
    }

    private fun openAllFilesSettings() {
        prefs.permissionAsked = true
        pendingScan = true
        awaitingSettings = true
        try {
            val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
            )
            startActivityForResult(intent, REQ_ALL_FILES)
        } catch (t: Throwable) {
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES)
            } catch (t2: Throwable) {
                pendingScan = false
                awaitingSettings = false
                showFallback()
            }
        }
    }

    private fun showFallback() {
        Dialogs.showPermissionFallback(
                this,
                onSaf = { pickDirectory() },
                onSettings = { openAllFilesSettings() },
        )
    }

    private fun pickDirectory() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, REQ_SAF)
        } catch (t: Throwable) {
            toast("系统没有提供目录选择器")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                showFallback()
            }
            return
        }
        if (requestCode == CacheCleaner.REQUEST_CODE) {
            if (::cleanPage.isInitialized) cleanPage.onShizukuPermissionResult()
        }
        if (requestCode == REQ_CAMERA) {
            if (::settingsPage.isInitialized) {
                val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
                settingsPage.refresh()
                if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                    settingsPage.openAppDetails()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TransferPage.REQ_FILE || requestCode == TransferPage.REQ_SCAN) {
            if (::transferPage.isInitialized) {
                transferPage.onActivityResult(requestCode, resultCode, data)
            }
            return
        }
        if (requestCode == SystemTrash.REQUEST) {
            android.util.Log.i("SpaceLens", "trash system result=" + resultCode)
            startScan()
            return
        }
        if (requestCode == REQ_SAF && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (t: Throwable) {
                    // 某些 ROM 不支持持久化，退化为本次会话有效
                }
                prefs.safUri = uri.toString()
                prefs.scanRoot = uri.lastPathSegment ?: uri.toString()
                updateMine()
                toast("已记录目录：" + (uri.lastPathSegment ?: uri.toString()) + "（SAF 扫描将在后续阶段接入）")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyBreakpoint()
        updateMine()
        updateOverview()
        if (::cleanPage.isInitialized) cleanPage.onResume()
        if (::transferPage.isInitialized) transferPage.onResume()
        if (::settingsPage.isInitialized) settingsPage.refresh()
        if (pendingScan) {
            if (hasAllFilesAccess() || hasReadAccess()) {
                pendingScan = false
                awaitingSettings = false
                startScan()
            } else if (awaitingSettings) {
                awaitingSettings = false
                pendingScan = false
                showFallback()
            }
        }
        checkRecvDirFallback()
        syncScanGate()
    }

    /** M2：单线程 DFS 扫描内部存储，边扫边更新树图。 */
    private fun startScan() {
        overviewPage.hideScanPermissionGate()
        if (snapshotLoading || saveThread?.isAlive == true) {
            pendingStartAfterIo = true
            toast("正在载入上次快照，稍后开始扫描")
            return
        }
        if (prefs.safUri != null && !hasAllFilesAccess()) {
            android.util.Log.i("SpaceLens", "SAF 目录扫描列为 v1.2 范围，当前未实现，拒绝启动扫描")
            toast("当前版本请先授予「所有文件访问」，指定目录扫描不在 v1.1 范围")
            return
        }
        if (scanner != null) {
            cancelScan()
            return
        }
        val root = Environment.getExternalStorageDirectory()
        if (!root.exists()) {
            toast("共享存储不可用")
            return
        }
        val gen = ++scanGeneration
        indexLoading = true
        store.reset()
        treePage.setScanning(store, "内部存储")
        treePage.setForbidden(emptyMap())
        overviewPage.setScanning(true)
        updateOverview()
        selectTab(0, animate = true)
        showTreeOverlay()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val s = Scanner(store, root, prefs.rateMode, object : Scanner.Listener {
            override fun onProgress(progress: Scanner.ScanProgress) {
                if (gen != scanGeneration || isFinishing || isDestroyed) return
                treePage.updateProgress(progress)
                try {
                    val t = store.categoryTotals()
                    overviewPage.updateScanProgress(longArrayOf(t[Classifier.IMAGE], t[Classifier.VIDEO],
                            t[Classifier.AUDIO], t[Classifier.DOC], t[Classifier.APK],
                            t[Classifier.ARCHIVE], t[Classifier.OTHER]), progress.bytes)
                } catch (_: Throwable) {
                }
            }

            override fun onFinished(summary: com.spacelens.app.core.ScanSummary) {
                if (gen != scanGeneration || isFinishing || isDestroyed) return
                scanner = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                indexLoading = false
                overviewPage.setScanning(false)
                treePage.finishScan(store, summary.rootName)
                refreshForbidden()
                prefs.lastSummary = summary
                android.util.Log.i("SpaceLens", "scan finished files=${summary.files} dirs=${summary.dirs} " +
                        "bytes=${summary.bytes} elapsedMs=${summary.elapsedMs} partial=${summary.partial} " +
                        "denied=${summary.denied} rate=${summary.rateMode}")
                updateOverview()
                updateMine()
                searchPage.rootPath = rootPath()
                searchPage.onIndexReady()
                cleanPage.rootPath = rootPath()
                cleanPage.reset()
                updateDetail()
                toast(
                        if (summary.partial) "已停止扫描：${com.spacelens.app.core.Fmt.count(summary.files.toLong())} 个文件，结果已保留"
                        else "扫描完成：${com.spacelens.app.core.Fmt.count(summary.files.toLong())} 个文件，耗时 ${com.spacelens.app.core.Fmt.duration(summary.elapsedMs)}"
                )
                saveSnapshot(summary.rootPath, summary.partial)
            }
        })
        scanner = s
        s.start()
    }

    private fun cancelScan() {
        val s = scanner ?: return
        s.cancel()
        toast("正在停止扫描…")
    }

    private fun saveSnapshot(rootPath: String, partial: Boolean) {
        val t = Thread({
            val begin = System.nanoTime()
            val meta = SnapshotStore.save(this, store, rootPath, partial)
            val ms = (System.nanoTime() - begin) / 1_000_000L
            android.util.Log.i("SpaceLens", "snapshot save nodes=${meta?.nodes ?: -1} ms=$ms path=$rootPath")
            runOnUiThread {
                saveThread = null
                if (pendingStartAfterIo) {
                    pendingStartAfterIo = false
                    startScan()
                }
            }
        }, "SpaceLens-Snapshot")
        saveThread = t
        t.start()
    }

    private fun loadSnapshot() {
        if (!SnapshotStore.fileFor(this).isFile) {
            indexLoading = false
            treePage.setEmpty("内部存储")
            return
        }
        snapshotLoading = true
        Thread({
            val begin = System.nanoTime()
            val meta = SnapshotStore.load(this, store)
            val ms = (System.nanoTime() - begin) / 1_000_000L
            android.util.Log.i("SpaceLens", "snapshot load nodes=${meta?.nodes ?: -1} ms=$ms ok=${meta != null}")
            runOnUiThread {
                snapshotLoading = false
                indexLoading = false
                searchPage.rootPath = rootPath()
                searchPage.onIndexReady()
                if (meta != null && store.count > 1) {
                    treePage.setTree(store, 0)
                    refreshForbidden()
                    updateOverview()
                    updateMine()
                    searchPage.rootPath = rootPath()
                    cleanPage.rootPath = rootPath()
                    cleanPage.reset()
                    updateDetail()
                } else {
                    treePage.setEmpty("内部存储")
                }
                if (pendingStartAfterIo) {
                    pendingStartAfterIo = false
                    startScan()
                }
            }
        }, "SpaceLens-SnapshotLoad").start()
    }

    private fun showNodeDetail(node: Int) {
        val s = treePage.store ?: return
        if (node !in 0 until s.count) return
        val decision = DeletePolicy.decide(this, store, node, rootPath())
        val abs = s.absolutePathOf(rootPath(), node)
        Dialogs.showNodeDetail(this, s.nameOf(node), treePage.nodeDescription(node, rootPath()),
                decision.selectable, decision.reason,
                onCopy = { copyPath(abs) },
                onOpen = { openNode(node) },
                onReveal = { revealNode(node) },
                onSelect = { enterSelection(node) })
    }

    private fun openNode(node: Int) {
        val s = store
        val abs = s.absolutePathOf(rootPath(), node)
        val f = File(abs)
        if (f.isDirectory) {
            revealNode(node)
            return
        }
        if (!f.isFile) {
            toast("文件不存在")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".files", f)
            val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(i, "用其他应用打开"))
        } catch (t: Throwable) {
            toast("没有可用的打开方式")
        }
    }

    private fun revealNode(node: Int) {
        val rel = store.relativePathOf(node)
        try {
            val docId = "primary:" + rel
            val uri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", docId)
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (t: Throwable) {
            toast("系统文件管理器不可用")
        }
    }

    private fun showAggregate(nodes: IntArray) {
        val s = treePage.store ?: return
        val sb = StringBuilder()
        var count = 0
        for (n in nodes) {
            if (n !in 0 until s.count) continue
            if (count >= 20) break
            sb.append(s.nameOf(n)).append("  ").append(Fmt.bytes(s.sizeOf(n))).append('\n')
            count++
        }
        if (nodes.size > 20) sb.append("… 共 ").append(nodes.size).append(" 项\n")
        Dialogs.showMessage(this, "其他 " + nodes.size + " 项", sb.toString().ifEmpty { "这些项目体积太小，已聚合显示。" })
    }

    // ---------------------------------------------------------------- 平板详情栏

    private fun rootPath(): String =
            prefs.safUri ?: Environment.getExternalStorageDirectory().absolutePath

    private fun updateDetail() {
        if (!::shell.isInitialized || !::detailPane.isInitialized) return
        if (shell.windowClass == WindowClass.COMPACT) return
        if (isTreeVisible() && store.count > 1 && detailNode in 0 until store.count) {
            detailPane.showNode(store, detailNode, rootPath())
        } else {
            detailPane.showStorage(store, prefs.lastSummary, BuildConfig.VERSION_NAME)
        }
    }

    private fun onTreeNodeLongPress(node: Int) {
        if (node !in 0 until store.count) return
        enterSelection(node)
        detailNode = node
        if (shell.windowClass != WindowClass.COMPACT) detailPane.showNode(store, node, rootPath())
    }

    private fun onDetailSelect(node: Int) {
        enterSelection(node)
    }

    private fun enterSelection(node: Int) {
        val d = DeletePolicy.decide(this, store, node, rootPath())
        if (!d.selectable) {
            toast(d.reason)
            return
        }
        selection.start(node)
        updateSelectionUi()
        if (d.extraConfirm) toast(d.reason)
    }

    private fun toggleSelection(node: Int) {
        val d = DeletePolicy.decide(this, store, node, rootPath())
        if (!d.selectable) {
            toast(d.reason)
            return
        }
        selection.toggle(node)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val active = selection.active && selection.count > 0
        val bytes = selection.totalBytes(store)
        treePage.setSelectionState(active, selection.count, bytes, selection.selected)
        val only = if (selection.count == 1) selection.selected.first() else -1
        treePage.setSelectedNode(only)
        if (!active) detailNode = -1
        updateDetail()
    }

    private fun cancelSelection() {
        selection.clear()
        treePage.setSelectionState(false, 0, 0, emptySet())
        treePage.setSelectedNode(-1)
        detailNode = -1
        updateDetail()
    }

    private fun showDeletePreview() {
        if (!selection.active || selection.count == 0) return
        val files = ArrayList<Triple<File, String, Int>>()
        var skipped = 0
        var bytes = 0L
        for (node in selection.selected) {
            val d = DeletePolicy.decide(this, store, node, rootPath())
            if (!d.selectable) {
                skipped++
                continue
            }
            val abs = store.absolutePathOf(rootPath(), node)
            val f = File(abs)
            if (!f.exists()) {
                skipped++
                continue
            }
            files.add(Triple(f, store.relativePathOf(node), node))
            bytes += store.sizeOf(node)
        }
        if (files.isEmpty()) {
            toast("没有可移入回收站的项目")
            return
        }
        val preview = files.take(5).joinToString("\n") { "· " + it.first.name }
        val more = if (files.size > 5) "\n还有 ${files.size - 5} 项" else ""
        val msg = "${selection.count} 项 · 释放 ${Fmt.bytes(bytes)}" +
                (if (preview.isNotEmpty()) "\n" + preview else "") + more +
                (if (skipped > 0) "\n跳过 $skipped 项（禁止选择或已不存在）" else "")
        Dialogs.showDeleteConfirm(this, "移入回收站", msg) {
            executeDelete(files)
        }
    }

    private fun executeDelete(files: List<Triple<File, String, Int>>) {
        if (SystemTrash.request(this, files.map { it.first })) {
            toast("请在系统弹窗确认移入系统回收站")
            return
        }
        android.util.Log.i("SpaceLens", "trash path=app count=${files.size}")
        val batch = Trash.batchId()
        deleteCancelled = false
        val totalBytes = files.sumOf { it.first.length() }.coerceAtLeast(1L)
        val progress = android.app.ProgressDialog(this).apply {
            setMessage("正在移入回收站…")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 1000
            setCancelable(true)
            setOnCancelListener { deleteCancelled = true }
            show()
        }
        Thread({
            val result = Trash.move(this, files.map { it.first to it.second }, batch,
                    onProgress = { done, total ->
                        runOnUiThread {
                            if (!isFinishing) progress.progress = (1000L * done / total.coerceAtLeast(1L)).toInt()
                        }
                    },
                    shouldStop = { deleteCancelled })
            val gone = files.filter { !it.first.exists() }.map { it.third }.toIntArray()
            runOnUiThread {
                try { progress.dismiss() } catch (_: Throwable) {
                }
                lastTrashBatch = if (result.moved > 0) batch else null
                if (gone.isNotEmpty()) {
                    store.markDeleted(gone)
                    treePage.refreshTree()
                    searchPage.refreshNow()
                    updateOverview()
                }
                cancelSelection()
                if (result.moved > 0) showUndoBar() else toast(result.message)
                updateMine()
                updateDetail()
            }
        }, "SpaceLens-Trash").start()
    }

    private fun showUndoBar() {
        pendingUndo = null
        showUndoLayer("已移入回收站 · 撤销")
    }

    /** 1.4.1：改名/移动的 5 秒撤销条，复用回收站那条。 */
    private fun showActionUndo(text: String, action: () -> Unit) {
        pendingUndo = action
        showUndoLayer(text)
    }

    /** 全局浮层：TabBar 顶边上方 16dp，动态计算。 */
    private fun showUndoLayer(text: String) {
        hideImeForUndo()
        undoHandler.removeCallbacksAndMessages(null)
        undoBar.text = text
        undoLayer.visibility = View.VISIBLE
        undoLayer.bringToFront()
        placeUndoLayer()
        root.post { placeUndoLayer() }
        val d = resources.displayMetrics.density
        undoBar.alpha = 0f
        undoBar.translationY = 12f * d
        undoBar.animate().alpha(1f).translationY(0f).setDuration(150L).start()
        undoHandler.postDelayed({ hideUndoLayer() }, 5000L)
    }

    private fun placeUndoLayer() {
        if (undoLayer.visibility != View.VISIBLE || root.height == 0) return
        val loc = IntArray(2)
        tabBar.getLocationInWindow(loc)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val tabTop = loc[1] - rootLoc[1]
        val d = resources.displayMetrics.density
        val lp = undoLayer.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = (root.height - tabTop + (16 * d).toInt()).coerceAtLeast((16 * d).toInt())
        undoLayer.layoutParams = lp
    }

    private fun hideUndoLayer() {
        undoHandler.removeCallbacksAndMessages(null)
        val d = resources.displayMetrics.density
        undoBar.animate().alpha(0f).translationY(12f * d).setDuration(120L)
                .withEndAction { undoLayer.visibility = View.GONE }.start()
    }

    private fun runPendingUndo() {
        val action = pendingUndo
        pendingUndo = null
        hideUndoLayer()
        if (action != null) action.invoke() else undoDelete()
    }

    /** 撤销条画在 Activity 底部，键盘弹着时会被盖住；弹条前先收键盘。 */
    private fun hideImeForUndo() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager ?: return
            val token = (currentFocus ?: root).windowToken
            imm.hideSoftInputFromWindow(token, 0)
        } catch (t: Throwable) {
            android.util.Log.e("FileLens", "hide ime failed", t)
        }
    }

    private fun undoDelete() {
        val batch = lastTrashBatch ?: return
        hideUndoLayer()
        Thread({
            var n = 0
            val restored = ArrayList<Trash.Item>()
            for (e in Trash.entries(this)) {
                if (e.batchId == batch && Trash.restore(this, e)) {
                    n++
                    restored.add(e)
                }
            }
            runOnUiThread {
                toast("已恢复 $n 项")
                lastTrashBatch = null
                // 只把节点补回本地快照并刷新当前页；不切 Tab、不开树图、不全盘重扫。
                for (e in restored) {
                    val idx = store.indexOfPath(rootPath(), e.originalPath)
                    if (idx > 0) {
                        val f = File(e.originalPath)
                        store.restoreNode(idx, f.length(), f.lastModified(), f.isDirectory)
                    }
                }
                treePage.refreshTree()
                searchPage.refreshNow()
                cleanPage.refreshDuplicates()
                updateOverview()
                updateDetail()
                updateMine()
            }
        }, "SpaceLens-Undo").start()
    }

    private fun showTrash() {
        val entries = Trash.entries(this)
        val bytes = entries.sumOf { it.size }
        val sys = SystemTrash.entries(this)
        if (entries.isEmpty() && sys.isEmpty()) {
            toast("回收站为空")
            return
        }
        Dialogs.showMessage(this, "回收站",
                "${entries.size} 项 · ${Fmt.bytes(bytes)}（应用回收站）" +
                        if (sys.isEmpty()) "" else "；系统回收站 ${sys.size} 项" + "\n保留 30 天，可还原或清空",
                positive = "还原全部",
                onPositive = {
                    Thread({
                        var n = 0
                        for (e in Trash.entries(this)) if (Trash.restore(this, e)) n++
                        runOnUiThread {
                            toast("已还原 $n 项")
                            updateMine()
                            if (n > 0) startScan()
                        }
                    }, "SpaceLens-Restore").start()
                },
                negative = "更多",
                onNegative = { showTrashMenu(entries, sys) },
        )
    }

    private fun showTrashMenu(entries: List<Trash.Item>, sys: List<SystemTrash.Entry>) {
        val actions = ArrayList<String>()
        if (entries.isNotEmpty()) {
            actions.add("逐项彻底删（应用回收站）")
            actions.add("清空应用回收站")
        }
        if (sys.isNotEmpty()) actions.add("还原系统回收站 ${sys.size} 项")
        if (actions.isEmpty()) return
        android.app.AlertDialog.Builder(this)
                .setTitle("回收站操作")
                .setItems(actions.toTypedArray()) { _, which ->
                    var index = which
                    if (entries.isNotEmpty()) {
                        if (index == 0) {
                            showTrashPurge(entries)
                            return@setItems
                        }
                        if (index == 1) {
                            Dialogs.showDeleteConfirm(this, "清空应用回收站",
                                    "将彻底删除 ${entries.size} 项，不可恢复。") {
                                Thread({
                                    val n = Trash.empty(this)
                                    runOnUiThread { toast("已清空 $n 项"); updateMine() }
                                }, "SpaceLens-TrashEmpty").start()
                            }
                            return@setItems
                        }
                        index -= 2
                    }
                    if (index == 0 && sys.isNotEmpty()) {
                        Dialogs.showDeleteConfirm(this, "还原系统回收站",
                                "将把 ${sys.size} 项移出系统回收站，恢复到原路径。") {
                            Thread({
                                var n = 0
                                for (e in sys) if (SystemTrash.restore(this, e.uri)) n++
                                runOnUiThread {
                                    toast("系统回收站已还原 $n 项")
                                    startScan()
                                }
                            }, "SpaceLens-SystemRestore").start()
                        }
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
    }
    private fun showTrashPurge(entries: List<Trash.Item>) {
        if (entries.isEmpty()) return
        val names = entries.mapIndexed { i, e ->
            "${i + 1}. " + File(e.originalPath).name + " · " + Fmt.bytes(e.size)
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
                .setTitle("逐项彻底删除")
                .setItems(names) { _, which ->
                    val e = entries.getOrNull(which) ?: return@setItems
                    Dialogs.showDeleteConfirm(this, "彻底删除",
                            "将彻底删除「" + File(e.originalPath).name + "」\n不可恢复，不进入回收站。") {
                        Trash.purge(this, e)
                        toast("已彻底删除")
                        updateMine()
                    }
                }
                .setNegativeButton("清空全部") { _, _ ->
                    Dialogs.showDeleteConfirm(this, "清空回收站",
                            "将彻底删除 ${entries.size} 项，不可恢复。") {
                        Thread({
                            val n = Trash.empty(this)
                            runOnUiThread { toast("已清空 $n 项"); updateMine() }
                        }, "SpaceLens-TrashEmpty").start()
                    }
                }
                .setPositiveButton("关闭", null)
                .show()
    }
    private fun showSearch() {
        if (store.count <= 1) {
            toast("先扫描后再搜索")
            return
        }
        hideTreeOverlay()
        selectTab(1, animate = true)
        searchPage.onShown()
    }

    private fun doSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        for (i in 1 until store.count) {
            if (store.nameOf(i).contains(q, ignoreCase = true)) {
                val target = if (store.flagsOf(i) and NodeStore.DIR != 0) i else store.parentOf(i)
                if (target >= 0) treePage.showRoot(target)
                toast("已定位：" + store.nameOf(i))
                return
            }
        }
        toast("没有匹配项")
    }
    private fun refreshForbidden() {
        if (store.count <= 1) {
            treePage.setForbidden(emptyMap())
            return
        }
        val snapshot = store
        Thread({
            val root = rootPath()
            val rootSize = snapshot.sizeOf(0).coerceAtLeast(1L)
            val set = HashMap<Int, String>()
            for (i in 1 until snapshot.count) {
                val d = DeletePolicy.decide(this, snapshot, i, root)
                if (!d.selectable) set[i] = d.reason
            }
            runOnUiThread { treePage.setForbidden(set) }
        }, "SpaceLens-Forbidden").start()
    }
    private fun showHealth() {
        if (store.count <= 1) {
            toast("先扫描后再清理")
            return
        }
        selectTab(2, animate = true)
        cleanPage.onShown()
    }

    private fun exportAll() {        if (store.count <= 1) {
            toast("先扫描后再导出")
            return
        }
        toast("正在导出 JSON / CSV / PNG")
        Thread({
            try {
                val out = Export.all(this, store, rootPath(), prefs.lastSummary)
                runOnUiThread {
                    toast("已导出到 exports 目录")
                    Export.open(this, out.png)
                }
            } catch (t: Throwable) {
                runOnUiThread { toast("导出失败：${t.message}") }
            }
        }, "SpaceLens-Export").start()
    }

    private fun startBench(durationSec: Int) {
        if (benchRunning) return
        val seconds = durationSec.coerceIn(5, 10)
        Dialogs.showMessage(this, "存储测速",
                "将跑满 $seconds 秒：前一半写、后一半读。\n" +
                        "测试文件只写入应用 cacheDir，结束立即清理；写入量受可用空间 30% 和 4GB 上限保护。\n\n" +
                        "测试期间请不要退出或锁屏，可随时取消。",
                positive = "开始测速", onPositive = { runBench(seconds) },
                negative = "取消", onNegative = {})
    }

    private fun runBench(durationSec: Int) {
        benchRunning = true
        benchPage.clearResult()
        benchPage.setRunning(true)
        BenchEngine.run(this, durationSec * 1000L, object : BenchEngine.Listener {
            override fun onProgress(p: BenchEngine.Progress) {
                runOnUiThread { benchPage.setProgress(p) }
            }

            override fun onFinished(r: BenchEngine.Result) {
                runOnUiThread {
                    benchRunning = false
                    benchPage.setResult(r)
                    toast("测速完成 · ${r.grade} · 最弱项 ${r.weakest}")
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    benchRunning = false
                    benchPage.setRunning(false)
                    toast("测速失败：$message")
                }
            }
        })
    }

    private fun copyPath(path: String) {
        if (path.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("SpaceLens 路径", path))
        toast("已复制路径")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && shell.isTablet) {
            when {
                event.keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                    if (selection.active) {
                        cancelSelection()
                    } else if (isTreeVisible() && treePage.goUp()) {
                        updateDetail()
                    }
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_A && event.isCtrlPressed -> {
                    if (isTreeVisible() && store.count > 1) {
                        selection.selectAllSiblings(store, treePage.rootIndex)
                        updateSelectionUi()
                    }
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> {
                    if (selection.active) showDeletePreview()
                    return true
                }
                event.keyCode == KeyEvent.KEYCODE_DEL -> {
                    if (isTreeVisible() && treePage.goUp()) updateDetail()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- 我的页

    private fun onMineAction(action: MinePage.Action) {
        when (action) {
            MinePage.Action.THEME -> {
                val next = prefs.themeMode.next()
                prefs.themeMode = next
                Theme.setMode(this, next)
                applyWindowTheme()
                repaintAll(root)
                updateMine()
                updateDetail()
            }
            MinePage.Action.RATE -> {
                val next = com.spacelens.app.core.RateMode.entries[
                        (prefs.rateMode.ordinal + 1) % com.spacelens.app.core.RateMode.entries.size]
                prefs.rateMode = next
                updateMine()
                toast("扫描速度：" + next.label + "（" + next.hint + "）")
            }
            MinePage.Action.PERMISSION -> {
                if (hasAnyAccess()) {
                    Dialogs.showMessage(this, "权限状态",
                            if (hasAllFilesAccess()) "已授予「所有文件访问」，可扫描完整内部存储。"
                            else if (prefs.safUri != null) "已选择指定目录，但当前版本只支持「所有文件访问」全盘扫描。"
                            else "已授予读取权限。")
                } else {
                    requestScan()
                }
            }
            MinePage.Action.SCOPE -> {
                Dialogs.showMessage(
                        this,
                        "扫描范围",
                        if (hasAllFilesAccess()) "当前：内部存储全量\n\n也可以改为只扫描一个指定目录。"
                        else "当前：未授权完整存储。\n\n授权后可扫描内部存储，或先选择一个指定目录。",
                        positive = "选择目录",
                        onPositive = { pickDirectory() },
                        negative = if (Build.VERSION.SDK_INT >= 30) "去设置" else null,
                        onNegative = { openAllFilesSettings() },
                )
            }
            MinePage.Action.TRASH -> showTrash()
            MinePage.Action.CLEAR_CACHE -> {
                if (scanner != null || snapshotLoading || saveThread?.isAlive == true) {
                    toast("请先停止扫描并等待快照保存完成")
                } else {
                    val size = SnapshotStore.delete(this)
                    prefs.lastSummary = null
                    store.reset()
                    treePage.setEmpty("内部存储")
                    updateOverview()
                    updateMine()
                    updateDetail()
                    toast("已清理快照与扫描摘要（" + Fmt.bytes(size) + "）")
                }
            }
            MinePage.Action.EXPORT -> exportAll()
            MinePage.Action.VERSION -> Dialogs.showVersion(this)
            MinePage.Action.ABOUT -> Dialogs.showAbout(this)
        }
    }

    private fun updateMine() {
        val permission = when {
            hasAllFilesAccess() -> "所有文件访问"
            prefs.safUri != null -> "指定目录"
            Build.VERSION.SDK_INT < 30 && hasReadAccess() -> "读取已授权"
            else -> "未授权"
        }
        val scope = if (prefs.safUri != null) "指定目录" else "内部存储"
        val snapshot = File(filesDir, "scan-latest.bin")
        val cache = if (snapshot.exists()) Fmt.bytes(snapshot.length()) else "0 B"
        minePage.setState(MinePage.State(
                theme = prefs.themeMode.label,
                rate = prefs.rateMode.label,
                permission = permission,
                scope = scope,
                cache = cache,
                trash = run {
                    val e = Trash.entries(this)
                    e.size.toString() + " 项 · " + Fmt.bytes(e.sumOf { it.size })
                },
                version = BuildConfig.VERSION_NAME,
        ))
    }

    private fun updateOverview() {
        overviewPage.refreshStorage()
        val hasTree = store.count > 1
        val categories = if (hasTree) {
            val t = store.categoryTotals()
            longArrayOf(t[Classifier.IMAGE], t[Classifier.VIDEO], t[Classifier.AUDIO],
                    t[Classifier.DOC], t[Classifier.APK], t[Classifier.ARCHIVE], t[Classifier.OTHER])
        } else {
            null
        }
        overviewPage.setSummary(prefs.lastSummary, categories, if (hasTree) store.sizeOf(0) else 0L)
    }

    private fun toggleFpsHud() {
        val show = !fpsHud.isShowing()
        fpsHud.setShowing(show)
        prefs.fpsHud = show
        toast(if (show) "FPS HUD 已开启" else "FPS HUD 已关闭")
    }

    private fun currentRefreshRate(): Float {
        return try {
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            d?.refreshRate ?: 60f
        } catch (_: Throwable) {
            60f
        }
    }

    private fun requestHighRefresh() {
        var chosen = 0f
        try {
            val d = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            val modes = d?.supportedModes
            val best = modes?.maxByOrNull { it.refreshRate }
            if (best != null && best.refreshRate >= 90f) {
                val attrs = window.attributes
                attrs.preferredDisplayModeId = best.modeId
                window.attributes = attrs
                chosen = best.refreshRate
            }
        } catch (t: Throwable) {
            // 部分 ROM 不提供模式列表，继续尝试 setFrameRate
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.post {
                try {
                    // Window.setFrameRate 在 Android 15/16 才有；compileSdk 34 用反射兼容。
                    val m = window.javaClass.getMethod("setFrameRate",
                            Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    m.invoke(window, 120f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                } catch (_: Throwable) {
                }
            }
        }
        android.util.Log.i("SpaceLens", "highRefresh requested modeHz=$chosen")
    }

    // ---------------------------------------------------------------- 主题与窗口

    private fun applyWindowTheme() {
        val p = Theme.palette
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 26) {
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            window.navigationBarColor = p.bg
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (!p.dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (!p.dark && Build.VERSION.SDK_INT >= 26) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.setBackgroundDrawable(ColorDrawable(p.bg))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.i("SpaceLens", "config width=${newConfig.screenWidthDp}dp density=${newConfig.densityDpi} " +
                "activity=${hashCode()} recreate=false")
        if (Theme.mode == ThemeMode.SYSTEM) {
            Theme.systemChanged(this)
            applyWindowTheme()
            updateMine()
            updateOverview()
        }
        applyBreakpoint()
        root.requestApplyInsets()
        repaintAll(root)
    }

    private fun repaintAll(view: View) {
        view.invalidate()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) repaintAll(view.getChildAt(i))
        }
    }

    override fun onBackPressed() {
        if (selection.active) {
            cancelSelection()
            return
        }
        if (isBenchVisible()) {
            hideBenchOverlay()
            return
        }
        if (isTreeVisible()) {
            if (treePage.goUp()) {
                updateDetail()
            } else {
                hideTreeOverlay()
                updateDetail()
            }
            return
        }
        if (pager.currentPage == 4) {
            selectTab(if (previousTab in 0..3) previousTab else 0, animate = true)
            return
        }
        if (pager.currentPage == 3 && ::transferPage.isInitialized) {
            if (transferPage.handleBack()) return
            val target = if (previousTab in 0..2) previousTab else 0
            selectTab(target, animate = true)
            return
        }
        if (pager.currentPage != 0) {
            selectTab(0, animate = true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        BenchEngine.cancel()
        scanner?.cancel()
        scanner = null
        saveThread?.interrupt()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        com.spacelens.app.ui.Motion.clear()
        super.onDestroy()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_READ = 1001
        private const val REQ_SAF = 1002
        private const val REQ_ALL_FILES = 1003
        private const val REQ_CAMERA = 1004
    }
}
