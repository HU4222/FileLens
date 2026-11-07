package com.spacelens.app.core

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * 标准 squarified treemap（Bruls et al. 2000），长宽比目标 ≤ 2.5。
 * 输入面积为原始字节数（会按容器面积缩放），输出与输入同序。
 */
object Squarify {

    fun layoutAreas(areasIn: DoubleArray, bounds: RectF): List<RectF> {
        val n = areasIn.size
        val out = ArrayList<RectF>(n)
        if (n == 0 || bounds.width() <= 0f || bounds.height() <= 0f) return out
        for (i in 0 until n) out.add(RectF())
        val total = areasIn.sumOf { it.coerceAtLeast(0.0) }
        if (total <= 0.0) return out
        val scale = bounds.width().toDouble() * bounds.height().toDouble() / total
        val a = DoubleArray(n) { areasIn[it].coerceAtLeast(0.0) * scale }

        var x = bounds.left.toDouble()
        var y = bounds.top.toDouble()
        var w = bounds.width().toDouble()
        var h = bounds.height().toDouble()
        var i = 0
        val eps = 1e-6

        while (i < n && w > eps && h > eps) {
            val side = min(w, h)
            val row = ArrayList<Int>()
            var rowArea = 0.0
            var worst = 1e18
            while (i < n) {
                val area = a[i]
                if (area <= 0.0) {
                    i++
                    continue
                }
                val newArea = rowArea + area
                val newWorst = computeWorst(row, a, area, newArea, side)
                if (row.isEmpty() || newWorst <= worst + 1e-9) {
                    row.add(i)
                    rowArea = newArea
                    worst = newWorst
                    i++
                } else {
                    break
                }
            }
            if (row.isEmpty()) break

            if (w >= h) {
                val stripW = min(w, rowArea / h)
                var cy = y
                for (k in row.indices) {
                    val idx = row[k]
                    val last = k == row.size - 1
                    val rh = if (last) (y + h) - cy else a[idx] / stripW
                    out[idx].set(x.toFloat(), cy.toFloat(), (x + stripW).toFloat(), (cy + rh).toFloat())
                    cy += rh
                }
                x += stripW
                w -= stripW
            } else {
                val stripH = min(h, rowArea / w)
                var cx = x
                for (k in row.indices) {
                    val idx = row[k]
                    val last = k == row.size - 1
                    val rw = if (last) (x + w) - cx else a[idx] / stripH
                    out[idx].set(cx.toFloat(), y.toFloat(), (cx + rw).toFloat(), (y + stripH).toFloat())
                    cx += rw
                }
                y += stripH
                h -= stripH
            }
        }

        for (r in out) {
            if (r.width() < 0f || r.height() < 0f) r.setEmpty()
        }
        return out
    }

    private fun computeWorst(row: List<Int>, a: DoubleArray, extra: Double, sum: Double, side: Double): Double {
        var mx = extra
        var mn = extra
        for (idx in row) {
            mx = max(mx, a[idx])
            mn = min(mn, a[idx])
        }
        if (mn <= 0.0 || sum <= 0.0) return 1e18
        val s2 = sum * sum
        val w2 = side * side
        return max(w2 * mx / s2, s2 / (w2 * mn))
    }
}
