package com.spacelens.app.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.spacelens.app.core.NodeStore
import java.io.File

/**
 * 只列目录的选择器：P3 移动目标 与 P4.5 接收目录共用。
 * 有 NodeStore 时按树的目录结构走；没有扫描数据时退回 File 列表。
 */
object DirPicker {

    fun show(
            activity: Activity,
            store: NodeStore?,
            rootPath: String,
            startDir: File?,
            allowRestoreDefault: Boolean,
            onPick: (File) -> Unit,
            onRestoreDefault: (() -> Unit)? = null,
    ) {
        val p = Theme.palette
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val useStore = store != null && store.count > 1 && rootPath.isNotEmpty()
        var node = if (useStore) store!!.indexOfPath(rootPath, startDir?.absolutePath ?: rootPath)
                .let { if (it < 0) 0 else it } else -1
        var dir = startDir ?: File(rootPath)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = Radius.PANEL * d
                setColor(p.panel)
                setStroke((1 * d).toInt().coerceAtLeast(1), p.line)
            }
        }
        val title = TextView(activity).apply {
            text = "选择目录"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(p.text)
        }
        root.addView(title)
        val pathView = TextView(activity).apply {
            textSize = 11f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(0, (6 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(pathView)

        val list = ListView(activity)
        list.divider = null
        list.dividerHeight = 0
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun currentDir(): File {
            if (useStore && node >= 0) {
                return File(store!!.absolutePathOf(rootPath, node))
            }
            return dir
        }

        val adapter = object : BaseAdapter() {
            private var names: List<String> = emptyList()
            private var dirs: List<File> = emptyList()
            private var parentDir: File? = null

            fun refresh() {
                val n = ArrayList<String>()
                val fs = ArrayList<File>()
                val cur = currentDir()
                pathView.text = cur.absolutePath
                val parent = cur.parentFile
                if (parent != null && parent.absolutePath.startsWith(rootPath.trimEnd('/'))) {
                    parentDir = parent
                    n.add("↰ 上一级")
                    fs.add(parent)
                } else {
                    parentDir = null
                }
                val children = try {
                    cur.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
                            ?: emptyList()
                } catch (t: Throwable) {
                    android.util.Log.e("FileLensDirPicker", "list failed: ${cur.absolutePath}", t)
                    emptyList()
                }
                for (f in children) {
                    n.add(f.name)
                    fs.add(f)
                }
                names = n
                dirs = fs
                notifyDataSetChanged()
            }

            override fun getCount(): Int = names.size
            override fun getItem(position: Int): Any? = dirs.getOrNull(position)
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                val tv = TextView(activity).apply {
                    text = names.getOrNull(position) ?: ""
                    textSize = 14f
                    setTextColor(p.text)
                    setPadding((10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.START
                }
                return tv
            }
        }
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            val target = adapter.getItem(position) as? File ?: return@setOnItemClickListener
            dir = target
            if (useStore) {
                val idx = store!!.indexOfPath(rootPath, target.absolutePath)
                if (idx >= 0) node = idx
            }
            adapter.refresh()
        }

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        if (allowRestoreDefault && onRestoreDefault != null) {
            buttons.addView(TextView(activity).apply {
                text = "恢复默认"
                textSize = 12f
                setTextColor(p.muted)
                setPadding((6 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                setOnClickListener {
                    dialog.dismiss()
                    onRestoreDefault.invoke()
                }
            })
        }
        buttons.addView(android.view.View(activity),
                LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(TextView(activity).apply {
            text = "取消"
            textSize = 13f
            setTextColor(p.muted)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            setOnClickListener { dialog.dismiss() }
        })
        buttons.addView(TextView(activity).apply {
            text = "移到这里"
            textSize = 13f
            typeface = Draw.SANS_MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(p.brand)
            }
            setOnClickListener {
                val chosen = currentDir()
                dialog.dismiss()
                onPick(chosen)
            }
        })
        root.addView(buttons)

        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.45f)
            win.setGravity(Gravity.CENTER)
        }
        dialog.setOnShowListener {
            val w = activity.resources.displayMetrics.widthPixels
            val h = activity.resources.displayMetrics.heightPixels
            dialog.window?.setLayout(w - (24 * d).toInt(), (h * 0.72f).toInt())
        }
        adapter.refresh()
        dialog.show()
    }
}
