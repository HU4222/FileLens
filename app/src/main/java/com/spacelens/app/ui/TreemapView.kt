package com.spacelens.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.spacelens.app.core.Classifier
import com.spacelens.app.core.Fmt
import com.spacelens.app.core.NodeStore
import com.spacelens.app.core.Squarify
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.min

/**
 * 自绘矩形树图（PRD 5.3 / 5.4 / 8.5）。
 * 手机只布局当前层；>=840dp（maxDepth=2）在每个大块内多画一层子块。
 * 面积 < 0.4% 聚合；同屏最多 1200（手机）/ 1800（平板）块。
 */
class TreemapView(context: Context) : View(context) {

    var onDrill: ((Int) -> Unit)? = null
    var onNodeTap: ((Int) -> Unit)? = null
    var onLongPressNode: ((Int) -> Unit)? = null
    var onAggregateTap: ((IntArray) -> Unit)? = null
    var onSelectionToggle: ((Int) -> Unit)? = null

    private var store: NodeStore? = null
    private var rootIndex: Int = 0
    private var emptyHint: String = "还没有空间树"
    private var maxDepth = 1
    private var maxBlocks = 1200
    private var selectedNode = -1
    private var selectionActive = false
    private var selectedSet: Set<Int> = emptySet()
    private var hoveredNode = -1

