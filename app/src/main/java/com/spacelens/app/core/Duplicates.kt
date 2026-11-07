package com.spacelens.app.core

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 重复文件扫描：只把「同 size 且 ≥1MB」的文件送进 SHA-256。
 * 全文件哈希，避免采样哈希造成误删；扫描可中断。
 */
object Duplicates {

    data class Item(
            val node: Int,
            val path: String,
            val name: String,
            val size: Long,
    )

    data class Group(
            val size: Long,
            val files: List<Item>,
    ) {
        val reclaim: Long get() = size * (files.size - 1).coerceAtLeast(0)
    }

    /**
     * @param onProgress (已检查组数, 总组数)
     * @param cancelled 每读 1MB 和每组之间检查
     */
    fun find(
            store: NodeStore,
            rootPath: String,
            onProgress: (Int, Int) -> Unit = { _, _ -> },
            cancelled: () -> Boolean = { false },
    ): List<Group> {
        if (store.count <= 1) return emptyList()
        val bySize = HashMap<Long, MutableList<Item>>()
        store.read {
            for (i in 1 until store.count) {
                val flags = store.flagsOf(i)
                if (flags and NodeStore.FILE == 0) continue
                val size = store.sizeOf(i)
                if (size < 1024L * 1024L) continue
                val path = store.absolutePathOf(rootPath, i)
                bySize.getOrPut(size) { ArrayList() }
                        .add(Item(i, path, store.nameOf(i), size))
            }
        }
        val candidates = bySize.values.filter { it.size >= 2 }
        val total = candidates.size
        val out = ArrayList<Group>()
        var checked = 0
        for (items in candidates) {
            if (cancelled()) break
            val byHash = HashMap<String, MutableList<Item>>()
            for (item in items) {
                if (cancelled()) break
                val hash = sha256(item, cancelled) ?: continue
                byHash.getOrPut(hash) { ArrayList() }.add(item)
            }
            checked++
            for (same in byHash.values) {
                if (same.size >= 2) out.add(Group(same[0].size, same))
            }
            onProgress(checked, total)
        }
        out.sortByDescending { it.reclaim }
        return out
    }

    private fun sha256(item: Item, cancelled: () -> Boolean): String? {
        return try {
            val f = File(item.path)
            if (!f.isFile || f.length() != item.size) return null
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    if (cancelled()) return null
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }
    }
}
