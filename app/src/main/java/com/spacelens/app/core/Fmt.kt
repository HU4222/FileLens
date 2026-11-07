package com.spacelens.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 体积 / 数量 / 时间 / 百分比的统一格式化，全应用只从这里取。 */
object Fmt {

    fun bytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return trim(kb) + " KB"
        val mb = kb / 1024.0
        if (mb < 1024.0) return trim(mb) + " MB"
        val gb = mb / 1024.0
        if (gb < 1024.0) return trim(gb) + " GB"
        return trim(gb / 1024.0) + " TB"
    }

    /** 树图小块用，尽量短：1.2G / 860M / 12K。 */
    fun bytesShort(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return trim(kb) + "K"
        val mb = kb / 1024.0
        if (mb < 1024.0) return trim(mb) + "M"
        val gb = mb / 1024.0
        if (gb < 1024.0) return trim(gb) + "G"
        return trim(gb / 1024.0) + "T"
    }

    private fun trim(v: Double): String {
        if (v >= 100) return String.format(Locale.US, "%.0f", v)
        if (v >= 10) return String.format(Locale.US, "%.1f", v)
        return String.format(Locale.US, "%.2f", v)
    }

    /** 大数字与单位分开：833 MB -> ("833","MB")，供 34sp 数字 + 14sp 单位排版。 */
    fun splitBytes(bytes: Long): Pair<String, String> {
        if (bytes < 1024L) return bytes.toString() to "B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return trim(kb) to "KB"
        val mb = kb / 1024.0
        if (mb < 1024.0) return trim(mb) to "MB"
        val gb = mb / 1024.0
        if (gb < 1024.0) return trim(gb) to "GB"
        return trim(gb / 1024.0) to "TB"
    }
    fun count(n: Long): String {
        if (n < 10_000L) return String.format(Locale.US, "%,d", n)
        val w = n / 10_000.0
        return if (w >= 100) String.format(Locale.US, "%.0f 万", w)
        else String.format(Locale.US, "%.1f 万", w)
    }

    fun pct(v: Float): String = String.format(Locale.US, "%.1f%%", v * 100f)

    fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100.0)

    fun dateTime(ms: Long): String {
        if (ms <= 0L) return "—"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))
    }

    fun duration(ms: Long): String {
        if (ms < 1000L) return "${ms}ms"
        val s = ms / 1000.0
        if (s < 60) return String.format(Locale.US, "%.1fs", s)
        val m = (s / 60).toInt()
        val sec = (s % 60).toInt()
        return "${m}m ${sec}s"
    }

    fun speed(mbPerSec: Double): String =
        if (mbPerSec >= 100) String.format(Locale.US, "%.0f", mbPerSec)
        else String.format(Locale.US, "%.1f", mbPerSec)

    fun iops(v: Double): String =
        if (v >= 1000) String.format(Locale.US, "%.1fk", v / 1000.0)
        else String.format(Locale.US, "%.0f", v)
}