    private val blocks = ArrayList<Block>()
    private val panelRect = RectF()
    private val clipPath = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Draw.SANS_MEDIUM
        fontFeatureSettings = "tnum"
    }
    private val textSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Draw.SANS
        fontFeatureSettings = "tnum"
    }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val gradientMatrix = Matrix()
    private val sweepMatrix = Matrix()
    private val sweepClip = Path()
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepShader: LinearGradient by lazy {
        LinearGradient(0f, 0f, 1f, 0f,
                intArrayOf(0x00FFFFFF, 0x18FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    private var sweepDrawn = 0
    private val animMap = HashMap<Int, AnimRect>()
    private val ghosts = ArrayList<Ghost>()
    private val pendingGhosts = ArrayList<Ghost>()
    private var animEngine: Motion.Animation? = null
    private var fastMode = false
    private var scanning = false
    private var lastScanItems = 0
    private var lastScanTime = 0L
    private var scanRate = 0f
    private var newAnimated = 0
    private var drawCount = 0
    private var forbidden = emptyMap<Int, String>()
    private val ripples = ArrayList<Ripple>()
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val tmpRect = RectF()
    private val ghostRect = RectF()

    private var dirty = true
    private var downX = 0f
    private var downY = 0f
    private var downBlock: Block? = null
    private var longPressDone = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        val b = downBlock
        if (b != null && !b.aggregate) {
            longPressDone = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onLongPressNode?.invoke(b.node)
        }
    }

    init {
        isClickable = true
    }

    fun setLayoutMode(maxDepth: Int, maxBlocks: Int) {
        val changed = this.maxDepth != maxDepth || this.maxBlocks != maxBlocks
        this.maxDepth = maxDepth.coerceIn(1, 2)
        this.maxBlocks = maxBlocks.coerceAtLeast(100)
        if (changed) {
            dirty = true
            invalidate()
        }
    }

    fun setTree(store: NodeStore, root: Int) {
        this.store = store
        this.rootIndex = root
        ghosts.clear()
        pendingGhosts.clear()
        dirty = true
        invalidate()
    }

    fun setForbidden(nodes: Map<Int, String>) {
        forbidden = nodes
        invalidate()
    }

    fun setScanning(scanning: Boolean) {
        this.scanning = scanning
        if (!scanning) scanRate = 0f
    }

    fun onScanProgress(items: Int) {
        val now = SystemClock.uptimeMillis()
        if (lastScanTime > 0L && now > lastScanTime) {
            val dt = (now - lastScanTime) / 1000f
            val delta = items - lastScanItems
            if (dt > 0.02f) scanRate = (delta / dt).coerceAtLeast(0f)
        }
        lastScanTime = now
        lastScanItems = items
    }

    fun setRoot(root: Int) {
        captureGhosts()
        rootIndex = root
        animMap.clear()
        dirty = true
        invalidate()
    }

    fun setEmpty(hint: String) {
        store = null
        emptyHint = hint
        blocks.clear()
        ghosts.clear()
        pendingGhosts.clear()
        animMap.clear()
        animEngine?.cancel()
        animEngine = null
        dirty = true
        invalidate()
    }

    fun setEmptyHint(hint: String) {
        emptyHint = hint
        invalidate()
    }

    fun setSelectedNode(node: Int) {
        selectedNode = node
        invalidate()
    }

    fun setSelection(indices: Set<Int>, active: Boolean) {
        selectedSet = indices.toSet()
        selectionActive = active
        invalidate()
    }

    fun hitNodeAt(x: Float, y: Float): Int = blockAt(x, y)?.node ?: -1

    fun refresh() {
        dirty = true
        postInvalidateOnAnimation()
    }

    fun currentRoot(): Int = rootIndex

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dirty = true
    }

    override fun onDraw(canvas: Canvas) {
        val drawStart = System.nanoTime()
        val p = Theme.palette
        val d = resources.displayMetrics.density
        panelRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(panelRect, 18f * d, 18f * d, Draw.fill(p.bg))
        clipPath.reset()
        clipPath.addRoundRect(panelRect, 18f * d, 18f * d, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val s = store
        if (s == null || s.count <= 1) {
            drawEmpty(canvas, p, d)
            canvas.restore()
            return
        }
        if (dirty) {
            rebuild(s)
            dirty = false
        }
        if (blocks.isEmpty()) {
            drawEmpty(canvas, p, d)
            canvas.restore()
            return
        }
        sweepDrawn = 0
        drawGhosts(canvas, p, d)
        for (b in blocks) drawBlock(canvas, b, p, d)
        drawRipples(canvas, p, d)
        canvas.restore()
        drawCount++
        if (drawCount % 60 == 0 && blocks.isNotEmpty()) {
            val ms = (System.nanoTime() - drawStart) / 1_000_000.0
            android.util.Log.i("SpaceLens", "treemap draw=%.2fms blocks=%d ghosts=%d fast=%s".format(ms, blocks.size, ghosts.size, fastMode))
        }
    }

    private fun drawEmpty(canvas: Canvas, p: Palette, d: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val icon = 46f * d
        canvas.drawCircle(cx, cy - 34f * d, 34f * d, Draw.fill(p.panelSunken))
        Draw.icon(canvas, Icon.TREE, cx, cy - 34f * d, icon * 0.62f, p.muted, 2f * d)
        val title = Draw.text(context, 15f, p.text2, medium = true, align = Paint.Align.CENTER)
        canvas.drawText(emptyHint, cx, cy + 18f * d, title)
        val sub = Draw.text(context, 10.5f, p.muted, align = Paint.Align.CENTER)
        canvas.drawText("扫描后，每个方块就是一块真实空间", cx, cy + 38f * d, sub)
    }

    private fun rebuild(s: NodeStore) {
        val hadFast = blocks.size > 1000
        blocks.clear()
        newAnimated = 0
        fastMode = hadFast || (scanning && scanRate > 500f)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 9f * d()
        if (w <= pad * 2 || h <= pad * 2) return
        val bounds = RectF(pad, pad, w - pad, h - pad)
        val rootSize = max(s.sizeOf(rootIndex), 1L)
        val children = s.childrenInfo(rootIndex)
        if (children.isEmpty()) return
        if (children.size > 1000) fastMode = true

        val topLimit = if (maxDepth >= 2) min(maxBlocks / 2, 600) else maxBlocks - 1
        val threshold = rootSize * 0.004
        val visible = ArrayList<NodeStore.NodeInfo>(min(children.size, topLimit))
        val hidden = ArrayList<Int>()
        var hiddenSize = 0L
        for (c in children) {
            if (c.size <= 0L) {
                hidden.add(c.index)
                continue
            }
            if (c.size >= threshold && visible.size < topLimit) visible.add(c) else {
                hidden.add(c.index)
                hiddenSize += c.size
            }
        }
        visible.sortByDescending { it.size }

        val items = ArrayList<LayoutItem>(visible.size + 1)
        for (c in visible) items.add(LayoutItem(c.size.toDouble(), c, null))
        if (hidden.isNotEmpty()) {
            items.add(LayoutItem(max(hiddenSize.toDouble(), rootSize * 0.0008), null, hidden.toIntArray()))
        }
        items.sortByDescending { it.size }
        val areas = DoubleArray(items.size) { items[it].size }
        val rects = Squarify.layoutAreas(areas, bounds)
        for (i in items.indices) {
            val r = rects.getOrNull(i) ?: continue
            if (r.isEmpty) continue
            val it = items[i]
            val target = RectF(r)
            val key = it.info?.index ?: -1
            val anim = animFor(key, target, it.info?.depth ?: 1)
            blocks.add(Block(
                    node = it.info?.index ?: -1,
                    rect = target,
                    anim = anim,
                    aggregate = it.aggregate != null,
                    aggregateNodes = it.aggregate ?: IntArray(0),
                    name = it.info?.name ?: "其他 " + (it.aggregate?.size ?: 0) + " 项",
                    size = it.info?.size ?: hiddenSize,
                    category = it.info?.category ?: Classifier.OTHER,
                    flags = it.info?.flags ?: 0,
                    nodeDepth = it.info?.depth ?: 0,
                    childCount = it.info?.children ?: 0,
                    level = 0,
            ))
        }

        if (maxDepth >= 2) {
            expandNested(s)
        }
        ensureEngine()
    }

    /** 平板：对面积够大的目录再画一层子块，总数不超过 maxBlocks。 */
    private fun expandNested(s: NodeStore) {
        val d = d()
        val budget = maxBlocks - blocks.size
        if (budget <= 0) return
        val parents = blocks.filter {
            !it.aggregate && it.node >= 1 && it.childCount > 0 &&
                    it.rect.width() * it.rect.height() > 5000f * d * d
        }.sortedByDescending { it.rect.width() * it.rect.height() }.take(32)

        var used = 0
        for (b in parents) {
            if (used >= budget) break
            val kids = s.childrenInfo(b.node)
            if (kids.isEmpty()) continue
            val parentSize = max(b.size, 1L)
            val minChild = max(parentSize / 100L, 1L)
            val pick = kids.filter { it.size >= minChild && it.size > 0 }
                    .sortedByDescending { it.size }
                    .take(min(16, budget - used))
            if (pick.isEmpty()) continue
            val inner = RectF(b.rect)
            inner.inset(3f * d, 3f * d)
            val header = if (inner.height() > 52f * d) 18f * d else 4f * d
            inner.top += header
            if (inner.width() < 8f * d || inner.height() < 8f * d) continue
            val areas = DoubleArray(pick.size) { pick[it].size.toDouble() }
            val rects = Squarify.layoutAreas(areas, inner)
            var added = 0
            for (i in pick.indices) {
                val r = rects.getOrNull(i) ?: continue
                if (r.isEmpty || r.width() < 2f || r.height() < 2f) continue
                val info = pick[i]
                val target = RectF(r)
                val anim = animFor(info.index, target, info.depth)
                blocks.add(Block(
                        node = info.index,
                        rect = target,
                        anim = anim,
                        aggregate = false,
                        aggregateNodes = IntArray(0),
                        name = info.name,
                        size = info.size,
                        category = info.category,
                        flags = info.flags,
                        nodeDepth = info.depth,
                        childCount = info.children,
                        level = 1,
                ))
                added++
                used++
                if (used >= budget) break
            }
            if (added > 0) b.hasNested = true
        }
        val newNodes = blocks.mapTo(HashSet()) { it.node }
        for (g in pendingGhosts) {
            if (g.node !in newNodes) ghosts.add(g)
        }
        pendingGhosts.clear()
        while (ghosts.size > 160) ghosts.removeAt(0)
        ensureEngine()
    }

    private fun animFor(key: Int, target: RectF, depth: Int): AnimRect {
        var anim = animMap[key]
        if (anim == null) {
            anim = AnimRect()
            val delay = min(90, depth * 22).toFloat()
            if (newAnimated < 30) {
                anim.startAtCenter(target, delay)
                newAnimated++
            } else {
                anim.startAtCenter(target, 0f)
                anim.snapToTarget(keepAlpha = true)
            }
            animMap[key] = anim
        } else {
            anim.retarget(target)
        }
        if (fastMode) anim.snapToTarget()
        return anim
    }

    private fun d(): Float = resources.displayMetrics.density

    private fun drawBlock(canvas: Canvas, b: Block, p: Palette, d: Float) {
        if (b.anim.alpha < 0.01f) return
        val r = tmpRect
        b.anim.current(r)
        if (r.isEmpty) return
        r.inset(2.2f * d, 2.2f * d)
        if (r.width() < 1f || r.height() < 1f) return

        val area = r.width() * r.height()
        val viewArea = max(width.toFloat() * height.toFloat(), 1f)
        val ratio = area / viewArea
        val base = if (b.aggregate) p.muted else p.category[b.category.coerceIn(0, 6)]
        val dim = selectionActive && b.node >= 0 && !selectedSet.contains(b.node)
        val chosen = b.node >= 0 && (b.node == selectedNode || (selectionActive && selectedSet.contains(b.node)))
        val radius = min(if (r.width() > 26f * d && r.height() > 26f * d) 7f * d else 3f * d,
                min(r.width(), r.height()) / 2f)

        if (chosen) {
            glowPaint.color = Theme.alpha(p.brand, 102)
            glowPaint.strokeWidth = 24f * d
            val gr = RectF(r)
            gr.inset(-12f * d, -12f * d)
            canvas.drawRoundRect(gr, radius + 12f * d, radius + 12f * d, glowPaint)
        }

        canvas.save()
        if (chosen) canvas.scale(1.02f, 1.02f, r.centerX(), r.centerY())

        fill.shader = null
        val fillAlpha = (when {
            b.aggregate -> 0
            b.hasNested -> 19
            else -> 62
        } + if (chosen) 15 else 0)
        fill.color = if (b.aggregate) p.panelSunken
        else {
            val tint = Draw.desaturate(base, 0.63f)
            Theme.alpha(Theme.blend(tint, p.bg, 0.05f), if (dim) (fillAlpha * 0.55f).toInt() else fillAlpha)
        }
        canvas.drawRoundRect(r, radius, radius, fill)

        stroke.color = Theme.alpha(base, if (dim) 70 else 140)
        stroke.strokeWidth = 1f * d
        canvas.drawRoundRect(r, radius, radius, stroke)

        if (r.height() > 10f * d && r.width() > 16f * d) {
            highlightPaint.color = Color.argb((41 * b.anim.alpha).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawLine(r.left + radius, r.top + 0.5f * d, r.right - radius, r.top + 0.5f * d, highlightPaint)
        }

        if (b.flags and NodeStore.CLUSTER != 0) drawCluster(canvas, r, radius, p, d)
        if (b.flags and NodeStore.DENIED != 0) drawDenied(canvas, r, radius, p, d)

        if (b.hasNested) {
            if (chosen) drawCheck(canvas, r, p, d)
            canvas.restore()
            return
        }

        val textAlpha = if (dim) 130 else 235
        if (b.aggregate) {
            if (ratio > 0.02f) {
                text.color = Theme.alpha(p.text2, textAlpha)
                text.textSize = 12f * resources.displayMetrics.scaledDensity
                text.typeface = Draw.SANS_MEDIUM
                canvas.drawText(Draw.ellipsize(b.name, text, r.width() - 12f * d),
                        r.left + 6f * d, r.bottom - 8f * d, text)
            }
        } else if (ratio > 0.08f) {
            text.color = Theme.alpha(0xFFFFFFFF.toInt(), (textAlpha * b.anim.alpha).toInt().coerceIn(0, 255))
            text.textSize = 12f * resources.displayMetrics.scaledDensity
            text.typeface = Draw.SANS_MEDIUM
            val name = Draw.ellipsize(b.name, text, r.width() - 12f * d)
            canvas.drawText(name, r.left + 6f * d, r.bottom - 24f * d, text)
            if (r.height() > 38f * d) {
                textSub.color = Theme.alpha(0xFFFFFFFF.toInt(),
                        ((if (dim) 90 else 158) * b.anim.alpha).toInt().coerceIn(0, 255))
                textSub.textSize = 11f * resources.displayMetrics.scaledDensity
                textSub.typeface = Draw.SANS_MEDIUM
                canvas.drawText(Fmt.bytes(b.size), r.left + 6f * d, r.bottom - 8f * d, textSub)
            }
        } else if (ratio > 0.02f) {
            text.color = Theme.alpha(0xFFFFFFFF.toInt(), (textAlpha * b.anim.alpha).toInt().coerceIn(0, 255))
            text.textSize = 12f * resources.displayMetrics.scaledDensity
            text.typeface = Draw.SANS_MEDIUM
            canvas.drawText(Draw.ellipsize(b.name, text, r.width() - 12f * d),
                    r.left + 6f * d, r.bottom - 8f * d, text)
        }
        if (forbidden.containsKey(b.node)) {
            canvas.drawRoundRect(r, radius, radius, Draw.fill(Theme.alpha(p.bg, 170)))
            val lp = Draw.text(context, 8f, p.muted, align = Paint.Align.CENTER)
            val reason = forbidden[b.node] ?: "禁选"
            canvas.drawText(Draw.ellipsize(reason, lp, r.width() - 8f * d),
                    r.centerX(), r.centerY() + 3f * d, lp)
        }
        if (chosen) drawCheck(canvas, r, p, d)
        canvas.restore()
    }
    private fun drawSweep(canvas: Canvas, r: RectF, radius: Float, d: Float) {
        if (sweepDrawn >= 8) return
        sweepDrawn++
        val t = (SystemClock.uptimeMillis() % 4000L) / 4000f
        val band = r.width() * 0.30f
        val x = r.left - band + t * (r.width() + band * 2f)
        sweepMatrix.reset()
        sweepMatrix.setScale(band, 1f)
        sweepMatrix.postTranslate(x, 0f)
        sweepShader.setLocalMatrix(sweepMatrix)
        sweepPaint.shader = sweepShader
        val pulse = (1f - kotlin.math.abs(2f * t - 1f)).coerceIn(0f, 1f)
        val eased = pulse * pulse * (3f - 2f * pulse)
        sweepPaint.alpha = (eased * 24f).toInt().coerceIn(0, 24)
        sweepClip.reset()
        sweepClip.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(sweepClip)
        canvas.rotate(-18f, r.centerX(), r.centerY())
        canvas.drawRect(x, r.top - r.height(), x + band, r.bottom + r.height(), sweepPaint)
        canvas.restore()
        sweepPaint.shader = null
        sweepPaint.alpha = 255
    }
    private fun textColor(fillColor: Int, threshold: Float): Int {
        val lum = (Color.red(fillColor) * 0.299f + Color.green(fillColor) * 0.587f +
                Color.blue(fillColor) * 0.114f) / 255f
        return if (lum > threshold) Color.argb(235, 15, 23, 42) else Color.argb(245, 255, 255, 255)
    }

    private fun drawCheck(canvas: Canvas, r: RectF, p: Palette, d: Float) {
        val cx = r.right - 13f * d
        val cy = r.top + 13f * d
        canvas.drawCircle(cx, cy, 8f * d, Draw.fill(p.brand))
        val tick = Draw.stroke(Color.WHITE, 1.8f * d)
        canvas.drawLine(cx - 4f * d, cy, cx - 1f * d, cy + 3f * d, tick)
        canvas.drawLine(cx - 1f * d, cy + 3f * d, cx + 4f * d, cy - 3f * d, tick)
    }

    private fun drawDenied(canvas: Canvas, r: RectF, radius: Float, p: Palette, d: Float) {
        val path = Path()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        hatch.color = if (p.dark) Color.argb(60, 255, 255, 255) else Color.argb(55, 15, 23, 42)
        hatch.strokeWidth = 1.2f * d
        var x = r.left - r.height()
        while (x < r.right) {
            canvas.drawLine(x, r.bottom, x + r.height(), r.top, hatch)
            x += 9f * d
        }
        canvas.restore()
        if (r.width() > 38f * d && r.height() > 30f * d) {
            Draw.icon(canvas, Icon.LOCK, r.right - 14f * d, r.top + 14f * d, 12f * d,
                    if (p.dark) Color.WHITE else p.text, 1.6f * d)
        }
    }

    private fun drawCluster(canvas: Canvas, r: RectF, radius: Float, p: Palette, d: Float) {
        val path = Path()
        path.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        dot.color = if (p.dark) Color.argb(60, 255, 255, 255) else Color.argb(50, 15, 23, 42)
        val step = 6f * d
        var y = r.top + step
        while (y < r.bottom) {
            var x = r.left + step
            while (x < r.right) {
                canvas.drawCircle(x, y, 1f * d, dot)
                x += step
            }
            y += step
        }
        canvas.restore()
    }

    private fun blockAt(x: Float, y: Float): Block? {
        for (i in blocks.indices.reversed()) {
            if (blocks[i].rect.contains(x, y)) return blocks[i]
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downBlock = blockAt(event.x, event.y)
                longPressDone = false
                if (downBlock != null) postDelayed(longPress, 480L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downBlock != null && (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop)) {
                    removeCallbacks(longPress)
                    downBlock = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val b = downBlock
                downBlock = null
                if (!longPressDone && b != null && b.node >= 0) startRipple(event.x, event.y)
                if (!longPressDone && b != null && !b.aggregate && selectionActive) {
                    onSelectionToggle?.invoke(b.node)
                } else if (!longPressDone && b != null && !b.aggregate) {
                    if ((store?.childCountOf(b.node) ?: 0) > 0) onDrill?.invoke(b.node)
                    else onNodeTap?.invoke(b.node)
                } else if (!longPressDone && b != null && b.aggregate) {
                    onAggregateTap?.invoke(b.aggregateNodes)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                downBlock = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val hit = blockAt(event.x, event.y)?.node ?: -1
                if (hit != hoveredNode) {
                    hoveredNode = hit
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoveredNode = -1
                invalidate()
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    private fun startRipple(x: Float, y: Float) {
        if (FrameStats.degraded || Motion.powerSave) return
        while (ripples.size >= 3) ripples.removeAt(0)
        ripples.add(Ripple(x, y, SystemClock.uptimeMillis()))
        ensureEngine()
        invalidate()
    }

    private fun drawRipples(canvas: Canvas, p: Palette, d: Float) {
        val now = SystemClock.uptimeMillis()
        for (r in ripples) {
            val t = ((now - r.startMs) / MotionDurations.PAGE.toFloat()).coerceIn(0f, 1f)
            val radius = (8f + 80f * Ease.softOut(t)) * d
            ripplePaint.color = Theme.alpha(p.brand, ((1f - t) * 0.18f * 255f).toInt())
            ripplePaint.strokeWidth = 1.5f * d
            canvas.drawCircle(r.cx, r.cy, radius, ripplePaint)
        }
    }
    private fun captureGhosts() {
        if (FrameStats.degraded || Motion.powerSave) return
        for (b in blocks) {
            if (b.node < 0) continue
            val r = RectF()
            b.anim.current(r)
            pendingGhosts.add(Ghost(b.node, r, 0.5f))
        }
        while (pendingGhosts.size > 160) pendingGhosts.removeAt(0)
    }

    private fun drawGhosts(canvas: Canvas, p: Palette, d: Float) {
        for (g in ghosts) {
            ghostPaint.color = Theme.alpha(p.brand, (g.alpha * 150f).toInt().coerceIn(0, 255))
            ghostPaint.strokeWidth = 1f * d
            canvas.drawRoundRect(g.rect, 6f * d, 6f * d, ghostPaint)
        }
    }

    private fun ensureEngine() {
        if (animEngine != null) return
        var active = ghosts.isNotEmpty() || ripples.isNotEmpty()
        for (b in blocks) if (b.anim.isActive()) active = true
        if (!active) return
        animEngine = Motion.add(object : Motion.FrameListener {
            override fun onFrame(frameTimeNanos: Long, dtMs: Float): Boolean {
                val dt = (dtMs / 1000f).coerceIn(0f, 1f / 30f)
                var moving = false
                for (b in blocks) {
                    if (b.anim.step(dt)) moving = true
                }
                val now = SystemClock.uptimeMillis()
                var r = ripples.size - 1
                while (r >= 0) {
                    if (now - ripples[r].startMs > 380L) ripples.removeAt(r) else moving = true
                    r--
                }
                var i = ghosts.size - 1
                while (i >= 0) {
                    val g = ghosts[i]
                    g.alpha -= 3.2f * dt
                    if (g.alpha <= 0f) ghosts.removeAt(i) else moving = true
                    i--
                }
                invalidate()
                if (!moving) {
                    animEngine = null
                    return false
                }
                return true
            }
        })
    }
    override fun onDetachedFromWindow() {
        animEngine?.cancel()
        animEngine = null
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    private data class Ghost(val node: Int, val rect: RectF, var alpha: Float)

    private data class Ripple(val cx: Float, val cy: Float, val startMs: Long)

    private data class LayoutItem(val size: Double, val info: NodeStore.NodeInfo?, val aggregate: IntArray?)

    private data class Block(
            val node: Int,
            val rect: RectF,
            val anim: AnimRect,
            val aggregate: Boolean,
            val aggregateNodes: IntArray,
            val name: String,
            val size: Long,
            val category: Int,
            val flags: Int,
            val nodeDepth: Int,
            val childCount: Int,
            val level: Int,
            var hasNested: Boolean = false,
    )
}
