package com.spacelens.app.core

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 紧凑二进制快照：
 * magic + version + 根路径 + 保存时间 + partial + 节点数 + UTF-8 名池 + 定长字段数组。
 * 只写应用私有 filesDir；直接覆盖目标文件，失败时截断为 0，加载侧用 magic/count 校验兜底。
 * 10 万节点目标读回 ≤ 300ms（批量 ByteBuffer I/O）。
 */
object SnapshotStore {

    private const val MAGIC = 0x534C4E53 // SLNS
    private const val VERSION = 1

    data class Meta(val rootPath: String, val savedAt: Long, val partial: Boolean, val nodes: Int)

    fun fileFor(context: Context): File = File(context.filesDir, "scan-latest.bin")

    fun save(context: Context, store: NodeStore, rootPath: String, partial: Boolean): Meta? {
        if (store.count <= 0) return null
        val target = fileFor(context)
        val now = System.currentTimeMillis()
        return try {
            FileOutputStream(target).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos, 1 shl 16))
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(rootPath)
                out.writeLong(now)
                out.writeBoolean(partial)
                store.writeTo(out)
                out.flush()
                fos.fd.sync()
            }
            Meta(rootPath, now, partial, store.count)
        } catch (t: Throwable) {
            truncate(target)
            null
        }
    }

    fun load(context: Context, store: NodeStore): Meta? {
        val file = fileFor(context)
        if (!file.isFile || file.length() < 24) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                val magic = inp.readInt()
                if (magic != MAGIC) {
                    truncate(file)
                    return null
                }
                val version = inp.readInt()
                if (version != VERSION) {
                    truncate(file)
                    return null
                }
                val rootPath = inp.readUTF()
                val savedAt = inp.readLong()
                val partial = inp.readBoolean()
                val nodes = store.readFrom(inp)
                Meta(rootPath, savedAt, partial, nodes)
            }
        } catch (t: Throwable) {
            store.reset()
            truncate(file)
            null
        }
    }

    /** 逻辑清理：截断为 0 而不是删除用户数据；文件只在应用私有目录。 */
    fun delete(context: Context): Long {
        val f = fileFor(context)
        val len = if (f.exists()) f.length() else 0L
        truncate(f)
        return len
    }

    private fun truncate(file: File) {
        try {
            if (file.exists()) {
                RandomAccessFile(file, "rw").use { it.setLength(0) }
            }
        } catch (_: Throwable) {
        }
    }
}
