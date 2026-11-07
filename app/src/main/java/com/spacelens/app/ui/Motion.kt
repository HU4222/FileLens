package com.spacelens.app.ui

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp

object Ease {
    fun linear(t: Float): Float = t
    fun outCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }
    fun inOutCubic(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    fun outExpo(t: Float): Float = if (t >= 1f) 1f else 1f - exp((-10f * t)).toFloat()

    /**
     * DropLink 曲线：cubic-bezier(0.22, 0.9, 0.3, 1)。
     * 三次贝塞尔求值，不用 outCubic 近似。
     */
    fun softOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (bezier(mid, 0.22f, 0.3f) < x) lo = mid else hi = mid
        }
        return bezier((lo + hi) / 2f, 0.9f, 1f)
    }

    private fun bezier(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }
}

/** 动效时长（ms）：只允许在这里出现，页面/控件不散落时长。 */
object MotionDurations {
    const val PAGE = 280
    const val ITEM = 200
    const val SHEET = 220
    const val FADE = 160
    const val PRESS = 130
    const val INDICATOR = 180
}

/**
 * 全应用唯一帧时钟（PRD 7.1）。所有动画都挂在这里，禁止每个动画各起 ValueAnimator。
 * 只在有监听者时运行，空闲时自动停表。
 */
object FrameStats {
    private const val CAP = 240
    private val samples = FloatArray(CAP)
    private var count = 0
    private var index = 0
    private var slowRun = 0

    @Volatile var degraded = false
        private set

    fun record(dtMs: Float) {
        samples[index] = dtMs
        index = (index + 1) % CAP
        if (count < CAP) count++
        if (dtMs > 24f) slowRun++ else slowRun = 0
        if (slowRun >= 5) degraded = true
    }

    fun avgMs(): Float {
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) sum += samples[i]
        return sum / count
    }

    fun p95Ms(): Float {
        if (count == 0) return 0f
        val a = samples.copyOf(count)
        a.sort()
        return a[((count - 1) * 0.95f).toInt()]
    }

    fun longFrames(): Int {
        var n = 0
        for (i in 0 until count) if (samples[i] > 32f) n++
        return n
    }

    fun fps(): Float {
        val avg = avgMs()
        return if (avg > 0f) 1000f / avg else 0f
    }

    fun reset() {
        count = 0
        index = 0
        slowRun = 0
        degraded = false
    }
}

object Motion {

    @Volatile var powerSave = false

    fun softOut(t: Float): Float = Ease.softOut(t)

    // v1.2.0 统一弹簧参数：全站只在这里出现
    const val PAGE_STIFFNESS = 380f
    const val INDICATOR_STIFFNESS = 500f
    const val PRESS_STIFFNESS = 600f
    const val SETTLE_K = 168f
    const val SETTLE_C = 25.5f

    // 物理阻尼系数 c = 2ζ√k；不再用 20 倍隐式换算。
    @JvmField val PAGE_DAMPING = 2f * 0.90f * kotlin.math.sqrt(380f)
    @JvmField val INDICATOR_DAMPING = 2f * 0.90f * kotlin.math.sqrt(500f)
    @JvmField val PRESS_DAMPING = 2f * 0.90f * kotlin.math.sqrt(600f)

    interface FrameListener {
        /** @return false 表示本帧结束后移除。 */
        fun onFrame(frameTimeNanos: Long, dtMs: Float): Boolean
    }

    class Animation internal constructor(
            private val listener: FrameListener,
    ) {
        @Volatile
        private var cancelled = false

        fun cancel() {
            cancelled = true
            remove(listener)
        }

        internal fun isCancelled(): Boolean = cancelled
    }

    private val listeners = ArrayList<FrameListener>()
    private var scheduled = false
    private var lastNanos = 0L
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private val frameCallback: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastNanos == 0L) 16.667f
            else ((frameTimeNanos - lastNanos) / 1_000_000.0).toFloat().coerceIn(0f, 64f)
            lastNanos = frameTimeNanos
            FrameStats.record(dt)
            val snapshot = listeners.toTypedArray()
            for (l in snapshot) {
                if (!listeners.contains(l)) continue
                val keep = try {
                    l.onFrame(frameTimeNanos, dt)
                } catch (t: Throwable) {
                    false
                }
                if (!keep) listeners.remove(l)
            }
            if (listeners.isEmpty()) {
                scheduled = false
                lastNanos = 0L
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    fun add(listener: FrameListener): Animation {
        if (!listeners.contains(listener)) listeners.add(listener)
        if (!scheduled) {
            scheduled = true
            lastNanos = 0L
            choreographer.postFrameCallback(frameCallback)
        }
        return Animation(listener)
    }

    fun remove(listener: FrameListener) {
        listeners.remove(listener)
    }

    fun clear() {
        listeners.clear()
        scheduled = false
        lastNanos = 0L
    }

    fun tween(
            durationMs: Int,
            easing: (Float) -> Float = Ease::softOut,
            update: (Float) -> Unit,
            end: (() -> Unit)? = null,
    ): Animation {
        val duration = durationMs.coerceAtLeast(1)
        val l = object : FrameListener {
            private var startNanos = -1L
            private var done = false

            override fun onFrame(frameTimeNanos: Long, dtMs: Float): Boolean {
                if (done) return false
                if (startNanos < 0L) startNanos = frameTimeNanos
                val raw = ((frameTimeNanos - startNanos) / 1_000_000.0 / duration).toFloat()
                val t = raw.coerceIn(0f, 1f)
                update(easing(t))
                if (t >= 1f) {
                    done = true
                    end?.invoke()
                    return false
                }
                return true
            }
        }
        return add(l)
    }

    /** 弹簧：页签指示器用 stiffness 500 / damping 0.82。 */
    fun spring(
            from: Float,
            to: Float,
            stiffness: Float = PAGE_STIFFNESS,
            damping: Float = PAGE_DAMPING,
            update: (Float) -> Unit,
            end: (() -> Unit)? = null,
    ): Animation = springInternal(from, to, stiffness, damping, update, end)

    fun pageSpring(
            from: Float,
            to: Float,
            update: (Float) -> Unit,
            end: (() -> Unit)? = null,
    ): Animation = springInternal(from, to, PAGE_STIFFNESS, PAGE_DAMPING, update, end)

    fun indicatorSpring(
            from: Float,
            to: Float,
            update: (Float) -> Unit,
            end: (() -> Unit)? = null,
    ): Animation = springInternal(from, to, INDICATOR_STIFFNESS, INDICATOR_DAMPING, update, end)

    private fun springInternal(
            from: Float,
            to: Float,
            stiffness: Float,
            damping: Float,
            update: (Float) -> Unit,
            end: (() -> Unit)? = null,
    ): Animation {
        val l = object : FrameListener {
            private var value = from
            private var velocity = 0f
            private var settled = 0
            private var finished = false

            override fun onFrame(frameTimeNanos: Long, dtMs: Float): Boolean {
                if (finished) return false
                val dt = (dtMs / 1000f).coerceIn(0f, 1f / 30f)
                var force = -stiffness * (value - to) - damping * velocity
                velocity += force * dt
                value += velocity * dt
                update(value)
                if (abs(value - to) < 0.0008f && abs(velocity) < 0.02f) {
                    settled++
                    if (settled >= 2) {
                        value = to
                        update(to)
                        finished = true
                        end?.invoke()
                        return false
                    }
                } else {
                    settled = 0
                }
                return true
            }
        }
        return add(l)
    }
}
