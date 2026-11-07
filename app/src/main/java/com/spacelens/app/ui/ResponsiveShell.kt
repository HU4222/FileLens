package com.spacelens.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min

/**
 * 断点布局容器（8.5）：
 * - <600dp：顶栏 + 页面 + 底部 TabBar；
 * - 600~839dp：88dp NavRail + 主内容；树图页加 280dp 详情栏；
 * - >=840dp：NavRail + 主内容 + 320dp 详情/统计栏；主内容最大 1100dp 居中。
 * 只重算布局，不重建 Activity。
 */
class ResponsiveShell(context: Context) : ViewGroup(context) {

    private lateinit var navRail: NavRail
    private lateinit var topBar: TopBar
    private lateinit var pager: SwipePager
    private lateinit var tabBar: TabBar
    private lateinit var detailPane: DetailPane

    var windowClass: WindowClass = WindowClass.COMPACT
        private set
    var tab: Int = 0
        private set

    val isTablet: Boolean get() = windowClass != WindowClass.COMPACT

    fun bind(nav: NavRail, top: TopBar, pager: SwipePager, tabs: TabBar, detail: DetailPane) {
        navRail = nav
        topBar = top
        this.pager = pager
        tabBar = tabs
        detailPane = detail
        addView(nav)
        addView(detail)
        addView(top)
        addView(pager)
        addView(tabs)
    }

    fun setWindowClass(cls: WindowClass, tab: Int) {
        val changed = windowClass != cls || this.tab != tab
        windowClass = cls
        this.tab = tab
        navRail.visibility = if (cls != WindowClass.COMPACT) View.VISIBLE else View.GONE
        tabBar.visibility = if (cls == WindowClass.COMPACT) View.VISIBLE else View.GONE
        val showDetail = Breakpoints.showDetail(widthDp(), tab)
        detailPane.visibility = if (showDetail) View.VISIBLE else View.GONE
        if (changed) requestLayout()
    }

    fun setTab(tab: Int) {
        if (this.tab == tab) return
        this.tab = tab
        val showDetail = Breakpoints.showDetail(widthDp(), tab)
        detailPane.visibility = if (showDetail) View.VISIBLE else View.GONE
        requestLayout()
    }

    private fun widthDp(): Int = resources.configuration.screenWidthDp

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val availableW = max(0, w - paddingLeft - paddingRight)
        val availableH = max(0, h - paddingTop - paddingBottom)
        val metrics = Breakpoints.metrics(widthDp())
        val navW = if (windowClass == WindowClass.COMPACT) 0 else dp(metrics.navWidthDp)
        val showDetail = Breakpoints.showDetail(widthDp(), tab)
        val detailW = if (showDetail) dp(metrics.detailWidthDp) else 0
        val mainAreaW = max(0, availableW - navW - detailW)
        val mainW = if (metrics.maxContentWidthDp > 0f) min(mainAreaW, dp(metrics.maxContentWidthDp)) else mainAreaW
        val mainLeft = paddingLeft + navW + max(0, (mainAreaW - mainW) / 2)

        if (navRail.visibility == View.VISIBLE) {
            navRail.measure(
                    MeasureSpec.makeMeasureSpec(navW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(availableH, MeasureSpec.EXACTLY),
            )
        }
        if (detailPane.visibility == View.VISIBLE) {
            detailPane.measure(
                    MeasureSpec.makeMeasureSpec(detailW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(availableH, MeasureSpec.EXACTLY),
            )
        }
        topBar.measure(
                MeasureSpec.makeMeasureSpec(mainW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(availableH, MeasureSpec.AT_MOST),
        )
        val topH = topBar.measuredHeight
        var tabH = 0
        if (tabBar.visibility == View.VISIBLE) {
            tabBar.measure(
                    MeasureSpec.makeMeasureSpec(mainW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(availableH, MeasureSpec.AT_MOST),
            )
            tabH = tabBar.measuredHeight
        }
        // 胶囊底栏悬浮在内容之上，pager 不再扣掉 tabH。
        val pagerH = max(0, availableH - topH)
        pager.measure(
                MeasureSpec.makeMeasureSpec(mainW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(pagerH, MeasureSpec.EXACTLY),
        )

        // 记下布局参数供 onLayout 使用
        layoutNavW = navW
        layoutDetailW = detailW
        layoutMainLeft = mainLeft
        layoutMainW = mainW
        layoutTopH = topH
        layoutTabH = tabH
        layoutPagerH = pagerH
        layoutAvailableH = availableH
    }

    private var layoutNavW = 0
    private var layoutDetailW = 0
    private var layoutMainLeft = 0
    private var layoutMainW = 0
    private var layoutTopH = 0
    private var layoutTabH = 0
    private var layoutPagerH = 0
    private var layoutAvailableH = 0

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val top = paddingTop
        if (navRail.visibility == View.VISIBLE) {
            navRail.layout(paddingLeft, top, paddingLeft + layoutNavW, top + layoutAvailableH)
        }
        if (detailPane.visibility == View.VISIBLE) {
            val left = r - paddingRight - layoutDetailW
            detailPane.layout(left, top, left + layoutDetailW, top + layoutAvailableH)
        }
        topBar.layout(layoutMainLeft, top, layoutMainLeft + layoutMainW, top + layoutTopH)
        pager.layout(layoutMainLeft, top + layoutTopH,
                layoutMainLeft + layoutMainW, top + layoutTopH + layoutPagerH)
        if (tabBar.visibility == View.VISIBLE) {
            tabBar.layout(layoutMainLeft, top + layoutAvailableH - layoutTabH,
                    layoutMainLeft + layoutMainW, top + layoutAvailableH)
        }
    }
}
