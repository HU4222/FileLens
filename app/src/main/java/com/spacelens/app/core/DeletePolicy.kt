package com.spacelens.app.core

import android.content.Context
import java.io.File

/**
 * 可删 / 需二次确认 / 禁止选中（PRD 5.9，唯一判定入口）。
 * 只做判定，不执行删除；UI 不得复制这里的规则。
 */
object DeletePolicy {

    data class Decision(
            val selectable: Boolean,
            val extraConfirm: Boolean,
            val reason: String,
    ) {
        companion object {
            val OK = Decision(true, false, "")
        }
    }

    private const val EXTRA_FILES = 500
    private const val EXTRA_BYTES = 2L * 1024 * 1024 * 1024

    fun decide(context: Context, store: NodeStore, node: Int, rootPath: String): Decision {
        if (node <= 0 || node >= store.count) {
            return Decision(false, false, "存储根目录不可选择")
        }
        val flags = store.flagsOf(node)
        if (flags and NodeStore.SYMLINK != 0) {
            return Decision(false, false, "符号链接不可选择")
        }
        if (rootPath.startsWith("content://")) {
            return Decision(false, false, "SAF 目录暂不支持删除")
        }
        val relative = store.relativePathOf(node)
        val abs = absolute(rootPath, relative)
        val normalized = relative.replace('\\', '/').lowercase()

        if (normalized == "android/data" || normalized.startsWith("android/data/")) {
            return Decision(false, false, "Android/data 为系统保护目录")
        }
        if (normalized == "android/obb" || normalized.startsWith("android/obb/")) {
            return Decision(false, false, "Android/obb 为系统保护目录")
        }
        if (matchesAnyAppDir(context, abs)) {
            return Decision(false, false, "本应用目录不可选择")
        }
        if (Trash.contains(context, abs)) {
            return Decision(false, false, "回收站自身不可选择")
        }
        if (isSystemPath(abs)) {
            return Decision(false, false, "系统分区不可选择")
        }
        if (relative.isEmpty()) {
            return Decision(false, false, "挂载点根目录不可选择")
        }

        val size = store.sizeOf(node)
        val files = store.filesOf(node).toLong()
        val extra = files >= EXTRA_FILES || size >= EXTRA_BYTES
        return Decision(true, extra, if (extra) "数量或体积较大，需要额外确认" else "")
    }

    private fun absolute(rootPath: String, relative: String): String {
        val root = rootPath.trimEnd('/')
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    private fun matchesAnyAppDir(context: Context, abs: String): Boolean {
        val candidates = ArrayList<String>(6)
        candidates.add(context.filesDir.absolutePath)
        candidates.add(context.cacheDir.absolutePath)
        context.getExternalFilesDir(null)?.let { candidates.add(it.absolutePath) }
        context.getExternalCacheDir()?.let { candidates.add(it.absolutePath) }
        try {
            context.getExternalMediaDirs()?.forEach { f -> if (f != null) candidates.add(f.absolutePath) }
        } catch (_: Throwable) {
        }
        candidates.add(File(context.filesDir.parentFile, "code_cache").absolutePath)
        for (c in candidates) {
            val base = c.trimEnd('/')
            if (abs == base || abs.startsWith("$base/")) return true
        }
        return false
    }

    private fun isSystemPath(abs: String): Boolean {
        val p = abs.replace('\\', '/')
        return p == "/data" || p.startsWith("/data/") ||
                p == "/system" || p.startsWith("/system/") ||
                p == "/vendor" || p.startsWith("/vendor/") ||
                p == "/proc" || p.startsWith("/proc/") ||
                p == "/sys" || p.startsWith("/sys/") ||
                p == "/product" || p.startsWith("/product/") ||
                p == "/apex" || p.startsWith("/apex/")
    }
}
