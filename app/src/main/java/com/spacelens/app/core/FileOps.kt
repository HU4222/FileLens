package com.spacelens.app.core

import android.os.Environment
import java.io.File

/**
 * 原生文件操作的最小实现：只做 重命名 / 移动 / 新建文件夹。
 * 不删除、不复制；移动失败不退化复制+删除（避免引入删除路径）。
 */
object FileOps {

    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    sealed class NameResult {
        object Ok : NameResult()
        object Same : NameResult()
        data class Bad(val message: String) : NameResult()
    }

    data class Result(val ok: Boolean, val message: String)

    /** 名字校验（空/点/非法字符/首尾空格/同名）。目标是否已存在由 [checkTarget] 判。 */
    fun checkName(raw: String, originalName: String?): NameResult {
        val name = raw.trim()
        if (name.isEmpty()) return NameResult.Bad("名字不能为空")
        if (name == "." || name == "..") return NameResult.Bad("名字不能是 . 或 ..")
        for (ch in ILLEGAL) {
            if (name.contains(ch)) return NameResult.Bad("名字不能包含字符 \"$ch\"")
        }
        if (originalName != null && name == originalName) return NameResult.Same
        return NameResult.Ok
    }

    fun checkTarget(dir: File, name: String): String? =
            if (File(dir, name).exists()) "同目录下已有同名文件" else null

    /** 目标目录就是源所在目录：按任务书要求直接关闭，不提示也不操作。 */
    fun sameDir(src: File, targetDir: File): Boolean =
            src.parentFile?.let { samePath(it, targetDir) } ?: false

    fun rename(file: File, newName: String): Result {
        if (!file.exists()) return Result(false, "文件不存在")
        val parent = file.parentFile
        val dest = File(parent, newName)
        if (dest.exists() && dest.absolutePath != file.absolutePath) {
            return Result(false, "同目录下已有同名文件")
        }
        if (!file.renameTo(dest)) {
            val why = if (parent != null && !parent.canWrite()) {
                "这个目录不允许改名，可能需要授权"
            } else {
                "改名失败（renameTo 返回 false）"
            }
            return Result(false, why)
        }
        return Result(true, "已重命名为 $newName")
    }

    fun move(src: File, targetDir: File): Result {
        if (!src.exists()) return Result(false, "源文件不存在")
        if (!targetDir.isDirectory) return Result(false, "目标不是目录")
        val parent = src.parentFile
        if (parent != null && samePath(parent, targetDir)) {
            return Result(false, "目标就是当前目录")
        }
        val srcPath = src.absolutePath.trimEnd('/')
        val dstPath = targetDir.absolutePath.trimEnd('/')
        if (dstPath == srcPath || dstPath.startsWith("$srcPath/")) {
            return Result(false, "不能把文件夹移到它自己里面")
        }
        val dest = File(targetDir, src.name)
        if (dest.exists()) return Result(false, "目标位置已有同名项")
        if (!sameVolume(src, targetDir)) return Result(false, "不能跨存储卡移动")
        if (!src.renameTo(dest)) return Result(false, "移动失败（renameTo 返回 false）")
        return Result(true, "已移动到 ${targetDir.absolutePath}")
    }

    fun newFolder(dir: File, name: String): Result {
        val target = File(dir, name)
        if (target.exists()) return Result(false, "同目录下已有同名文件")
        if (!target.mkdirs()) return Result(false, "新建文件夹失败（目录可能不可写）")
        return Result(true, "已新建文件夹 $name")
    }

    private fun canonical(f: File): String = try {
        f.canonicalPath
    } catch (_: Throwable) {
        f.absolutePath
    }

    private fun samePath(a: File, b: File): Boolean = canonical(a) == canonical(b)

    /** 只接受内部存储卷内的移动；跨卷 renameTo 会失败，直接拒绝而不是退化复制+删除。 */
    private fun sameVolume(a: File, b: File): Boolean {
        val root = try {
            Environment.getExternalStorageDirectory().canonicalPath
        } catch (_: Throwable) {
            Environment.getExternalStorageDirectory().absolutePath
        }.trimEnd('/')
        val pa = canonical(a)
        val pb = canonical(b)
        val inA = pa == root || pa.startsWith("$root/")
        val inB = pb == root || pb.startsWith("$root/")
        return inA == inB
    }
}
