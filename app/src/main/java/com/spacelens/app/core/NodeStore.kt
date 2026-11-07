package com.spacelens.app.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.max

/**
 * SoA 节点存储（PRD 5.1）。10 万节点目标常驻 ≤ 12MB。
 * 扫描线程只追加；UI 读取前拿读锁。节点一经写入不再修改，仅 flags 可能补 DENIED / CLUSTER。
 */
class NodeStore(initialCapacity: Int = 65536) {

    companion object {
        const val DIR = 1
        const val FILE = 2
        const val SYMLINK = 4
        const val DENIED = 8
        const val CLUSTER = 16
        const val AGGREGATED = 32

        const val ROOT = 0
    }

    private val lock = ReentrantReadWriteLock()

    private var capacity = initialCapacity.coerceAtLeast(64)
    private var parent = IntArray(capacity) { -1 }
    private var firstChild = IntArray(capacity) { -1 }
    private var nextSibling = IntArray(capacity) { -1 }
    private var size = LongArray(capacity)
    private var mtime = LongArray(capacity)
    private var files = IntArray(capacity)
    private var dirs = IntArray(capacity)
    private var flags = IntArray(capacity)
    private var depth = ByteArray(capacity)
    private var cat = ByteArray(capacity)
    private var catScore = LongArray(capacity * 7)
    private var name = arrayOfNulls<String>(capacity)
    private val namePool = HashMap<String, String>(2048)

    @Volatile
    var count: Int = 0
        private set

    val isEmpty: Boolean get() = count <= 1

