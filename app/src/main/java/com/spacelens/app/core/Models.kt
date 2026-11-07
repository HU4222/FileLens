package com.spacelens.app.core

/** 三档扫描限速（PRD 5.2）。 */
enum class RateMode(val stored: Int, val label: String, val perSecond: Int, val hint: String) {
    ECO(0, "节能", 1200, "约 1200 项/秒，发热低"),
    BALANCED(1, "均衡", 6000, "约 6000 项/秒，推荐"),
    TURBO(2, "极速", 0, "不限速，尽量快");

    companion object {
        fun from(v: Int): RateMode = entries.firstOrNull { it.stored == v } ?: BALANCED
    }
}

data class ScanSummary(
        val rootPath: String = "",
        val rootName: String = "",
        val files: Int = 0,
        val dirs: Int = 0,
        val bytes: Long = 0L,
        val elapsedMs: Long = 0L,
        val finishedAt: Long = 0L,
        val partial: Boolean = false,
        val denied: Int = 0,
        val rateMode: Int = RateMode.BALANCED.stored,
)
