package com.spacelens.app.core

import android.content.Context
import android.os.Environment
import android.util.Log
import com.spacelens.app.data.Prefs
import java.io.File

/**
 * 传输接收目录：默认 Download/FileLens，可在「我的」里自选。
 * 存的绝对路径；每次使用前都重新校验存在+可写，坏掉就回默认，不让传输失败。
 */
object RecvDir {

    private const val TAG = "FileLensRecv"
    private const val PROBE = ".fl_probe"

    fun defaultDir(): File = File(Environment.getExternalStorageDirectory(), "Download/FileLens")

    fun stored(context: Context): File? = Prefs(context).recvDir?.let { File(it) }

    fun usable(dir: File): Boolean = dir.isDirectory && dir.canWrite()

    /** 当前真正使用的目录；自定义不可用时静默回默认（提示由调用方负责）。 */
    fun resolve(context: Context): File {
        val custom = stored(context)
        if (custom != null && usable(custom)) return custom
        val def = defaultDir()
        if (!def.exists() && !def.mkdirs()) {
            Log.e(TAG, "cannot create default recv dir: ${def.absolutePath}")
        }
        return def
    }

    fun needsFallback(context: Context): Boolean {
        val custom = stored(context) ?: return false
        return !usable(custom)
    }

    fun clear(context: Context) {
        Prefs(context).recvDir = null
    }

    fun save(context: Context, dir: File) {
        Prefs(context).recvDir = dir.absolutePath
    }

    /**
     * 可写性探针：写一个 .fl_probe 再删掉。
     * 仅用于可写性探测——这是本版工程里唯一允许 File.delete() 的地方。
     */
    fun probe(dir: File): String? {
        if (!dir.isDirectory) return "目录不存在"
        if (!dir.canWrite()) return "这个目录不允许写入，换一个试试"
        val probeFile = File(dir, PROBE)
        var error: String? = null
        try {
            probeFile.writeText("FileLens probe", Charsets.UTF_8)
            if (!probeFile.isFile) error = "这个目录不允许写入，换一个试试"
        } catch (t: Throwable) {
            error = "这个目录不允许写入，换一个试试（${t.javaClass.simpleName}）"
        } finally {
            // 仅用于可写性探测：删掉探针文件本身，不碰任何用户文件
            if (probeFile.exists() && !probeFile.delete()) {
                Log.w(TAG, "probe file delete failed: ${probeFile.absolutePath}")
            }
        }
        return error
    }
}