    fun reset() {
        lock.writeLock().lock()
        try {
            count = 0
            namePool.clear()
            parent.fill(-1)
            firstChild.fill(-1)
            nextSibling.fill(-1)
            size.fill(0L)
            mtime.fill(0L)
            files.fill(0)
            dirs.fill(0)
            flags.fill(0)
            depth.fill(0)
            cat.fill(0)
            catScore.fill(0L)
            name.fill(null)
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun addRoot(rootName: String, rootMtime: Long): Int {
        lock.writeLock().lock()
        try {
            count = 0
            namePool.clear()
            return addLocked(-1, rootName, DIR, 0L, rootMtime, -1)
        } finally {
            lock.writeLock().unlock()
        }
    }

    /**
     * 追加一个节点。parentIdx 为已提交节点下标。
     * @param cat 文件分类 0..6；目录传 -1。
     */
    fun add(
            parentIdx: Int,
            nodeName: String,
            nodeFlags: Int,
            nodeSize: Long,
            nodeMtime: Long,
            nodeCat: Int,
    ): Int {
        lock.writeLock().lock()
        try {
            return addLocked(parentIdx, nodeName, nodeFlags, nodeSize, nodeMtime, nodeCat)
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun addLocked(
            parentIdx: Int,
            nodeName: String,
            nodeFlags: Int,
            nodeSize: Long,
            nodeMtime: Long,
            nodeCat: Int,
    ): Int {
        ensureCapacity(count + 1)
        val i = count
        parent[i] = parentIdx
        firstChild[i] = -1
        nextSibling[i] = -1
        size[i] = nodeSize.coerceAtLeast(0L)
        mtime[i] = nodeMtime
        files[i] = if (nodeFlags and FILE != 0 || nodeFlags and SYMLINK != 0) 1 else 0
        dirs[i] = if (nodeFlags and DIR != 0) 1 else 0
        flags[i] = nodeFlags
        depth[i] = if (parentIdx >= 0) (depth[parentIdx] + 1).toByte() else 0
        cat[i] = if (nodeCat in 0..6) nodeCat.toByte() else -1
        for (k in 0 until 7) catScore[i * 7 + k] = 0L
        name[i] = if (nodeFlags and DIR != 0) {
            namePool.getOrPut(nodeName) { nodeName }
        } else {
            nodeName
        }

        if (parentIdx >= 0 && parentIdx < count) {
            nextSibling[i] = firstChild[parentIdx]
            firstChild[parentIdx] = i
            var a = parentIdx
            while (a >= 0) {
                if (nodeFlags and DIR != 0) {
                    dirs[a]++
                } else {
                    files[a]++
                    size[a] += size[i]
                    if (nodeCat in 0..6) catScore[a * 7 + nodeCat] += size[i]
                }
                a = parent[a]
            }
        }
        count = i + 1
        return i
    }

    fun markFlag(index: Int, flag: Int) {
        lock.writeLock().lock()
        try {
            if (index in 0 until count) flags[index] = flags[index] or flag
        } finally {
            lock.writeLock().unlock()
        }
    }

    /** 扫描结束后标记微文件集群：≥120 文件且平均 ≤4KB。 */
    fun markClusters() {
        lock.writeLock().lock()
        try {
            for (i in 0 until count) {
                if (flags[i] and DIR == 0) continue
                val f = files[i]
                if (f >= 120 && size[i] / f <= 4096L) {
                    flags[i] = flags[i] or CLUSTER
                }
            }
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun <T> read(block: () -> T): T {
        lock.readLock().lock()
        try {
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    /** 删除成功后把节点体积从祖先链扣除，树图无需重扫即可收缩。 */
    fun markDeleted(indices: IntArray) {
        lock.writeLock().lock()
        try {
            for (i in indices) {
                if (i <= 0 || i >= count) continue
                val s = size[i]
                if (s <= 0L) continue
                val f = files[i]
                val d = dirs[i]
                var a = parent[i]
                while (a >= 0) {
                    size[a] = (size[a] - s).coerceAtLeast(0L)
                    files[a] = (files[a] - f).coerceAtLeast(0)
                    dirs[a] = (dirs[a] - d).coerceAtLeast(0)
                    a = parent[a]
                }
                size[i] = 0L
                files[i] = 0
                dirs[i] = 0
                flags[i] = 0
            }
        } finally {
            lock.writeLock().unlock()
        }
    }
    fun sizeOf(i: Int): Long = read { if (i in 0 until count) size[i] else 0L }
    fun filesOf(i: Int): Int = read { if (i in 0 until count) files[i] else 0 }
    fun dirsOf(i: Int): Int = read { if (i in 0 until count) dirs[i] else 0 }
    fun mtimeOf(i: Int): Long = read { if (i in 0 until count) mtime[i] else 0L }
    fun flagsOf(i: Int): Int = read { if (i in 0 until count) flags[i] else 0 }
    fun parentOf(i: Int): Int = read { if (i in 0 until count) parent[i] else -1 }
    fun depthOf(i: Int): Int = read { if (i in 0 until count) depth[i].toInt() else 0 }
    fun nameOf(i: Int): String = read { if (i in 0 until count) name[i] ?: "" else "" }

    fun categoryOf(i: Int): Int = read {
        if (i !in 0 until count) return@read 6
        if (flags[i] and DIR == 0) return@read cat[i].toInt().coerceIn(0, 6)
        var best = 6
        var bestScore = 0L
        for (k in 0 until 7) {
            val v = catScore[i * 7 + k]
            if (v > bestScore) {
                bestScore = v
                best = k
            }
        }
        best
    }

    fun childCountOf(i: Int): Int = read {
        var n = 0
        var c = if (i in 0 until count) firstChild[i] else -1
        while (c >= 0) {
            n++
            c = nextSibling[c]
        }
        n
    }

    fun childrenOf(i: Int): IntArray = read {
        if (i !in 0 until count) return@read IntArray(0)
        var n = 0
        var c = firstChild[i]
        while (c >= 0) {
            n++
            c = nextSibling[c]
        }
        val out = IntArray(n)
        c = firstChild[i]
        var k = 0
        while (c >= 0) {
            out[k++] = c
            c = nextSibling[c]
        }
        out
    }

    data class NodeInfo(
            val index: Int,
            val name: String,
            val size: Long,
            val files: Int,
            val dirs: Int,
            val flags: Int,
            val mtime: Long,
            val depth: Int,
            val category: Int,
            val children: Int,
    )

    /** 一次锁内拷贝一层子节点，供树图布局，避免绘制时逐节点加锁。 */
    fun childrenInfo(i: Int): List<NodeInfo> = read {
        if (i !in 0 until count) return@read emptyList()
        val out = ArrayList<NodeInfo>()
        var c = firstChild[i]
        while (c >= 0) {
            out.add(NodeInfo(
                    index = c,
                    name = name[c] ?: "",
                    size = size[c],
                    files = files[c],
                    dirs = dirs[c],
                    flags = flags[c],
                    mtime = mtime[c],
                    depth = depth[c].toInt(),
                    category = if (flags[c] and DIR == 0) cat[c].toInt().coerceIn(0, 6)
                    else dominantCategory(c),
                    children = countChildren(c),
            ))
            c = nextSibling[c]
        }
        out
    }

    private fun countChildren(i: Int): Int {
        var n = 0
        var c = firstChild[i]
        while (c >= 0) {
            n++
            c = nextSibling[c]
        }
        return n
    }

    private fun dominantCategory(i: Int): Int {
        var best = 6
        var bestScore = 0L
        for (k in 0 until 7) {
            val v = catScore[i * 7 + k]
            if (v > bestScore) {
                bestScore = v
                best = k
            }
        }
        return best
    }

    /** 分类体积合计，总览四类条用。 */
    fun categoryTotals(): LongArray = read {
        val out = LongArray(7)
        for (i in 0 until count) {
            if (flags[i] and DIR != 0) continue
            val c = cat[i].toInt()
            if (c in 0..6) out[c] += size[i]
        }
        out
    }

    fun pathOf(i: Int): String = read {
        if (i !in 0 until count) return@read ""
        if (i == 0) return@read name[0] ?: ""
        val parts = ArrayList<String>(8)
        var cur = i
        while (cur > 0 && cur < count) {
            parts.add(name[cur] ?: "")
            cur = parent[cur]
            if (parts.size > 64) break
        }
        val rootName = if (count > 0) name[0] ?: "" else ""
        buildString {
            append(rootName)
            for (k in parts.indices.reversed()) {
                append('/')
                append(parts[k])
            }
        }
    }

    /** 相对扫描根目录的路径，不含根名；用于回收站落点与禁止项判定。 */
    fun relativePathOf(i: Int): String = read {
        if (i <= 0 || i >= count) return@read ""
        val parts = ArrayList<String>(8)
        var cur = i
        while (cur > 0 && cur < count) {
            parts.add(name[cur] ?: "")
            cur = parent[cur]
            if (parts.size > 64) break
        }
        buildString {
            for (k in parts.indices.reversed()) {
                if (isNotEmpty()) append('/')
                append(parts[k])
            }
        }
    }

    fun absolutePathOf(rootPath: String, i: Int): String {
        val root = rootPath.trimEnd('/')
        if (i <= 0) return root
        val rel = relativePathOf(i)
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    /** 从 0 到 count 的路径段，面包屑用。 */
    fun chainOf(i: Int): IntArray = read {
        if (i !in 0 until count) return@read IntArray(0)
        val stack = IntArray(64)
        var n = 0
        var cur = i
        while (cur >= 0 && n < stack.size) {
            stack[n++] = cur
            cur = parent[cur]
        }
        val out = IntArray(n)
        for (k in 0 until n) out[k] = stack[n - 1 - k]
        out
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= capacity) return
        val newCap = max(needed, max(capacity + capacity / 2, 64))
        parent = parent.copyOf(newCap).also { if (newCap > capacity) it.fill(-1, capacity, newCap) }
        firstChild = firstChild.copyOf(newCap).also { if (newCap > capacity) it.fill(-1, capacity, newCap) }
        nextSibling = nextSibling.copyOf(newCap).also { if (newCap > capacity) it.fill(-1, capacity, newCap) }
        size = size.copyOf(newCap)
        mtime = mtime.copyOf(newCap)
        files = files.copyOf(newCap)
        dirs = dirs.copyOf(newCap)
        flags = flags.copyOf(newCap)
        depth = depth.copyOf(newCap)
        cat = cat.copyOf(newCap)
        catScore = catScore.copyOf(newCap * 7)
        name = name.copyOf(newCap)
        capacity = newCap
    }

    // ------------------------------------------------------------ 快照

    fun writeTo(out: DataOutputStream) {
        lock.readLock().lock()
        try {
            val pool = LinkedHashMap<String, Int>(2048)
            for (i in 0 until count) {
                val n = name[i] ?: ""
                if (!pool.containsKey(n)) pool[n] = pool.size
            }
            out.writeInt(pool.size)
            for (key in pool.keys) {
                val bytes = key.toByteArray(Charsets.UTF_8)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeInt(count)
            val ids = IntArray(count) { pool[name[it] ?: ""] ?: 0 }
            writeInts(out, ids)
            writeInts(out, parent, count)
            writeInts(out, firstChild, count)
            writeInts(out, nextSibling, count)
            writeLongs(out, size, count)
            writeLongs(out, mtime, count)
            writeInts(out, files, count)
            writeInts(out, dirs, count)
            writeInts(out, flags, count)
            out.write(depth, 0, count)
            out.write(cat, 0, count)
            writeLongs(out, catScore, count * 7)
        } finally {
            lock.readLock().unlock()
        }
    }

    fun readFrom(inp: DataInputStream): Int {
        lock.writeLock().lock()
        try {
            val poolSize = inp.readInt()
            if (poolSize < 0 || poolSize > 2_000_000) throw IllegalStateException("name pool 异常：$poolSize")
            val pool = arrayOfNulls<String>(poolSize)
            for (i in 0 until poolSize) {
                val len = inp.readInt()
                if (len < 0 || len > 1_000_000) throw IllegalStateException("名称长度异常：$len")
                val bytes = ByteArray(len)
                inp.readFully(bytes)
                pool[i] = String(bytes, Charsets.UTF_8)
            }
            val n = inp.readInt()
            if (n <= 0 || n > 3_000_000) throw IllegalStateException("节点数异常：$n")
            count = 0
            capacity = 0
            ensureCapacity(n)
            val ids = readInts(inp, n)
            for (i in 0 until n) {
                val id = ids[i]
                name[i] = if (id in 0 until poolSize) pool[id] else ""
            }
            System.arraycopy(readInts(inp, n), 0, parent, 0, n)
            System.arraycopy(readInts(inp, n), 0, firstChild, 0, n)
            System.arraycopy(readInts(inp, n), 0, nextSibling, 0, n)
            System.arraycopy(readLongs(inp, n), 0, size, 0, n)
            System.arraycopy(readLongs(inp, n), 0, mtime, 0, n)
            System.arraycopy(readInts(inp, n), 0, files, 0, n)
            System.arraycopy(readInts(inp, n), 0, dirs, 0, n)
            System.arraycopy(readInts(inp, n), 0, flags, 0, n)
            val depthBytes = ByteArray(n)
            inp.readFully(depthBytes)
            System.arraycopy(depthBytes, 0, depth, 0, n)
            val catBytes = ByteArray(n)
            inp.readFully(catBytes)
            System.arraycopy(catBytes, 0, cat, 0, n)
            System.arraycopy(readLongs(inp, n * 7), 0, catScore, 0, n * 7)
            count = n
            return n
        } finally {
            lock.writeLock().unlock()
        }
    }

    private fun writeInts(out: DataOutputStream, arr: IntArray, n: Int = arr.size) {
        val bytes = ByteArray(n * 4)
        val bb = java.nio.ByteBuffer.wrap(bytes)
        bb.asIntBuffer().put(arr, 0, n)
        out.write(bytes)
    }

    private fun writeLongs(out: DataOutputStream, arr: LongArray, n: Int = arr.size) {
        val bytes = ByteArray(n * 8)
        val bb = java.nio.ByteBuffer.wrap(bytes)
        bb.asLongBuffer().put(arr, 0, n)
        out.write(bytes)
    }

    private fun readInts(inp: DataInputStream, n: Int): IntArray {
        val bytes = ByteArray(n * 4)
        inp.readFully(bytes)
        val out = IntArray(n)
        java.nio.ByteBuffer.wrap(bytes).asIntBuffer().get(out)
        return out
    }

    private fun readLongs(inp: DataInputStream, n: Int): LongArray {
        val bytes = ByteArray(n * 8)
        inp.readFully(bytes)
        val out = LongArray(n)
        java.nio.ByteBuffer.wrap(bytes).asLongBuffer().get(out)
        return out
    }

    fun estimatedBytes(): Long = capacity.toLong() * (4 * 3 + 8 * 2 + 4 * 4 + 1 + 1 + 4) +
            capacity.toLong() * 7 * 8 + capacity.toLong() * 8
}
