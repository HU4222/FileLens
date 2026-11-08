package com.spacelens.app.debug

import android.graphics.RectF
import android.util.Log

/**
 * 仅 Debug 的虚拟控件表：各页面在绘制时把 hit 用的矩形登记进来，
 * flDumpControls 原样打印，flTap 按 id 直接触发动作。
 * Release 下不会有人调用（入口都在 BuildConfig.DEBUG 内）。
 */
object ControlRegistry {

    data class Ctl(val id: String, val label: String, val screen: String, val rect: RectF)

    private val items = LinkedHashMap<String, Ctl>()

    @Synchronized
    fun clear(screen: String) {
        items.entries.removeAll { it.value.screen == screen }
    }

    @Synchronized
    fun put(screen: String, id: String, label: String, rect: RectF) {
        items[id] = Ctl(id, label, screen, RectF(rect))
    }

    @Synchronized
    fun find(id: String): Ctl? = items[id]

    @Synchronized
    fun dumpToLog() {
        for (c in items.values) {
            Log.i("FLCTL", "screen=${c.screen} id=${c.id} text=${c.label} " +
                    "rect=${c.rect.left.toInt()},${c.rect.top.toInt()}," +
                    "${c.rect.right.toInt()},${c.rect.bottom.toInt()}")
        }
        Log.i("FLCTL", "total=${items.size}")
    }
}
