package com.spacelens.app.bench

import android.content.Context
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * M4 六项测速（串行）：顺序写 / 顺序读 / 4K 随机读 / 4K 随机写 / 元数据 / fsync。
 * 只写应用专属外部目录 `getExternalFilesDir(null)/bench/`，结束清理；不碰用户文件。
 */
object BenchEngine {

    data class Result(
            val seqWriteMb: Double,
            val seqReadMb: Double,
            val randReadIops: Double,
            val randReadMb: Double,
            val randReadQd32Iops: Double,
            val randWriteIops: Double,
            val metaFiles: Double,
            val fsyncAvgMs: Double,
            val fsyncP95Ms: Double,
            val writeBytes: Long,
            val cacheNote: String,
            val grade: String,
            val weakest: String,
            val elapsedMs: Long,
    )

    data class Progress(val index: Int, val label: String, val fraction: Float)

    interface Listener {
        fun onProgress(p: Progress)
        fun onFinished(r: Result)
        fun onError(message: String)
    }

    @Volatile private var cancelled = false
    @Volatile private var thread: Thread? = null

    private const val MB = 1024 * 1024
    private const val FILE_MB = 128
    private const val RANDOM_OPS = 2000
    private const val META_FILES = 300
    private const val FSYNC_OPS = 32

    fun run(context: Context, listener: Listener) {
        if (thread?.isAlive == true) return
        cancelled = false
        thread = Thread({
            try {
                val dir = File(context.getExternalFilesDir(null), "bench")
                dir.mkdirs()
                clear(dir)
                val free = dir.usableSpace
                val sizeMb = if (free < 1536L * MB) 64 else FILE_MB
                val r = runInternal(dir, sizeMb, listener)
                clear(dir)
                listener.onFinished(r)
            } catch (t: Throwable) {
                listener.onError(t.message ?: "测速失败")
            }
        }, "SpaceLens-Bench").also { it.start() }
    }

    fun cancel() {
        cancelled = true
        thread?.interrupt()
    }

