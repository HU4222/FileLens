package com.spacelens.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Environment
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.spacelens.app.core.Trash
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 回收站：恢复 / 彻底删除 / 清空。 */
class TrashPage(context: Context) : LinearLayout(context) {

    var onClose: (() -> Unit)? = null
    private val list = ListView(context)
    private val empty = LinearLayout(context)
    private var items: List<Trash.Item> = emptyList()
    private val adapter = Adapter()

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.palette.bg)
        setPadding(dp(18f), dp(10f), dp(18f), 0)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(context).apply {
            text = "回收站"
            textSize = 22f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val clear = TextView(context).apply {
            text = "清空"
            textSize = 13f
            setTextColor(Theme.palette.danger)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setOnClickListener { confirmClear() }
        }
        bar.addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val close = TextView(context).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(dp(10f), dp(8f), 0, dp(8f))
            setOnClickListener { onClose?.invoke() }
        }
        bar.addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(52f)))

        empty.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "回收站是空的"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.text)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "删除的文件会在这里保留 30 天"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.muted)
                setPadding(0, dp(8f), 0, 0)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        list.divider = null
        list.dividerHeight = 0
        list.adapter = adapter
        list.visibility = View.GONE
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    fun onShown() = refresh()

    private fun refresh() {
        items = Trash.entries(context)
        adapter.notifyDataSetChanged()
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmClear() {
        if (items.isEmpty()) return
        AlertDialog.Builder(context)
                .setTitle("清空回收站")
                .setMessage("将彻底删除 ${items.size} 项，不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    for (i in items) Trash.purge(context, i)
                    toast("回收站已清空")
                    refresh()
                }.show()
    }

    private fun confirmPurge(item: Trash.Item) {
        AlertDialog.Builder(context)
                .setTitle("彻底删除")
                .setMessage("${File(item.originalPath).name}\n不可恢复，确定？")
                .setNegativeButton("取消", null)
                .setPositiveButton("彻底删除") { _, _ ->
                    Trash.purge(context, item)
                    toast("已彻底删除")
                    refresh()
                }.show()
    }

    private fun restore(item: Trash.Item) {
        if (Trash.restore(context, item)) {
            toast("已恢复到原位置")
            refresh()
            return
        }
        AlertDialog.Builder(context)
                .setTitle("恢复失败")
                .setMessage("原位置无法写入，是否另存到 Download？")
                .setNegativeButton("取消", null)
                .setPositiveButton("另存到 Download") { _, _ ->
                    val name = File(item.originalPath).name
                    val dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS)
                    val dest = File(dir, name)
                    try {
                        if (dest.exists()) {
                            toast("Download 中已有同名文件")
                            return@setPositiveButton
                        }
                        item.trashFile.copyTo(dest, overwrite = false)
                        Trash.purge(context, item)
                        toast("已另存到 Download/$name")
                        refresh()
                    } catch (t: Throwable) {
                        toast("另存失败：${t.message ?: "无写入权限"}")
                    }
                }.show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12f), 0, dp(12f))
            }
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            val name = File(item.originalPath).name
            texts.addView(TextView(context).apply {
                text = name
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(Theme.palette.text)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            texts.addView(TextView(context).apply {
                text = item.originalPath
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(Theme.palette.muted)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val days = ((System.currentTimeMillis() - item.movedAt) / 86400000L).coerceAtLeast(0)
            val left = (30 - days).coerceAtLeast(0)
            texts.addView(TextView(context).apply {
                text = "删除于 ${days} 天前 · 占用 ${size(item.size)} · 剩余 ${left} 天"
                textSize = 11f
                setTextColor(Theme.palette.muted)
                setPadding(0, dp(4f), 0, 0)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val restoreBtn = TextView(context).apply {
                text = "恢复"
                textSize = 12f
                setTextColor(Theme.palette.brand)
                setPadding(dp(10f), dp(8f), 0, dp(8f))
                setOnClickListener { restore(item) }
            }
            row.addView(restoreBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            var downX = 0f
            var moved = false
            row.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> if (ev.x - downX < -dp(72f)) moved = true
                    MotionEvent.ACTION_UP -> if (moved) confirmPurge(item)
                }
                false
            }
            return row
        }
    }

    private fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
