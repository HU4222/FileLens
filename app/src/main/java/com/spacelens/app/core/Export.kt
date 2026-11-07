package com.spacelens.app.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import com.spacelens.app.ui.Draw
import com.spacelens.app.ui.Theme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** M5 导出：JSON / CSV / PNG；文件只写应用专属 exports 目录，可经 FileProvider 打开。 */
object Export {

    data class Output(val json: File, val csv: File, val png: File)

    fun all(context: Context, store: NodeStore, rootPath: String, summary: ScanSummary?): Output {
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val top = topNodes(store, 500)
        val json = File(dir, "spacelens-" + System.currentTimeMillis() + ".json")
        val csv = File(dir, "spacelens-" + System.currentTimeMillis() + ".csv")
        val png = File(dir, "spacelens-" + System.currentTimeMillis() + ".png")

        val arr = JSONArray()
        for (i in top) {
            arr.put(JSONObject().apply {
                put("name", store.nameOf(i))
                put("path", store.absolutePathOf(rootPath, i))
                put("size", store.sizeOf(i))
                put("files", store.filesOf(i))
                put("dirs", store.dirsOf(i))
            })
        }
        JSONObject().apply {
            put("app", "SpaceLens")
            put("version", "1.0.0")
            put("root", rootPath)
            put("nodes", store.count)
            put("files", summary?.files ?: 0)
            put("dirs", summary?.dirs ?: 0)
            put("bytes", summary?.bytes ?: store.sizeOf(0))
            put("elapsedMs", summary?.elapsedMs ?: 0)
            put("items", arr)
        }.let { json.writeText(it.toString(2), Charsets.UTF_8) }

        val sb = StringBuilder("name,path,size,files,dirs\n")
        for (i in top) {
            val p = store.absolutePathOf(rootPath, i).replace("\"", "\"\"")
            sb.append('"').append(store.nameOf(i)).append("\",\"")
                    .append(p).append("\",").append(store.sizeOf(i)).append(',')
                    .append(store.filesOf(i)).append(',').append(store.dirsOf(i)).append('\n')
        }
        csv.writeText(sb.toString(), Charsets.UTF_8)

        writeCard(context, png, store, rootPath, summary)
        return Output(json, csv, png)
    }

    private fun topNodes(store: NodeStore, limit: Int): IntArray {
        val n = minOf(store.count, 10000)
        return (0 until n).sortedByDescending { store.sizeOf(it) }
                .take(minOf(limit, n)).toIntArray()
    }

    private fun writeCard(context: Context, file: File, store: NodeStore,
                          rootPath: String, summary: ScanSummary?) {
        val w = 1080
        val h = 1440
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Theme.palette
        c.drawColor(p.bg)
        val d = 2f
        val title = Draw.text(context, 30f, p.text, medium = true)
        c.drawText("SpaceLens · 空间透镜", 60f, 90f, title)
        val sub = Draw.text(context, 16f, p.muted)
        c.drawText(rootPath + " · " + store.count + " 节点", 60f, 126f, sub)
        val used = summary?.bytes ?: store.sizeOf(0)
        val big = Draw.text(context, 64f, p.text, medium = true)
        c.drawText(Fmt.bytes(used), 60f, 250f, big)
        val unit = Draw.text(context, 18f, p.muted)
        c.drawText((summary?.files ?: 0).toString() + " 个文件 / " +
                (summary?.dirs ?: 0) + " 个目录", 60f, 292f, unit)

        val cats = store.categoryTotals()
        val names = arrayOf("图片", "视频", "音频", "文档", "应用", "压缩", "其他")
        val total = maxOf(cats.sum(), 1L)
        var y = 380f
        for (i in 0 until 7) {
            val color = p.category[i]
            c.drawCircle(72f, y - 6f, 6f, Draw.fill(color))
            c.drawText(names[i], 96f, y, Draw.text(context, 16f, p.text2))
            c.drawText(Fmt.bytes(cats[i]), 900f, y, Draw.text(context, 16f, p.text2, align = Paint.Align.RIGHT))
            val track = RectF(260f, y - 10f, 840f, y - 6f)
            c.drawRoundRect(track, 2f, 2f, Draw.fill(p.panelSunken))
            val ratio = cats[i].toFloat() / total
            c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * ratio, track.bottom),
                    2f, 2f, Draw.fill(color))
            y += 46f
        }
        c.drawText("本地生成 · HU4222", 60f, 750f, Draw.text(context, 14f, p.muted))
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    fun open(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
            val mime = when (file.extension.lowercase()) {
                "json" -> "application/json"
                "csv" -> "text/csv"
                "png" -> "image/png"
                else -> "*/*"
            }
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(i, "打开导出文件")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
        }
    }
}
