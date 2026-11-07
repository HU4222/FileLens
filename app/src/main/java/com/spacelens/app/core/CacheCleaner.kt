package com.spacelens.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * Shizuku 缓存清理。普通应用没有清理别的应用缓存的公开 API；
 * 这里只在 Shizuku 已安装且已授权时通过 shell 执行两条受控命令。
 */
object CacheCleaner {

    const val REQUEST_CODE = 4242
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class State { NOT_INSTALLED, NOT_RUNNING, NOT_AUTHORIZED, AUTHORIZED }

    data class Status(
            val state: State,
            val installed: Boolean,
            val running: Boolean,
            val granted: Boolean,
    )

    data class Result(
            val ok: Boolean,
            val message: String,
            val succeeded: Int = 0,
            val failed: Int = 0,
    )

    private const val TRIM = "pm trim-caches 999999999999"
    private const val RM_CACHE_TEMPLATE = "rm -rf /sdcard/Android/data/%s/cache/*"
    private val PKG_RE = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

    fun status(context: Context): Status {
        val installed = isInstalled(context)
        if (!installed) return Status(State.NOT_INSTALLED, false, false, false)
        val running = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!running) return Status(State.NOT_RUNNING, true, false, false)
        val granted = try {
            Shizuku.isPreV11() ||
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        return Status(
                if (granted) State.AUTHORIZED else State.NOT_AUTHORIZED,
                true, true, granted,
        )
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** 必须在有前台 Activity 时调用；结果走 Activity.onRequestPermissionsResult。 */
    fun requestPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11() ||
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return true
            }
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 先让系统回收全局缓存，再对每个应用只清外部 cache 目录。
     * 绝不使用清除应用数据的命令；绝不能删 Android/data/<pkg> 本身。
     */
    fun clearAll(packages: List<String>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val st = statusForShell()
        if (st != null) return st
        val trim = shell(TRIM)
        if (!trim.ok) {
            return Result(false, trim.message, 0, 1)
        }
        val valid = packages.filter { PKG_RE.matches(it) }
        var okCount = 0
        var failCount = 0
        for ((i, pkg) in valid.withIndex()) {
            onProgress(i, valid.size)
            val cmd = String.format(Locale.US, RM_CACHE_TEMPLATE, pkg)
            val r = shell(cmd)
            if (r.ok) okCount++ else failCount++
        }
        onProgress(valid.size, valid.size)
        val msg = trim.message.ifBlank {
            "已执行全局缓存回收；外部缓存成功 $okCount 个，失败 $failCount 个"
        }
        return Result(failCount == 0, msg, okCount, failCount)
    }

    private fun statusForShell(): Result? {
        return try {
            if (!Shizuku.pingBinder()) {
                return Result(false, "Shizuku 未运行，请先打开 Shizuku 并完成激活")
            }
            if (!Shizuku.isPreV11() &&
                    Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return Result(false, "尚未获得 Shizuku 授权")
            }
            null
        } catch (t: Throwable) {
            Result(false, "Shizuku 不可用：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private data class ShellResult(val ok: Boolean, val message: String)

    private fun shell(cmd: String): ShellResult {
        return try {
            val binder = Shizuku.getBinder()
                    ?: return ShellResult(false, "Shizuku binder 不可用")
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = StringBuilder()
            val err = StringBuilder()
            // stdout/stderr 必须并发读取，否则子进程写满任一管道都会互相阻塞。
            val outThread = Thread({
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
                            .bufferedReader().use { out.append(it.readText()) }
                } catch (_: Throwable) {
                }
            }, "FileLens-Shizuku-Out")
            val errThread = Thread({
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
                            .bufferedReader().use { err.append(it.readText()) }
                } catch (_: Throwable) {
                }
            }, "FileLens-Shizuku-Err")
            outThread.start()
            errThread.start()
            outThread.join()
            errThread.join()
            val code = process.waitFor()
            val stdout = out.toString().trim()
            val stderr = err.toString().trim()
            val text = when {
                stdout.isNotEmpty() -> stdout
                stderr.isNotEmpty() -> stderr
                else -> "exit=$code"
            }
            ShellResult(code == 0, text)
        } catch (t: Throwable) {
            ShellResult(false, t.message ?: t.javaClass.simpleName)
        }
    }
}
