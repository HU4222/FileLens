package com.spacelens.app.core

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import java.io.IOException

/**
 * 应用占用：只读系统 StorageStats，不遍历文件、不读文件内容。
 * 读取前的授权由系统「使用情况访问」控制。
 */
object AppStats {

    data class Entry(
            val packageName: String,
            val label: String,
            val system: Boolean,
            val dataBytes: Long,
            val cacheBytes: Long,
            val appBytes: Long,
            val icon: Drawable?,
    ) {
        val totalBytes: Long get() = dataBytes + cacheBytes
    }

    data class Result(
            val entries: List<Entry>,
            val failed: Int,
            val total: Int,
    )

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName,
                )
            } else {
                ops.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun load(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val pm = context.packageManager
        val apps = try {
            pm.getInstalledApplications(0)
        } catch (_: Throwable) {
            return Result(emptyList(), 0, 0)
        }
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        if (ssm == null) return Result(emptyList(), apps.size, apps.size)
        val out = ArrayList<Entry>(apps.size)
        var failed = 0
        for ((i, app) in apps.withIndex()) {
            onProgress(i, apps.size)
            val pkg = app.packageName
            try {
                val stats = ssm.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                val label = try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Throwable) {
                    pkg
                }
                val icon = try {
                    app.loadIcon(pm)
                } catch (_: Throwable) {
                    null
                }
                val system = (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) ||
                        (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0)
                out.add(Entry(
                        packageName = pkg,
                        label = label,
                        system = system,
                        dataBytes = stats.dataBytes,
                        cacheBytes = stats.cacheBytes,
                        appBytes = stats.appBytes,
                        icon = icon,
                ))
            } catch (_: PackageManager.NameNotFoundException) {
                failed++
            } catch (_: SecurityException) {
                failed++
            } catch (_: IOException) {
                failed++
            } catch (_: Throwable) {
                failed++
            }
        }
        onProgress(apps.size, apps.size)
        return Result(out, failed, apps.size)
    }

    fun sort(entries: List<Entry>, byCache: Boolean): List<Entry> =
            if (byCache) entries.sortedWith(compareByDescending<Entry> { it.cacheBytes }
                    .thenByDescending { it.totalBytes })
            else entries.sortedByDescending { it.totalBytes }
}
