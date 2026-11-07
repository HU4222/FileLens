package com.spacelens.app.core

import java.util.LinkedHashSet

/**
 * 多选状态（5.9 / 8.5）。M2 只预留在 MainActivity / TreemapView 的接口，
 * 真正接删除流程在 M5；这里保持纯数据，方便单测与复用。
 */
class SelectionState {

    var active: Boolean = false
        private set

    var anchor: Int = -1
        private set

    private val indices = LinkedHashSet<Int>()

    val selected: Set<Int> get() = indices
    val count: Int get() = indices.size

    fun start(node: Int) {
        active = true
        anchor = node
        indices.clear()
        if (node >= 0) indices.add(node)
    }

    fun toggle(node: Int) {
        if (!active) {
            start(node)
            return
        }
        if (node < 0) return
        if (!indices.remove(node)) indices.add(node)
        if (indices.isEmpty()) {
            active = false
            anchor = -1
        }
    }

    fun add(node: Int) {
        if (node >= 0) {
            active = true
            indices.add(node)
        }
    }

    fun remove(node: Int) {
        indices.remove(node)
        if (indices.isEmpty()) {
            active = false
            anchor = -1
        }
    }

    fun clear() {
        active = false
        anchor = -1
        indices.clear()
    }

    /** 全选同级：返回实际加入的数量。 */
    fun selectAllSiblings(store: NodeStore, parent: Int): Int {
        val children = store.childrenOf(parent)
        for (c in children) indices.add(c)
        active = indices.isNotEmpty()
        if (active && anchor < 0) anchor = children.firstOrNull() ?: -1
        return children.size
    }

    fun totalBytes(store: NodeStore): Long {
        var total = 0L
        for (i in indices) total += store.sizeOf(i)
        return total
    }

    fun snapshot(): IntArray = indices.toIntArray()
}
