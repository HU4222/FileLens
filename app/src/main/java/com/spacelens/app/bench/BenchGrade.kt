package com.spacelens.app.bench

/** M4 档位与最弱项；阈值来自 PRD 6.3，写死便于复测。 */
object BenchGrade {

    fun grade(seqWriteMb: Double): String = when {
        seqWriteMb < 250 -> "eMMC 5.1"
        seqWriteMb < 500 -> "UFS 2.1 / 2.2"
        seqWriteMb < 1500 -> "UFS 3.0 / 3.1"
        else -> "UFS 4.0"
    }

    /** 归一化后找最弱项；只做一句话提示，不替用户判断硬件。 */
    fun weakest(
            seqWrite: Double,
            seqRead: Double,
            randRead: Double,
            randWrite: Double,
            meta: Double,
            fsync: Double,
    ): String {
        val scores = arrayOf(
                seqWrite / 1500.0,
                seqRead / 2000.0,
                randRead / 50000.0,
                randWrite / 20000.0,
                meta / 1000.0,
                (5.0 / fsync.coerceAtLeast(0.05)),
        )
        val names = arrayOf(
                "顺序写", "顺序读", "4K 随机读", "4K 随机写", "元数据", "fsync 延迟",
        )
        var idx = 0
        for (i in 1 until scores.size) if (scores[i] < scores[idx]) idx = i
        return names[idx]
    }
}
