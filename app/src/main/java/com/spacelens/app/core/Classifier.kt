package com.spacelens.app.core

/** 文件分类与受限路径判定。分类索引即 Theme.palette.category 下标。 */
object Classifier {

    const val IMAGE = 0
    const val VIDEO = 1
    const val AUDIO = 2
    const val DOC = 3
    const val APK = 4
    const val ARCHIVE = 5
    const val OTHER = 6

    val labels = arrayOf("图片", "视频", "音频", "文档", "安装包", "压缩包", "其他")

    private val image = hashSetOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "raw", "svg", "avif")
    private val video = hashSetOf("mp4", "mkv", "mov", "avi", "wmv", "flv", "webm", "3gp", "m4v", "ts", "mts")
    private val audio = hashSetOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "amr")
    private val doc = hashSetOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
            "json", "xml", "html", "htm", "epub", "rtf", "log", "ini", "yaml", "yml",
    )
    private val apk = hashSetOf("apk", "aab", "xapk", "apks")
    private val archive = hashSetOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "tgz")

    fun categoryOf(name: String, flags: Int): Int {
        if (flags and NodeStore.DIR != 0) return OTHER
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return OTHER
        val ext = name.substring(dot + 1).lowercase()
        return when {
            ext in image -> IMAGE
            ext in video -> VIDEO
            ext in audio -> AUDIO
            ext in doc -> DOC
            ext in apk -> APK
            ext in archive -> ARCHIVE
            else -> OTHER
        }
    }

    fun labelOf(category: Int): String = labels.getOrElse(category) { "其他" }

    /** 树图四类条：图片 / 视频 / 文档 / 其他。 */
    fun overviewBucket(category: Int): Int = when (category) {
        IMAGE -> 0
        VIDEO -> 1
        DOC -> 2
        else -> 3
    }

    fun restrictedPath(path: String): Boolean {
        val p = path.replace('\\', '/')
        return p.contains("/Android/data") ||
                p.contains("/Android/obb") ||
                p == "/data" || p.startsWith("/data/") ||
                p == "/system" || p.startsWith("/system/") ||
                p == "/vendor" || p.startsWith("/vendor/")
    }
}
