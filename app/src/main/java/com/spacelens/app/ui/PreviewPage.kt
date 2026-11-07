package com.spacelens.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.core.content.FileProvider
import com.spacelens.app.core.Trash
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** 文件预览：图片/视频/文本/PDF/不支持。 */
class PreviewPage(
        context: Context,
        private val paths: List<String>,
        start: Int,
) : LinearLayout(context) {

    var onClose: (() -> Unit)? = null
    private var index = start.coerceIn(0, max(0, paths.size - 1))
    private val stage = LinearLayout(context)
    private val name = TextView(context)
    private var video: VideoView? = null
    private var pdf: PdfRenderer? = null
    private var pdfPage = 0
    private var pdfView: ImageView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        val top = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(10f), dp(8f), dp(8f))
        }
        name.apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(Color.WHITE)
        }
        top.addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(button("关闭") { onClose?.invoke() })
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, dp(52f)))
        stage.orientation = VERTICAL
        stage.gravity = Gravity.CENTER
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val tools = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8f), 0, dp(12f))
        }
        tools.addView(button("删除") { deleteCurrent() })
        tools.addView(button("分享") { shareCurrent() })
        tools.addView(button("其它应用") { openCurrent() })
        tools.addView(button("信息") { infoCurrent() })
        addView(tools, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)))
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) downX = ev.x
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val dx = ev.x - downX
                if (abs(dx) > dp(64f)) {
                    show(if (dx < 0) index + 1 else index - 1)
                    return@setOnTouchListener true
                }
            }
            false
        }
        show(index)
    }

    private var downX = 0f

    private fun show(i: Int) {
        if (paths.isEmpty()) { onClose?.invoke(); return }
        index = ((i % paths.size) + paths.size) % paths.size
        release()
        stage.removeAllViews()
        val f = File(paths[index])
        name.text = f.name
        if (!f.exists()) {
            showError("文件不存在")
            return
        }
        if (!f.canRead()) {
            showError("没有读取权限")
            return
        }
        val ext = f.extension.lowercase()
        when {
            ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") -> showImage(f)
            ext in setOf("mp4", "mkv", "avi") -> showVideo(f)
            ext in setOf("txt", "md", "json", "log", "xml") -> showText(f)
            ext == "pdf" -> showPdf(f)
            else -> showUnsupported(f)
        }
    }

    private fun showImage(f: File) {
        val bitmap = decodeSampled(f, 2048)
        if (bitmap == null) {
            showError("无法解码该图片")
            return
        }
        val iv = ZoomImage(context)
        iv.setImageBitmap(bitmap)
        stage.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun decodeSampled(f: File, target: Int): Bitmap? {
        // API 28+ 先走 ImageDecoder，按目标尺寸解码，避免原图整张进内存。
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                val source = ImageDecoder.createSource(f)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = minOf(1f, target.toFloat() / max(w, h).toFloat())
                    decoder.setTargetSize(max(1, (w * scale).toInt()),
                            max(1, (h * scale).toInt()))
                }
            } catch (_: Throwable) {
            }
        }
        // 降级 1：BitmapFactory 采样。
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            var sample = 1
            while (opts.outWidth > 0 && max(opts.outWidth, opts.outHeight) / sample > target * 2) {
                sample *= 2
            }
            opts.inJustDecodeBounds = false
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(f.absolutePath, opts)?.let { return it }
        } catch (_: Throwable) {
        }
        // 降级 2：原图（仅在小图/解码器特殊格式时兜底）。
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
    }

    private fun showError(message: String) {
        stage.removeAllViews()
        stage.addView(TextView(context).apply {
            text = "$message\n${File(paths[index]).name}"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stage.addView(button("用其他应用打开") { openCurrent() })
    }

    private fun showVideo(f: File) {
        val vv = VideoView(context)
        vv.setVideoPath(f.absolutePath)
        val mc = MediaController(context)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setOnPreparedListener { it.setVolume(0f, 0f) }
        video = vv
        stage.addView(vv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun showText(f: File) {
        val bytes = try {
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        } catch (_: Throwable) { ByteArray(0) }
        val text = String(bytes, Charsets.UTF_8)
        val tv = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        stage.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (f.length() > 64 * 1024) {
            stage.addView(button("文件过大，仅显示前 64KB") { })
        }
    }

    private fun showPdf(f: File) {
        val pfd = try { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Throwable) { null }
        if (pfd == null) { showUnsupported(f); return }
        val renderer = try { PdfRenderer(pfd) } catch (_: Throwable) {
            try { pfd.close() } catch (_: Throwable) {}
            showUnsupported(f); return
        }
        pdf = renderer
        val iv = ImageView(context)
        pdfView = iv
        pdfPage = 0
        stage.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(context).apply { gravity = Gravity.CENTER }
        nav.addView(button("上一页") { renderPdf(-1) })
        nav.addView(button("下一页") { renderPdf(1) })
        stage.addView(nav)
        renderPdf(0)
    }

    private fun renderPdf(delta: Int) {
        val r = pdf ?: return
        val p = (pdfPage + delta).coerceIn(0, r.pageCount - 1)
        pdfPage = p
        try {
            r.openPage(p).use { page ->
                val w = 1200
                val h = (w.toFloat() * page.height / page.width).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pdfView?.setImageBitmap(bmp)
            }
        } catch (_: Throwable) {}
    }

    private fun showUnsupported(f: File) {
        stage.addView(TextView(context).apply {
            text = "该类型暂不支持预览\n${f.name}"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stage.addView(button("用其他应用打开") { openCurrent() })
    }

    private fun deleteCurrent() {
        val f = File(paths[index])
        android.app.AlertDialog.Builder(context)
                .setTitle("删除文件")
                .setMessage("将移入回收站，可恢复。\n${f.name}")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    try {
                        Trash.move(context, listOf(f to f.name))
                        toast("已移入回收站")
                        onClose?.invoke()
                    } catch (t: Throwable) { toast("删除失败：${t.message}") }
                }.show()
    }

    private fun shareCurrent() {
        val uri = fileUri(File(paths[index])) ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(Intent.createChooser(i, "分享")) } catch (_: Throwable) {}
    }

    private fun openCurrent() {
        val uri = fileUri(File(paths[index])) ?: return
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(i) } catch (_: Throwable) { toast("没有可打开该类型的应用") }
    }

    private fun fileUri(f: File): Uri? = try {
        FileProvider.getUriForFile(context, context.packageName + ".files", f)
    } catch (_: Throwable) { null }

    private fun infoCurrent() {
        val f = File(paths[index])
        toast("${f.absolutePath}\n${f.length()} 字节\n" +
                android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", f.lastModified()))
    }

    private fun release() {
        video?.stopPlayback()
        video = null
        try { pdf?.close() } catch (_: Throwable) {}
        pdf = null
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun button(text: String, click: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Theme.palette.brand)
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        setOnClickListener { click() }
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}

private class ZoomImage(context: Context) : ImageView(context) {
    private val matrix = Matrix()
    private var scale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downTime = 0L

    init {
        scaleType = ScaleType.FIT_CENTER
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.x; lastY = ev.y; downX = ev.x; downTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> {
                    matrix.postTranslate(ev.x - lastX, ev.y - lastY)
                    lastX = ev.x; lastY = ev.y
                    imageMatrix = matrix
                }
                MotionEvent.ACTION_UP -> {
                    if (System.currentTimeMillis() - downTime < 250 && abs(ev.x - downX) < 20f) {
                        scale = if (scale > 1.5f) 1f else 2f
                        matrix.setScale(scale, scale, width / 2f, height / 2f)
                        imageMatrix = matrix
                    }
                }
            }
            true
        }
    }
}
