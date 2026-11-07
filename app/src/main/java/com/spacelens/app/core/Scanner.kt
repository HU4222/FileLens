package com.spacelens.app.core

import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * 单线程 DFS 扫描器（PRD 5.2）：
 * 三档令牌桶限速 + 50ms 时间片；只枚举目录与 length()；符号链接不跟随；
 * 权限异常记 DENIED 不中断；每 100ms / 2000 项回主线程报进度；可中止。
 */
class Scanner(
        private val store: NodeStore,
        private val rootFile: File,
        private val rateMode: RateMode,
        private val listener: Listener,
) {

    interface Listener {
        fun onProgress(progress: ScanProgress)
        fun onFinished(summary: ScanSummary)
    }

    data class ScanProgress(
            val files: Int,
            val dirs: Int,
            val bytes: Long,
            val currentPath: String,
            val elapsedMs: Long,
            val denied: Int,
            val items: Int,
    )

    private val stop = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val startedAt = System.currentTimeMillis()
    private val limiter = RateLimiter(rateMode.perSecond)
    private var denied = 0
    private var items = 0
    private var lastPublish = System.currentTimeMillis()
    private var lastPublishItems = 0
    private var rootIndex = -1
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        thread = Thread({ run() }, "SpaceLens-Scan").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    fun cancel() {
        stop.set(true)
    }

    private fun run() {
        val rootName = friendlyRootName(rootFile)
        rootIndex = store.addRoot(rootName, safeMtime(rootFile))
        val stack = ArrayDeque<DirTask>()
        stack.push(DirTask(rootFile, rootIndex))
        val t0 = System.nanoTime()

        while (stack.isNotEmpty() && !stop.get()) {
            val task = stack.pop()
            if (Classifier.restrictedPath(task.dir.absolutePath)) {
                store.markFlag(task.node, NodeStore.DENIED)
                denied++
                publish(task.dir.absolutePath, force = false)
                continue
            }
            val children = try {
                task.dir.listFiles()
            } catch (t: SecurityException) {
                null
            } catch (t: Throwable) {
                null
            }
            if (children == null) {
                store.markFlag(task.node, NodeStore.DENIED)
                denied++
                publish(task.dir.absolutePath, force = false)
                continue
            }
            if (children.isEmpty()) {
                publish(task.dir.absolutePath, force = false)
                continue
            }
            for (child in children) {
                if (stop.get()) break
                val name = child.name ?: continue
                val path = child.absolutePath
                val isLink = isSymlink(path)
                val isDir = !isLink && try {
                    child.isDirectory
                } catch (t: Throwable) {
                    false
                }
                val flags: Int
                val size: Long
                val cat: Int
                if (isLink) {
                    flags = NodeStore.SYMLINK
                    size = 0L
                    cat = Classifier.OTHER
                } else if (isDir) {
                    flags = NodeStore.DIR
                    size = 0L
                    cat = -1
                } else {
                    flags = NodeStore.FILE
                    size = safeLength(child)
                    cat = Classifier.categoryOf(name, flags)
                }
                val idx = store.add(
                        parentIdx = task.node,
                        nodeName = name,
                        nodeFlags = flags,
                        nodeSize = size,
                        nodeMtime = if (isDir) safeMtime(child) else 0L,
                        nodeCat = cat,
                )
                items++
                if (isDir) stack.push(DirTask(child, idx))
                limiter.acquire()
                val now = System.currentTimeMillis()
                if (now - lastPublish >= 100L || items - lastPublishItems >= 2000) {
                    publish(path, force = true)
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastPublish >= 100L) publish(task.dir.absolutePath, force = false)
        }

        store.markClusters()
        val partial = stop.get()
        val summary = ScanSummary(
                rootPath = rootFile.absolutePath,
                rootName = rootName,
                files = store.filesOf(0),
                dirs = store.dirsOf(0),
                bytes = store.sizeOf(0),
                elapsedMs = (System.nanoTime() - t0) / 1_000_000L,
                finishedAt = System.currentTimeMillis(),
                partial = partial,
                denied = denied,
                rateMode = rateMode.stored,
        )
        main.post { listener.onFinished(summary) }
        thread = null
    }

    private fun publish(path: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 100L) return
        lastPublish = now
        lastPublishItems = items
        val progress = ScanProgress(
                files = store.filesOf(0),
                dirs = store.dirsOf(0),
                bytes = store.sizeOf(0),
                currentPath = path,
                elapsedMs = max(0L, now - startedAt),
                denied = denied,
                items = items,
        )
        main.post { listener.onProgress(progress) }
    }

    private fun isSymlink(path: String): Boolean = try {
        val st = Os.lstat(path)
        OsConstants.S_ISLNK(st.st_mode)
    } catch (t: Throwable) {
        false
    }

    private fun safeLength(f: File): Long = try {
        f.length().coerceAtLeast(0L)
    } catch (t: Throwable) {
        0L
    }

    private fun safeMtime(f: File): Long = try {
        f.lastModified()
    } catch (t: Throwable) {
        0L
    }

    private fun friendlyRootName(f: File): String {
        val path = f.absolutePath
        return when {
            path == "/storage/emulated/0" -> "内部存储"
            path == "/sdcard" -> "内部存储"
            else -> f.name.ifEmpty { "存储" }
        }
    }

    private data class DirTask(val dir: File, val node: Int)

    /** 令牌桶 + 50ms 时间片，片间 sleep 让出 CPU。 */
    private class RateLimiter(perSecond: Int) {
        private val rate = perSecond.toDouble()
        private val burst = if (perSecond <= 0) 0.0 else max(32.0, perSecond / 10.0)
        private var tokens = burst
        private var lastNanos = System.nanoTime()
        private var sliceStart = System.nanoTime()

        fun acquire() {
            if (rate > 0.0) {
                while (true) {
                    val now = System.nanoTime()
                    tokens = min(burst, tokens + (now - lastNanos) / 1_000_000_000.0 * rate)
                    lastNanos = now
                    if (tokens >= 1.0) {
                        tokens -= 1.0
                        break
                    }
                    val needMs = ((1.0 - tokens) / rate * 1000.0).toLong().coerceIn(1L, 50L)
                    try {
                        Thread.sleep(needMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
            val now = System.nanoTime()
            if (now - sliceStart > 50_000_000L) {
                sliceStart = now
                try {
                    Thread.sleep(1L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}
