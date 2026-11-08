package com.spacelens.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.spacelens.app.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志：只写本机 files/crash，最多 10 份，不联网、不上传。
 * 写文件必须同步并 fsync：进程马上会被系统杀掉，异步写会留下空文件。
 */
object CrashLog {

    private const val TAG = "FileLensCrash"
    private const val DIR = "crash"
    private const val MAX_FILES = 10

    @Volatile private var installed = false
    private var appContext: Context? = null

    data class Entry(val file: File, val time: String, val exception: String)

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        appContext = app
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (t2: Throwable) {
                Log.e(TAG, "write crash log failed", t2)
            } finally {
                // 必须转给原 handler，否则「崩溃」会变成「静默退出」。
                if (previous != null) {
                    previous.uncaughtException(thread, error)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun count(context: Context): Int = listFiles(context).size

    fun entries(context: Context): List<Entry> = listFiles(context).map { parse(it) }

    fun read(file: File): String = file.readText(Charsets.UTF_8)

    fun delete(file: File): Boolean = file.delete()

    fun clear(context: Context): Int {
        var n = 0
        for (f in listFiles(context)) if (f.delete()) n++
        return n
    }

    private fun listFiles(context: Context): List<File> =
            dir(context).listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                    ?.sortedByDescending { it.name }
                    ?: emptyList()

    private fun parse(file: File): Entry {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val time = Regex("时间: (.+)").find(text)?.groupValues?.get(1) ?: file.name
            val ex = Regex("异常: (.+)").find(text)?.groupValues?.get(1) ?: "未知异常"
            Entry(file, time, ex)
        } catch (t: Throwable) {
            Log.e(TAG, "read crash log failed: ${file.name}", t)
            Entry(file, file.name, "读取失败")
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val dir = dir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create crash dir: ${dir.absolutePath}")
        }
        // 写入前先腾出位置，写完再按上限裁一次，防止死循环崩溃写满存储。
        prune(dir, MAX_FILES - 1)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var file = File(dir, "crash-$stamp.txt")
        var n = 1
        while (file.exists()) {
            file = File(dir, "crash-$stamp-${n++}.txt")
        }
        val content = buildContent(context, thread, error)
        FileOutputStream(file).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        prune(dir, MAX_FILES)
    }

    private fun buildContent(context: Context, thread: Thread, error: Throwable): String {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "read package info failed", t)
            null
        }
        val versionName = info?.versionName ?: BuildConfig.VERSION_NAME
        val versionCode = when {
            info == null -> BuildConfig.VERSION_CODE.toLong()
            Build.VERSION.SDK_INT >= 28 -> info.longVersionCode
            else -> info.versionCode.toLong()
        }
        val stack = StringWriter()
        PrintWriter(stack).use { error.printStackTrace(it) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("时间: ").append(time).append('\n')
            append("versionName: ").append(versionName).append('\n')
            append("versionCode: ").append(versionCode).append('\n')
            append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("线程: ").append(thread.name).append('\n')
            append("异常: ").append(error.javaClass.name).append(": ")
                    .append(error.message ?: "").append('\n')
            append('\n')
            append("堆栈:\n")
            append(stack.toString())
        }
    }

    /** 只删单个文件，不递归删目录（红线：deleteRecursively 保持 0）。 */
    private fun prune(dir: File, keep: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") }
                ?: return
        if (files.size <= keep) return
        files.sortBy { it.name }
        for (i in 0 until files.size - keep) {
            if (!files[i].delete()) {
                Log.w(TAG, "failed to delete old crash log: ${files[i].name}")
            }
        }
    }
}
