package com.spacelens.app.data

import android.content.Context
import com.spacelens.app.core.RateMode
import com.spacelens.app.core.ScanSummary
import com.spacelens.app.ui.ThemeMode

/** 设置与上次扫描摘要，全部本地 SharedPreferences，不上传。 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("spacelens", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = ThemeMode.from(sp.getInt("theme_mode", ThemeMode.DARK.stored))
        set(value) = sp.edit().putInt("theme_mode", value.stored).apply()

    var rateMode: RateMode
        get() = RateMode.from(sp.getInt("rate_mode", RateMode.BALANCED.stored))
        set(value) = sp.edit().putInt("rate_mode", value.stored).apply()

    var permissionAsked: Boolean
        get() = sp.getBoolean("permission_asked", false)
        set(value) = sp.edit().putBoolean("permission_asked", value).apply()

    var scanRoot: String?
        get() = sp.getString("scan_root", null)
        set(value) = sp.edit().putString("scan_root", value).apply()

    var safUri: String?
        get() = sp.getString("saf_uri", null)
        set(value) = sp.edit().putString("saf_uri", value).apply()

    var lastSummary: ScanSummary?
        get() {
            val at = sp.getLong("scan_finished_at", 0L)
            if (at <= 0L) return null
            return ScanSummary(
                    rootPath = sp.getString("scan_root_path", "") ?: "",
                    rootName = sp.getString("scan_root_name", "") ?: "",
                    files = sp.getInt("scan_files", 0),
                    dirs = sp.getInt("scan_dirs", 0),
                    bytes = sp.getLong("scan_bytes", 0L),
                    elapsedMs = sp.getLong("scan_elapsed", 0L),
                    finishedAt = at,
                    partial = sp.getBoolean("scan_partial", false),
                    denied = sp.getInt("scan_denied", 0),
                    rateMode = sp.getInt("scan_rate_mode", RateMode.BALANCED.stored),
            )
        }
        set(value) {
            val e = sp.edit()
            if (value == null) {
                e.remove("scan_finished_at")
            } else {
                e.putLong("scan_finished_at", value.finishedAt)
                        .putString("scan_root_path", value.rootPath)
                        .putString("scan_root_name", value.rootName)
                        .putInt("scan_files", value.files)
                        .putInt("scan_dirs", value.dirs)
                        .putLong("scan_bytes", value.bytes)
                        .putLong("scan_elapsed", value.elapsedMs)
                        .putBoolean("scan_partial", value.partial)
                        .putInt("scan_denied", value.denied)
                        .putInt("scan_rate_mode", value.rateMode)
            }
            e.apply()
        }

    var fpsHud: Boolean
        get() = sp.getBoolean("fps_hud", false)
        set(value) = sp.edit().putBoolean("fps_hud", value).apply()

    var firstRunDone: Boolean
        get() = sp.getBoolean("first_run_done", false)
        set(value) = sp.edit().putBoolean("first_run_done", value).apply()
}
