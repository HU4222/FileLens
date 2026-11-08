package com.spacelens.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.spacelens.app.core.CrashLog
import java.io.File

/** 崩溃日志页：列表 / 全文 / 分享 / 删除 / 清空。只读本机文件。 */
class CrashLogPage(context: Context) : LinearLayout(context) {

    var onClose: (() -> Unit)? = null

    private val list = ListView(context)
    private val adapter = Adapter()
    private val empty = LinearLayout(context)
    private val listWrap = LinearLayout(context)
    private val detailWrap = LinearLayout(context)
    private val detailScroll = ScrollView(context)
    private val detailText = TextView(context)
    private var entries: List<CrashLog.Entry> = emptyList()
    private var current: CrashLog.Entry? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.palette.bg)
        setPadding(dp(18f), dp(10f), dp(18f), dp(20f))

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(TextView(context).apply {
            text = "崩溃日志"
            textSize = 22f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(context).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(dp(10f), dp(8f), 0, dp(8f))
            setOnClickListener { onClose?.invoke() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(52f)))

        empty.apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "没有崩溃日志"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.text)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "崩溃时自动记录，只存在本机，不会上传。"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.muted)
                setPadding(0, dp(8f), 0, 0)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        listWrap.orientation = VERTICAL
        listWrap.addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        list.divider = null
        list.dividerHeight = 0
        list.adapter = adapter
        list.visibility = View.GONE
        listWrap.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val clearAll = TextView(context).apply {
            text = "清空全部"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.danger)
            setPadding(0, dp(12f), 0, dp(12f))
            setOnClickListener { confirmClear() }
        }
        listWrap.addView(clearAll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(listWrap, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        detailWrap.orientation = VERTICAL
        detailWrap.visibility = View.GONE
        val detailBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(6f))
        }
        detailBar.addView(TextView(context).apply {
            text = "‹ 返回"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(8f), dp(10f), dp(8f))
            setOnClickListener { showList() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        detailBar.addView(View(context),
                LayoutParams(0, dp(1f), 1f))
        detailBar.addView(TextView(context).apply {
            text = "分享"
            textSize = 13f
            setTextColor(Theme.palette.brand)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            setOnClickListener { shareCurrent() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        detailBar.addView(TextView(context).apply {
            text = "删除"
            textSize = 13f
            setTextColor(Theme.palette.danger)
            setPadding(dp(10f), dp(8f), 0, dp(8f))
            setOnClickListener { confirmDelete() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        detailWrap.addView(detailBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        detailText.apply {
            textSize = 11.5f
            setTextColor(Theme.palette.text2)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
        }
        detailScroll.setBackground(rounded(Theme.palette.panel, Theme.palette.line, Radius.SM, 1f))
        detailScroll.addView(detailText,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        detailWrap.addView(detailScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(detailWrap, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        refresh()
    }

    fun onShown() = refresh()

    /** 返回键：先退全文，再关页；返回 true 表示已消费。 */
    fun handleBack(): Boolean {
        if (detailWrap.visibility == View.VISIBLE) {
            showList()
        } else {
            onClose?.invoke()
        }
        return true
    }

    private fun refresh() {
        entries = CrashLog.entries(context)
        adapter.notifyDataSetChanged()
        list.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showList() {
        current = null
        detailWrap.visibility = View.GONE
        listWrap.visibility = View.VISIBLE
        refresh()
    }

    private fun showDetail(entry: CrashLog.Entry) {
        val text = try {
            CrashLog.read(entry.file)
        } catch (t: Throwable) {
            toast("读取失败：${t.message ?: entry.file.name}")
            return
        }
        current = entry
        detailText.text = text
        detailScroll.scrollTo(0, 0)
        listWrap.visibility = View.GONE
        detailWrap.visibility = View.VISIBLE
    }

    private fun shareCurrent() {
        val entry = current ?: return
        val text = try {
            CrashLog.read(entry.file)
        } catch (t: Throwable) {
            toast("读取失败：${t.message ?: entry.file.name}")
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FileLens 崩溃日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(Intent.createChooser(send, "分享崩溃日志"))
        } catch (t: Throwable) {
            toast("无法打开分享：${t.message}")
        }
    }

    private fun confirmDelete() {
        val entry = current ?: return
        AlertDialog.Builder(context)
                .setTitle("删除崩溃日志")
                .setMessage(entry.file.name + "\n删除后不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    if (CrashLog.delete(entry.file)) {
                        toast("已删除")
                        showList()
                    } else {
                        toast("删除失败")
                    }
                }
                .show()
    }

    private fun confirmClear() {
        if (entries.isEmpty()) return
        AlertDialog.Builder(context)
                .setTitle("清空全部")
                .setMessage("将删除 ${entries.size} 份崩溃日志，不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    val n = CrashLog.clear(context)
                    toast("已清空 $n 份")
                    showList()
                }
                .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any? = entries.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = entries.getOrNull(position) ?: return View(context)
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = rounded(Theme.palette.panel, Theme.palette.line, Radius.SM, 1f)
                setOnClickListener { showDetail(entry) }
            }
            row.addView(TextView(context).apply {
                text = entry.time
                textSize = 13f
                setTextColor(Theme.palette.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(TextView(context).apply {
                text = entry.exception
                textSize = 11f
                setTextColor(Theme.palette.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4f), 0, 0)
            })
            return row
        }
    }

    private fun toast(text: String) =
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun rounded(fill: Int, stroke: Int, radius: Float, width: Float = 1f): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(maxOf(1, dp(width)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
