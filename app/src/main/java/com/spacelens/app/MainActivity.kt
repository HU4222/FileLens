package com.spacelens.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import com.spacelens.app.core.NodeStore
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
import com.spacelens.app.ui.DetailPane
import com.spacelens.app.ui.Dialogs
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
    private var lastTrashBatch: String? = null
    @Volatile private var deleteCancelled = false
    private val undoHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
    private var benchRunning = false

    private var pendingScan = false
    private var awaitingSettings = false
    private var visibleTab = -1
    private var previousTab = 0

    private val store = NodeStore()
    private var scanner: Scanner? = null
    private var scanGeneration = 0
    private var snapshotLoading = false
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
        cleanPage = CleanPage(this)
        settingsPage = SettingsPage(this)
        settingsPage.onTrash = { showTrashPage() }
        settingsPage.onClose = { selectTab(if (previousTab in 0..3) previousTab else 0, true) }
        settingsPage.onRequestCamera = {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        transferPage = TransferPage(this)
        transferPage.onPanelVisibility = { open -> tabBar.isEnabled = !open }
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
            visibility = View.GONE
            setOnClickListener { undoDelete() }
        }
        val undoLp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        undoLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        undoLp.bottomMargin = (88 * resources.displayMetrics.density).toInt()
        root.addView(undoBar, undoLp)

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
        overviewPage.onOpenTree = { showTreeOverlay() }
        overviewPage.onHealth = {
            selectTab(2, animate = true)
            cleanPage.onShown()
        }
        overviewPage.onBench = { showBenchOverlay() }
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
        cleanPage.store = store
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
        dialog.setContentView(page)
        dialog.show()
    }


    private fun showPreview(paths: List<String>, start: Int) {
        if (paths.isEmpty()) return
        val dialog = android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val page = PreviewPage(this, paths, start)
        page.onClose = { dialog.dismiss() }
        dialog.setContentView(page)
        dialog.show()
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

    private fun showTreeOverlay() {
        if (!::treeOverlay.isInitialized) return
        tabBar.visibility = View.GONE
        treePage.setWindowClass()
        treeOverlay.visibility = View.VISIBLE
        treeOverlay.bringToFront()
        updateDetail()
    }

    private fun hideTreeOverlay() {
        if (::treeOverlay.isInitialized) treeOverlay.visibility = View.GONE
        if (::tabBar.isInitialized && shell.windowClass == WindowClass.COMPACT) {
            tabBar.visibility = View.VISIBLE
        }
    }

    private fun isBenchVisible(): Boolean =
            ::benchOverlay.isInitialized && benchOverlay.visibility == View.VISIBLE

    private fun showBenchOverlay() {
        if (!::benchOverlay.isInitialized) return
        tabBar.visibility = View.GONE
        benchOverlay.visibility = View.VISIBLE
        benchOverlay.bringToFront()
        benchOverlay.requestLayout()
        benchPage.requestLayout()
        benchPage.invalidate()
    }

    private fun hideBenchOverlay() {
        if (::benchOverlay.isInitialized) benchOverlay.visibility = View.GONE
        if (::tabBar.isInitialized && shell.windowClass == WindowClass.COMPACT) {
            tabBar.visibility = View.VISIBLE
        }
    }

    private fun showTreeAt(node: Int) {
        val target = if (node in 0 until store.count
                && store.flagsOf(node) and NodeStore.DIR != 0) node else store.parentOf(node)
        if (target >= 0) treePage.showRoot(target)
        showTreeOverlay()
    }

    private fun deleteSingle(node: Int) {
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
            Thread({
                val result = Trash.move(this, listOf(File(abs) to rel))
                runOnUiThread {
                    if (result.moved > 0) {
                        store.markDeleted(intArrayOf(node))
                        treePage.refreshTree()
                        searchPage.refresh()
                        cleanPage.reset()
                        updateOverview()
                        updateDetail()
                        toast("已移入回收站")
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
                    updateOverview()
                }
                cancelSelection()
                toast(result.message)
                if (result.moved > 0) showUndoBar()
                updateMine()
                updateDetail()
            }
        }, "SpaceLens-Trash").start()
    }

    private fun showUndoBar() {
        undoHandler.removeCallbacksAndMessages(null)
        undoBar.text = "已移入回收站 · 撤销"
        undoBar.visibility = View.VISIBLE
        undoHandler.postDelayed({ undoBar.visibility = View.GONE }, 5000L)
    }

    private fun undoDelete() {
        val batch = lastTrashBatch ?: return
        undoBar.visibility = View.GONE
        Thread({
            var n = 0
            for (e in Trash.entries(this)) {
                if (e.batchId == batch && Trash.restore(this, e)) n++
            }
            runOnUiThread {
                toast("已撤销，还原 $n 项")
                lastTrashBatch = null
                updateMine()
                if (n > 0) startScan()
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
