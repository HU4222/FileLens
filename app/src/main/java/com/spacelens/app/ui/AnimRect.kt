package com.spacelens.app.ui

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min

/**
 * 四轴一体的矩形弹簧（v1.3.0 / DiskLens）。
 * k=168, c=25.5 → ζ≈0.98；dt 上限 1/30；静止双阈值。
 */
class AnimRect {

    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    private var vx = 0f
    private var vy = 0f
    private var vw = 0f
    private var vh = 0f

    private var tx = 0f
    private var ty = 0f
    private var tw = 0f
    private var th = 0f

    var alpha = 1f
        private set
    private var delayMs = 0f
    private var ageMs = 0f
    private var started = false

    fun startAtCenter(target: RectF, delayMs: Float) {
        tx = target.left
        ty = target.top
        tw = target.width()
        th = target.height()
        w = (tw * 0.04f).coerceAtLeast(1f)
        h = (th * 0.04f).coerceAtLeast(1f)
        x = target.centerX() - w / 2f
        y = target.centerY() - h / 2f
        vx = 0f
        vy = 0f
        vw = 0f
        vh = 0f
        alpha = 0f
        this.delayMs = delayMs
        ageMs = 0f
        started = true
    }

    fun retarget(target: RectF) {
        tx = target.left
        ty = target.top
        tw = target.width()
        th = target.height()
        if (!started) {
            snapToTarget(keepAlpha = false)
        }
    }

    fun snapToTarget(keepAlpha: Boolean = true) {
        x = tx
        y = ty
        w = tw
        h = th
        vx = 0f
        vy = 0f
        vw = 0f
        vh = 0f
        if (!keepAlpha) alpha = 1f
    }

    fun current(out: RectF) {
        out.set(x, y, x + w, y + h)
    }

    fun isActive(): Boolean {
        if (alpha < 1f) return true
        return !settledAxis(x, tx, vx) || !settledAxis(y, ty, vy) ||
                !settledAxis(w, tw, vw) || !settledAxis(h, th, vh)
    }

    /** @return true 表示仍需下一帧。 */
    fun step(dtSeconds: Float): Boolean {
        if (ageMs < delayMs) {
            ageMs += dtSeconds * 1000f
            return true
        }
        alpha = min(1f, alpha + 5.5f * dtSeconds)
        val dt = dtSeconds.coerceIn(0f, 1f / 30f)
        val k = Motion.SETTLE_K
        val c = Motion.SETTLE_C
        vx += (-k * (x - tx) - c * vx) * dt
        vy += (-k * (y - ty) - c * vy) * dt
        vw += (-k * (w - tw) - c * vw) * dt
        vh += (-k * (h - th) - c * vh) * dt
        x += vx * dt
        y += vy * dt
        w += vw * dt
        h += vh * dt

        val settled = settledAxis(x, tx, vx) && settledAxis(y, ty, vy) &&
                settledAxis(w, tw, vw) && settledAxis(h, th, vh)
        if (settled && alpha >= 1f) {
            snapToTarget()
            return false
        }
        return true
    }

    private fun settledAxis(p: Float, target: Float, v: Float): Boolean =
            abs(p - target) < 0.28f && abs(v) < 2.5f
}