    private fun runInternal(dir: File, sizeMb: Int, l: Listener): Result {
        val total = 6
        val t0 = System.nanoTime()
        var writeBytes = 0L
        val file = File(dir, "seq.bin")
        val raf = RandomAccessFile(file, "rw")
        val ch = raf.channel
        val buf = ByteBuffer.allocateDirect(MB)
        val chunk = ByteArray(MB)
        for (i in 0 until MB) chunk[i] = (i and 0xFF).toByte()
        buf.put(chunk)
        buf.flip()

        // 1 顺序写
        l.onProgress(Progress(0, "顺序写", 0f))
        val wStart = System.nanoTime()
        var written = 0
        while (written < sizeMb * MB && !cancelled) {
            buf.clear()
            ch.write(buf)
            written += MB
            if (written % (8 * MB) == 0) ch.force(false)
            l.onProgress(Progress(0, "顺序写", written.toFloat() / (sizeMb * MB)))
        }
        ch.force(false)
        val seqWrite = mbPerSec(written.toLong(), System.nanoTime() - wStart)
        writeBytes += written.toLong()

        // 2 顺序读（posix_fadvise 清页缓存）
        l.onProgress(Progress(1, "顺序读", 0f))
        var cacheNote = ""
        var cleared = true
        try {
            val m = Os::class.java.getMethod("posix_fadvise", java.io.FileDescriptor::class.java,
                    Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.invoke(null, raf.fd, 0L, 0L, 4)
        } catch (t: Throwable) {
            cleared = false
            cacheNote = "含页缓存（DONTNEED 不可用，取第二遍）"
        }
        var read = 0L
        var rStart = System.nanoTime()
        fun readOnce() {
            read = 0L
            ch.position(0)
            while (read < written && !cancelled) {
                buf.clear()
                val n = ch.read(buf)
                if (n <= 0) break
                read += n
                l.onProgress(Progress(1, "顺序读", read.toFloat() / written))
            }
        }
        readOnce()
        if (!cleared && !cancelled) {
            rStart = System.nanoTime()
            readOnce()
        }
        val seqRead = mbPerSec(read, System.nanoTime() - rStart)

        // 3 4K 随机读 QD1
        l.onProgress(Progress(2, "4K 随机读", 0f))
        val rndRead = ByteBuffer.allocateDirect(4096)
        var rr = 0
        val rrStart = System.nanoTime()
        for (i in 0 until RANDOM_OPS) {
            if (cancelled) break
            val pos = (Math.random() * max(1, written - 4096)).toLong()
            rndRead.clear()
            ch.read(rndRead, pos)
            rr++
            if (i % 100 == 0) l.onProgress(Progress(2, "4K 随机读", i.toFloat() / RANDOM_OPS))
        }
        val rrSec = max((System.nanoTime() - rrStart) / 1e9, 0.0001)
        val randReadIops = rr / rrSec
        val randReadMb = rr * 4096.0 / MB / rrSec

        // 3b 4K 随机读 QD32（8 线程 × 250 次）
        l.onProgress(Progress(2, "4K 随机读 QD32", 0f))
        val qdStart = System.nanoTime()
        val qdCount = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        for (t in 0 until 8) {
            pool.execute {
                val b = ByteBuffer.allocateDirect(4096)
                for (k in 0 until 250) {
                    if (cancelled) break
                    val pos = (Math.random() * max(1, written - 4096)).toLong()
                    b.clear()
                    try { ch.read(b, pos) } catch (_: Throwable) {}
                    qdCount.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)
        val qdSec = max((System.nanoTime() - qdStart) / 1e9, 0.0001)
        val qd32 = qdCount.get() / qdSec

        // 4 4K 随机写 QD1
        l.onProgress(Progress(3, "4K 随机写", 0f))
        val rndWrite = ByteBuffer.allocateDirect(4096)
        var rw = 0
        val rwStart = System.nanoTime()
        for (i in 0 until RANDOM_OPS) {
            if (cancelled) break
            val pos = (Math.random() * max(1, written - 4096)).toLong()
            rndWrite.clear()
            rndWrite.putInt(i)
            rndWrite.flip()
            ch.write(rndWrite, pos)
            rw++
            writeBytes += 4096
            if (i % 200 == 0) ch.force(false)
            if (i % 100 == 0) l.onProgress(Progress(3, "4K 随机写", i.toFloat() / RANDOM_OPS))
        }
        ch.force(false)
        val rwSec = max((System.nanoTime() - rwStart) / 1e9, 0.0001)
        val randWriteIops = rw / rwSec

        // 5 元数据
        l.onProgress(Progress(4, "元数据", 0f))
        val metaStart = System.nanoTime()
        var made = 0
        for (i in 0 until META_FILES) {
            if (cancelled) break
            val f = File(dir, "m$i")
            if (f.createNewFile()) made++
            f.length()
            if (i % 20 == 0) l.onProgress(Progress(4, "元数据", i.toFloat() / META_FILES))
        }
        val metaSec = max((System.nanoTime() - metaStart) / 1e9, 0.0001)
        val meta = made / metaSec
        for (i in 0 until META_FILES) File(dir, "m$i").delete()

        // 6 fsync 延迟
        l.onProgress(Progress(5, "fsync 延迟", 0f))
        val fsyncFile = File(dir, "fsync.bin")
        val fr = RandomAccessFile(fsyncFile, "rw")
        val fb = ByteBuffer.allocateDirect(4096)
        val times = ArrayList<Double>(FSYNC_OPS)
        for (i in 0 until FSYNC_OPS) {
            if (cancelled) break
            fb.clear(); fb.putInt(i); fb.flip()
            fr.channel.write(fb, 0)
            val s = System.nanoTime()
            fr.channel.force(true)
            times.add((System.nanoTime() - s) / 1e6)
            if (i % 4 == 0) l.onProgress(Progress(5, "fsync 延迟", i.toFloat() / FSYNC_OPS))
        }
        fr.close()
        val avg = if (times.isEmpty()) 0.0 else times.average()
        val p95 = if (times.isEmpty()) 0.0 else times.sorted()[((times.size - 1) * 0.95).toInt()]

        raf.close()
        val grade = BenchGrade.grade(seqWrite)
        val weakest = BenchGrade.weakest(seqWrite, seqRead, randReadIops, randWriteIops, meta, avg)
        return Result(seqWrite, seqRead, randReadIops, randReadMb, qd32, randWriteIops, meta,
                avg, p95, writeBytes, cacheNote, grade, weakest,
                (System.nanoTime() - t0) / 1_000_000L)
    }

    private fun mbPerSec(bytes: Long, nanos: Long): Double {
        val sec = max(nanos / 1e9, 0.0001)
        return bytes / MB / sec
    }

    private fun clear(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
