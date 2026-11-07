package com.spacelens.app.core

/**
 * 垃圾建议：只读 NodeStore，复用 Classifier 的文件类型与 Health 的缓存路径口径。
 * 只输出分类与候选节点；是否可删仍由 DeletePolicy 决定。
 */
object GarbageAdvisor {

    enum class Risk(val label: String) {
        SAFE("可删"),
        REBUILD("可重建"),
        CAUTION("谨慎"),
    }

    data class Item(
            val node: Int,
            val name: String,
            val path: String,
            val size: Long,
    )

    data class Category(
            val key: String,
            val label: String,
            val risk: Risk,
            val count: Int,
            val bytes: Long,
            val items: List<Item>,
            val truncated: Boolean,
    )

    fun scan(store: NodeStore, rootPath: String): List<Category> {
        val cache = Bucket("cache", "缓存", Risk.SAFE)
        val empty = Bucket("empty", "空目录", Risk.SAFE)
        val logs = Bucket("logs", "日志文件", Risk.SAFE)
        val apk = Bucket("apk", "旧安装包", Risk.CAUTION)
        val thumbs = Bucket("thumbs", "缩略图", Risk.REBUILD)
        val all = arrayOf(cache, empty, logs, apk, thumbs)
        if (store.count <= 1) return all.map { it.finish() }

        store.read {
            for (i in 1 until store.count) {
                val flags = store.flagsOf(i)
                val name = store.nameOf(i)
                val path = store.absolutePathOf(rootPath, i)
                val size = store.sizeOf(i)
                val p = path.replace('\\', '/').lowercase()
                if (flags and NodeStore.DIR != 0) {
                    if (store.filesOf(i) == 0 && store.dirsOf(i) == 0) {
                        empty.add(i, name, path, size)
                    }
                    continue
                }
                if (flags and NodeStore.FILE == 0) continue
                when {
                    p.contains("/cache/") || p.contains("/.cache/") ||
                            p.contains("/tmp/") || p.contains("/temp/") ->
                        cache.add(i, name, path, size)
                    p.contains("/thumbnails/") || p.contains("/.thumbnails/") ||
                            p.contains("/thumb/") ->
                        thumbs.add(i, name, path, size)
                    name.lowercase().endsWith(".log") || p.contains("/log/") ->
                        logs.add(i, name, path, size)
                    Classifier.categoryOf(name, flags) == Classifier.APK ->
                        apk.add(i, name, path, size)
                }
            }
        }
        return all.map { it.finish() }
    }

    private class Bucket(
            val key: String,
            val label: String,
            val risk: Risk,
    ) {
        var count = 0
        var bytes = 0L
        val items = ArrayList<Item>()

        fun add(node: Int, name: String, path: String, size: Long) {
            count++
            bytes += size
            if (items.size < 100) items.add(Item(node, name, path, size))
        }

        fun finish(): Category = Category(key, label, risk, count, bytes, items, count > items.size)
    }
}
