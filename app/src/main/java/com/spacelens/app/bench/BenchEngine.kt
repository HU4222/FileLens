package com.spacelens.app.bench

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * 顺序读写测速：按时间跑满，前一半写、后一半读。
 * 只写 `context.cacheDir/bench/`；取消/异常也会清空。
 *
 * 原来的 4K 随机读写 / 8 线程 QD32 / 元数据 / fsync 代码已不参与本流程；
 * 5/10 秒成品只测顺序写、顺序读。
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

    data class Progress(
            val phase: String,
            val instantMBps: Double,
            val elapsedMs: Long,
            val totalMs: Long,
            val fraction: Float,
    )

    interface Listener {
        fun onProgress(p: Progress)
        fun onFinished(r: Result)
        fun onError(message: String)
    }

    @Volatile private var cancelled = false
    @Volatile private var thread: Thread? = null

    private const val MB = 1024 * 1024
    private const val MAX_WRITE_BYTES = 4L * 1024 * 1024 * 1024

    fun run(context: Context, durationMs: Long, listener: Listener) {
        if (thread?.isAlive == true) return
        cancelled = false
        val totalMs = durationMs.coerceIn(5000L, 10000L)
        thread = Thread({
            val dir = File(context.cacheDir, "bench")
            try {
                dir.mkdirs()
                clear(dir)
                listener.onFinished(runTimed(dir, totalMs, listener))
            } catch (_: CancellationException) {
                // 用户取消：UI 已由 onCancel 收口，不再发“失败”提示。
            } catch (t: Throwable) {
                if (!cancelled) listener.onError(t.message ?: "测速失败")
            } finally {
                try {
                    clear(dir)
                } catch (_: Throwable) {
                }
            }
        }, "FileLens-Bench").also { it.start() }
    }

    fun cancel() {
        cancelled = true
        thread?.interrupt()
    }

    private fun runTimed(dir: File, totalMs: Long, l: Listener): Result {
        val available = try {
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Throwable) {
            0L
        }
        if (available < 4L * MB) throw IllegalStateException("可用空间不足，无法测速")
        // 单次最多使用可用空间的 30%，且不超过 4GB，避免为了跑满时长写爆存储。
        val writeCap = min((available * 0.3).toLong(), MAX_WRITE_BYTES)
        // 顺序写 40% / 顺序读 40% / 4K 随机读写 20%
        val writeMs = totalMs * 40L / 100L
        val readMs = totalMs * 40L / 100L
        val randomMs = totalMs - writeMs - readMs
        val allStart = SystemClock.elapsedRealtime()

        val file = File(dir, "seq.bin")
        val raf = RandomAccessFile(file, "rw")
        try {
        val ch = raf.channel
        val buf = ByteBuffer.allocateDirect(MB)
        val chunk = ByteArray(MB)
        for (i in 0 until MB) chunk[i] = (i and 0xFF).toByte()
        var written = 0L
        var spaceLimited = false

        // 写入阶段
        l.onProgress(Progress("正在写入…", 0.0, 0L, totalMs, 0f))
        val writeStart = SystemClock.elapsedRealtime()
        val writeWindow = SpeedWindow(writeStart)
        var writeReportAt = writeStart
        while (!cancelled) {
            val now = SystemClock.elapsedRealtime()
            if (now - writeStart >= writeMs) break
            if (written + MB > writeCap) {
                spaceLimited = true
                break
            }
            buf.clear()
            var ok = true
            while (buf.hasRemaining()) {
                if (ch.write(buf) < 0) {
                    ok = false
                    break
                }
            }
            if (!ok) break
            written += MB
            writeWindow.add(now, written)
            if (now - writeReportAt >= 200L) {
                writeReportAt = now
                report(l, "正在写入…", writeWindow.instant(now), allStart, totalMs, now)
            }
        }
        if (!cancelled) {
            try {
                ch.force(false)
            } catch (_: Throwable) {
            }
        }
        val writeEnd = SystemClock.elapsedRealtime()
        val seqWrite = mbPerSec(written, writeEnd - writeStart)
        if (spaceLimited) {
            l.onProgress(Progress("写入受空间限制", 0.0, writeEnd - allStart, totalMs,
                    fraction(writeEnd - allStart, totalMs)))
        }

        // 读取阶段：循环读刚写的文件，跑满剩余一半时间。
        l.onProgress(Progress("正在读取…", 0.0, writeEnd - allStart, totalMs,
                fraction(writeEnd - allStart, totalMs)))
        val readStart = SystemClock.elapsedRealtime()
        val readWindow = SpeedWindow(readStart)
        var readTotal = 0L
        var readReportAt = readStart
        ch.position(0)
        while (!cancelled) {
            val now = SystemClock.elapsedRealtime()
            if (now - readStart >= readMs) break
            if (written <= 0L) break
            buf.clear()
            val n = ch.read(buf)
            if (n <= 0) {
                ch.position(0)
                continue
            }
            readTotal += n
            readWindow.add(now, readTotal)
            if (now - readReportAt >= 200L) {
                readReportAt = now
                report(l, "正在读取…", readWindow.instant(now), allStart, totalMs, now)
            }
        }
        val readEnd = SystemClock.elapsedRealtime()
        val seqRead = mbPerSec(readTotal, readEnd - readStart)

        // 第三阶段：4KB 块随机读写，读写交替；只测 IOPS，不替代顺序大文件测试。
        val randomStart = SystemClock.elapsedRealtime()
        val randomWindow = SpeedWindow(randomStart)
        var randomOps = 0L
        var randomBytes = 0L
        var randomReportAt = randomStart
        if (written >= 4096L) {
            val rnd = ByteBuffer.allocateDirect(4096)
            while (!cancelled) {
                val now = SystemClock.elapsedRealtime()
                if (now - randomStart >= randomMs) break
                val pos = (Math.random() * max(1L, written - 4096L)).toLong()
                rnd.clear()
                if (randomOps % 2L == 0L) {
                    ch.read(rnd, pos)
                } else {
                    rnd.putInt((randomOps and 0x7FFFFFFF).toInt())
                    rnd.flip()
                    ch.write(rnd, pos)
                }
                randomOps++
                randomBytes += 4096
                randomWindow.add(now, randomBytes)
                if (now - randomReportAt >= 200L) {
                    randomReportAt = now
                    report(l, "正在 4K 随机读写…", randomWindow.instant(now),
                            allStart, totalMs, now)
                }
            }
        }
        val randomEnd = SystemClock.elapsedRealtime()
        val randomSec = max((randomEnd - randomStart) / 1000.0, 0.001)
        val randomIops = randomOps / randomSec
        val randomMb = randomBytes / MB / randomSec
        if (cancelled) throw CancellationException("cancelled")

        val elapsed = randomEnd - allStart
        val grade = BenchGrade.grade(seqWrite)
        val weakest = if (seqWrite <= seqRead) "顺序写" else "顺序读"
        val note = if (spaceLimited) "受可用空间限制，写入阶段提前结束" else ""
        return Result(
                seqWrite, seqRead,
                randomIops, randomMb, 0.0, 0.0, 0.0, 0.0, 0.0,
                written, note, grade, weakest, elapsed,
        )
        } finally {
            try {
                raf.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun report(
            l: Listener,
            phase: String,
            instant: Double,
            allStart: Long,
            totalMs: Long,
            now: Long,
    ) {
        val elapsed = now - allStart
        l.onProgress(Progress(phase, instant, elapsed, totalMs, fraction(elapsed, totalMs)))
    }

    private fun fraction(elapsed: Long, total: Long): Float =
            (elapsed.toFloat() / total.coerceAtLeast(1L)).coerceIn(0f, 1f)

    private fun mbPerSec(bytes: Long, millis: Long): Double {
        val sec = max(millis / 1000.0, 0.001)
        return bytes / MB / sec
    }

    /** 最近 1 秒的滑动窗口；记录 (时间, 累计字节)。 */
    private class SpeedWindow(now: Long) {
        private val times = java.util.ArrayDeque<Long>()
        private val totals = java.util.ArrayDeque<Long>()

        init {
            times.add(now)
            totals.add(0L)
        }

        fun add(now: Long, total: Long) {
            times.add(now)
            totals.add(total)
        }

        fun instant(now: Long): Double {
            while (times.size > 1 && now - times.first > 1000L) {
                times.removeFirst()
                totals.removeFirst()
            }
            val startTime = times.first
            val startBytes = totals.first
            val endBytes = totals.last
            val windowSec = max((now - startTime) / 1000.0, 0.001)
            return (endBytes - startBytes) / MB / windowSec
        }
    }

    private fun clear(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
