package com.spacelens.app.core

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站（PRD 5.9）：移动 / 还原 / 彻底删 / 过期清理。
 * 用户文件主删除路径禁止 File.delete()；本文件只清自己的回收站目录。
 *
 * 落点：/sdcard/Android/media/com.spacelens.app/trash/<yyyyMMdd-HHmmss>/<原相对路径>
 */
object Trash {

    const val DIR_NAME = "trash"
    private const val META = "trash.json"
    private const val MAX_AGE_DAYS = 30
    private const val TAG = "SpaceLens"

    data class Entry(
            val originalPath: String,
            val size: Long,
            val movedAt: Long,
            val batchId: String,
            val restorable: Boolean,
    )

    data class Item(
            val batchId: String,
            val originalPath: String,
            val trashFile: File,
            val size: Long,
            val movedAt: Long,
            val restorable: Boolean,
    )

    data class MoveResult(val moved: Int, val bytes: Long, val skipped: Int, val message: String)

    fun root(context: Context): File? {
        return try {
            val dirs = context.getExternalMediaDirs() ?: return null
            val base = dirs.firstOrNull { it != null } ?: return null
            File(base, DIR_NAME)
        } catch (_: Throwable) {
            null
        }
    }

    fun batchId(now: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

    fun batchDir(context: Context, batchId: String): File? =
            root(context)?.let { File(it, batchId) }

    fun target(context: Context, batchId: String, relativePath: String): File? {
        val batch = batchDir(context, batchId) ?: return null
        val rel = relativePath.trim().trim('/').replace('\\', '/')
        if (rel.isEmpty()) return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(batch, rel)
    }

    fun contains(context: Context, absolutePath: String): Boolean {
        val root = root(context)?.absolutePath?.trimEnd('/') ?: return false
        val p = absolutePath.trimEnd('/')
        return p == root || p.startsWith("$root/")
    }

    fun isExpired(movedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
            now - movedAt > MAX_AGE_DAYS * 24L * 60L * 60L * 1000L

    /** 同卷 rename；跨卷不回退 copy+delete（避免主路径直删），记为跳过。 */
    fun move(context: Context, files: List<Pair<File, String>>, batch: String = batchId(),
             onProgress: (Long, Long) -> Unit = { _, _ -> },
             shouldStop: () -> Boolean = { false }): MoveResult {
        val total = files.sumOf { it.first.length() }
        val batchDir = batchDir(context, batch) ?: return MoveResult(0, 0, files.size, "回收站不可用")
        var moved = 0
        var bytes = 0L
        var doneBytes = 0L
        var skipped = 0
        val entries = ArrayList<JSONObject>()
        for ((file, relative) in files) {
            if (shouldStop()) break
            val dest = target(context, batch, relative)
            if (dest == null || !file.exists() || dest.exists()) {
                skipped++
                continue
            }
            dest.parentFile?.mkdirs()
            if (!file.renameTo(dest)) {
                skipped++
                continue
            }
            moved++
            bytes += dest.length()
            doneBytes += file.length()
            onProgress(doneBytes, total)
            entries.add(JSONObject().apply {
                put("originalPath", file.absolutePath)
                put("relativePath", relative)
                put("trashPath", dest.absolutePath)
                put("size", dest.length())
                put("movedAt", System.currentTimeMillis())
                put("batchId", batch)
                put("restorable", true)
            })
        }
        if (entries.isNotEmpty()) writeMeta(batchDir, entries)
        val msg = when {
            moved == 0 -> "没有可移动的文件"
            skipped > 0 -> "已移入回收站 $moved 项，跳过 $skipped 项"
            else -> "已移入回收站 $moved 项"
        }
        return MoveResult(moved, bytes, skipped, msg)
    }

    /** 原子写：临时文件 + rename，避免半截 JSON。 */
    private fun writeMeta(batchDir: File, entries: List<JSONObject>) {
        try {
            val arr = JSONArray()
            for (e in entries) arr.put(e)
            for (e in readMeta(batchDir)) arr.put(e)
            val tmp = File(batchDir, "$META.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            val meta = File(batchDir, META)
            if (meta.exists()) meta.delete()
            if (!tmp.renameTo(meta)) {
                meta.writeText(arr.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    private fun readMeta(batchDir: File): List<JSONObject> {
        val f = File(batchDir, META)
        if (!f.isFile) return emptyList()
        return try {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 只返回 trashPath 落在 trashRoot 下、且 originalPath 可安全还原的条目。 */
    fun entries(context: Context): List<Item> {
        val root = root(context) ?: return emptyList()
        val rootPath = canonical(root)
        val out = ArrayList<Item>()
        for (b in root.listFiles() ?: return emptyList()) {
            if (!b.isDirectory) continue
            for (o in readMeta(b)) {
                val f = File(o.optString("trashPath", ""))
                val cp = canonical(f)
                if (cp == rootPath || !isUnder(cp, rootPath)) {
                    Log.w(TAG, "trash entry rejected: outside root")
                    continue
                }
                val original = o.optString("originalPath", "")
                val safe = isSafeOriginal(context, original) && f.exists()
                out.add(Item(
                        batchId = o.optString("batchId", b.name),
                        originalPath = original,
                        trashFile = f,
                        size = o.optLong("size", f.length()),
                        movedAt = o.optLong("movedAt", 0L),
                        restorable = o.optBoolean("restorable", true) && safe,
                ))
            }
        }
        return out.sortedByDescending { it.movedAt }
    }

    fun restore(context: Context, item: Item): Boolean {
        if (!item.restorable || !item.trashFile.exists()) return false
        val rootPath = canonical(root(context) ?: return false)
        val cp = canonical(item.trashFile)
        if (cp == rootPath || !isUnder(cp, rootPath)) return false
        if (!isSafeOriginal(context, item.originalPath)) return false
        val original = File(item.originalPath)
        if (original.exists()) return false
        original.parentFile?.mkdirs()
        if (!item.trashFile.renameTo(original)) return false
        removeEntry(context, item)
        return true
    }

    fun purge(context: Context, item: Item) {
        val rootPath = canonical(root(context) ?: return)
        val cp = canonical(item.trashFile)
        if (cp == rootPath || !isUnder(cp, rootPath)) {
            Log.w(TAG, "trash purge rejected: outside root")
            return
        }
        try {
            if (item.trashFile.exists()) item.trashFile.deleteRecursively()
        } catch (_: Throwable) {
        }
        removeEntry(context, item)
    }

    private fun removeEntry(context: Context, item: Item) {
        val batch = batchDir(context, item.batchId) ?: return
        val remain = readMeta(batch).filter { it.optString("trashPath") != item.trashFile.absolutePath }
        try {
            val arr = JSONArray()
            for (o in remain) arr.put(o)
            val meta = File(batch, META)
            if (remain.isEmpty()) {
                meta.delete()
                if (batch.listFiles()?.isEmpty() == true) batch.delete()
            } else {
                meta.writeText(arr.toString(), Charsets.UTF_8)
            }
        } catch (_: Throwable) {
        }
    }

    fun purgeExpired(context: Context): Int {
        var n = 0
        for (e in entries(context)) {
            if (isExpired(e.movedAt)) {
                purge(context, e)
                n++
            }
        }
        return n
    }

    fun empty(context: Context): Int {
        val root = root(context) ?: return 0
        var n = 0
        for (b in root.listFiles() ?: emptyArray()) {
            if (b.isDirectory) {
                n += (b.listFiles()?.size ?: 0)
                b.deleteRecursively()
            }
        }
        return n
    }

    fun totalBytes(context: Context): Long = entries(context).sumOf { it.size }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (_: Throwable) {
        f.absolutePath
    }

    private fun isUnder(path: String, root: String): Boolean {
        val r = root.trimEnd('/')
        return path == r || path.startsWith("$r/")
    }

    /** originalPath 必须在共享存储内，且避开 Android/data、Android/obb、应用目录、回收站自身。 */
    private fun isSafeOriginal(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        val ext = try {
            Environment.getExternalStorageDirectory().canonicalPath
        } catch (_: Throwable) {
            return false
        }
        val p = canonical(File(path))
        if (p == ext || !p.startsWith(ext.trimEnd('/') + "/")) return false
        val rel = p.removePrefix(ext).trimStart('/').replace('\\', '/').lowercase()
        if (rel == "android/data" || rel.startsWith("android/data/")) return false
        if (rel == "android/obb" || rel.startsWith("android/obb/")) return false
        if (contains(context, p)) return false
        val candidates = ArrayList<String>(6)
        candidates.add(context.filesDir.absolutePath)
        candidates.add(context.cacheDir.absolutePath)
        context.getExternalFilesDir(null)?.let { candidates.add(it.absolutePath) }
        context.getExternalCacheDir()?.let { candidates.add(it.absolutePath) }
        try {
            context.getExternalMediaDirs()?.forEach { f -> if (f != null) candidates.add(f.absolutePath) }
        } catch (_: Throwable) {
        }
        for (c in candidates) if (isUnder(p, canonical(File(c)))) return false
        return true
    }
}
