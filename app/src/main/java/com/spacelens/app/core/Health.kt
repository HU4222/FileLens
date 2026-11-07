package com.spacelens.app.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * M5 体检：大文件 / 重复 / 空目录 / 缓存五档 / 近 7 天增长 / 快照对比。
 * 只读判定，不执行删除；删除一律走 5.9 选择模式 + 回收站。
 */
object Health {

    data class Finding(
            val title: String,
            val summary: String,
            val bytes: Long,
            val paths: List<String>,
    )

    fun scan(context: Context, store: NodeStore, rootPath: String): List<Finding> {
        val out = ArrayList<Finding>()
        if (store.count <= 1) return out

        val large = ArrayList<Pair<String, Long>>()
        val empty = ArrayList<String>()
        val bySize = HashMap<Long, MutableList<String>>()
        val cache = HashMap<String, LongArray>()
        val growth = ArrayList<Triple<String, Long, Long>>()
        val now = System.currentTimeMillis()
        val week = 7L * 24 * 60 * 60 * 1000
        for (i in 1 until store.count) {
            val size = store.sizeOf(i)
            val path = store.absolutePathOf(rootPath, i)
            val flags = store.flagsOf(i)
            if (flags and NodeStore.DIR != 0) {
                if (store.filesOf(i) == 0 && store.dirsOf(i) == 0 && empty.size < 200) empty.add(path)
                if (store.mtimeOf(i) in (now - week)..now && size > 0 && growth.size < 10) {
                    growth.add(Triple(path, size, store.mtimeOf(i)))
                }
                val c = cacheBucket(path)
                if (c != null) {
                    val v = cache.getOrPut(c) { LongArray(2) }
                    v[0]++
                    v[1] += size
                }
            } else if (flags and NodeStore.FILE != 0) {
                if (size >= 100L * 1024 * 1024 && large.size < 50) large.add(path to size)
                if (size >= 1024 * 1024) bySize.getOrPut(size) { ArrayList() }.add(path)
            }
        }

        val largeBytes = large.sumOf { it.second }
        out.add(Finding("大文件 Top 50", "${large.size} 个 >100MB，合计 ${Fmt.bytes(largeBytes)}", largeBytes,
                large.map { it.first }))

        val emptySize = empty.sumOf { File(it).length() }
        out.add(Finding("空目录", "${empty.size} 个空目录", emptySize, empty))

        val dup = findDuplicates(bySize)
        out.add(Finding("重复文件", "${dup.first.size} 组，可释放 ${Fmt.bytes(dup.second)}",
                dup.second, dup.first))

        val c1 = cache["可删"] ?: LongArray(2)
        val c2 = cache["可重建"] ?: LongArray(2)
        val c3 = cache["谨慎"] ?: LongArray(2)
        val c4 = cache["别动"] ?: LongArray(2)
        val c5 = cache["系统级"] ?: LongArray(2)
        out.add(Finding("缓存识别五档",
                "可删 ${c1[0]} 项 ${Fmt.bytes(c1[1])}；可重建 ${c2[0]} 项；谨慎 ${c3[0]} 项；别动 ${c4[0]} 项；系统级 ${c5[0]} 项",
                c1[1] + c2[1], emptyList()))

        val g = growth.sortedByDescending { it.second }.take(10)
        out.add(Finding("近 7 天增长", if (g.isEmpty()) "暂无增长目录" else g.joinToString("；") {
            it.first.substringAfterLast('/') + " +" + Fmt.bytes(it.second)
        }, g.sumOf { it.second }, g.map { it.first }))

        out.add(Finding("快照对比", "需保留两次扫描快照；当前只保留最近一次，M5 内未接", 0L, emptyList()))
        return out
    }

    private fun cacheBucket(path: String): String? {
        val p = path.lowercase()
        return when {
            p.contains("/cache/") || p.contains("/.cache/") -> "可删"
            p.contains("/tmp/") || p.contains("/temp/") -> "可删"
            p.contains("/thumbnails/") || p.contains("/.thumbnails/") -> "可重建"
            p.contains("/download/") -> "谨慎"
            p.contains("/android/data/") || p.contains("/android/obb/") -> "系统级"
            p.contains("/spaceLensTest".lowercase()) -> "别动"
            else -> null
        }
    }

    private fun findDuplicates(bySize: Map<Long, MutableList<String>>): Pair<List<String>, Long> {
        val groups = ArrayList<String>()
        var reclaim = 0L
        for ((size, paths) in bySize) {
            if (paths.size < 2) continue
            if (size < 1024 * 1024) continue
            val seen = HashMap<String, String>()
            for (p in paths) {
                val h = sampleHash(p) ?: continue
                val old = seen.put(h, p)
                if (old != null) {
                    groups.add(File(p).name + " ↔ " + File(old).name + "  " + Fmt.bytes(size))
                    reclaim += size
                }
            }
        }
        return groups to reclaim
    }

    private fun sampleHash(path: String): String? {
        return try {
            val f = File(path)
            if (!f.isFile) return null
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                val n = input.read(buf)
                if (n > 0) md.update(buf, 0, n)
            }
            if (f.length() > 128 * 1024) {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(f.length() - 64 * 1024)
                    val buf = ByteArray(64 * 1024)
                    val n = raf.read(buf)
                    if (n > 0) md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            null
        }
    }
}
