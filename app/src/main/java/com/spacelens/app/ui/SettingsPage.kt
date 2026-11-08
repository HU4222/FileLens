package com.spacelens.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.spacelens.app.BuildConfig
import com.spacelens.app.core.AppStats
import com.spacelens.app.core.CrashLog
import com.spacelens.app.core.Trash

/** 设置页：四项权限、回收站、版本信息。 */
class SettingsPage(context: Context) : LinearLayout(context) {

    var onClose: (() -> Unit)? = null
    var onTrash: (() -> Unit)? = null
    var onCrashLog: (() -> Unit)? = null
    var onRequestCamera: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private val rows = ArrayList<Row>()
    private val trashRow = LinearLayout(context)
    private val trashLine = TextView(context)
    private val crashLine = TextView(context)

    private data class Row(
            val action: TextView,
            val check: () -> Boolean,
            val open: () -> Unit,
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Theme.palette.bg)
        setPadding(dp(18f), dp(10f), dp(18f), dp(92f))

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            text = "设置"
            textSize = 24f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = "关闭"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(dp(12f), dp(10f), 0, dp(10f))
            setOnClickListener { onClose?.invoke() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, dp(54f)))

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(section("权限"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addPermission(body, "所有文件访问",
                "用来扫描和整理手机里的文件。\n没有它只能看到系统允许的少部分目录。",
                { allFiles() }, { openAllFiles() })
        addPermission(body, "使用情况访问",
                "用来读取每个应用占了多少空间。\n这是系统级权限，只能在系统设置里开。",
                { AppStats.hasUsageAccess(context) }, { openUsage() })
        addPermission(body, "通知",
                "传输服务在后台运行时需要一条常驻通知，\n没有它服务会被系统杀掉。",
                { notifications() }, { openNotifications() })
        addPermission(body, "相机",
                "扫码连接电脑时要用。",
                { camera() }, { requestCamera() })

        body.addView(section("诊断"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val diagRow = LinearLayout(context).apply {
            orientation = VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = rounded(Theme.palette.panelSunken, Theme.palette.line, 12f)
            setOnClickListener { onCrashLog?.invoke() }
        }
        val diagHead = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        crashLine.apply {
            textSize = 14f
            setTextColor(Theme.palette.text2)
        }
        diagHead.addView(crashLine, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        diagHead.addView(TextView(context).apply {
            text = "进入 ›"
            textSize = 12f
            setTextColor(Theme.palette.brand)
            setPadding(dp(8f), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        diagRow.addView(diagHead, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        diagRow.addView(TextView(context).apply {
            text = "崩溃时自动记录，只存在本机，不会上传。"
            textSize = 11f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(diagRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        body.addView(section("其他"), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        trashRow.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = rounded(Theme.palette.panelSunken, Theme.palette.line, 12f)
            setOnClickListener {
                android.util.Log.d("FileLensSettings", "trash row clicked")
                val callback = onTrash
                if (callback == null) {
                    android.util.Log.e("FileLensSettings", "trash callback is null")
                    android.widget.Toast.makeText(context, "回收站打开失败",
                            android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        callback.invoke()
                    } catch (t: Throwable) {
                        android.util.Log.e("FileLensSettings", "open trash failed", t)
                        android.widget.Toast.makeText(context, "回收站打开失败",
                                android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        trashLine.apply {
            textSize = 14f
            setTextColor(Theme.palette.text2)
        }
        trashRow.addView(trashLine, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        trashRow.addView(TextView(context).apply {
            text = "进入 ›"
            textSize = 12f
            setTextColor(Theme.palette.brand)
            setPadding(dp(8f), 0, 0, 0)
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        body.addView(trashRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(TextView(context).apply {
            text = "FileLens ${BuildConfig.VERSION_NAME} · HU4222"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(22f), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refresh()
    }

    private fun addPermission(
            parent: LinearLayout,
            title: String,
            description: String,
            check: () -> Boolean,
            open: () -> Unit,
    ) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = rounded(Theme.palette.panel, Theme.palette.line, 12f)
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = title
            textSize = 14f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val action = TextView(context).apply {
            textSize = 12f
            setPadding(dp(8f), dp(4f), 0, dp(4f))
            setOnClickListener { open() }
        }
        head.addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        card.addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(TextView(context).apply {
            text = description
            textSize = 11f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, 0)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.setOnClickListener { open() }
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8f)
        parent.addView(card, lp)
        rows.add(Row(action, check, open))
    }

    fun refresh() {
        for (row in rows) {
            val yes = row.check()
            row.action.text = if (yes) "已授权 ✓" else "去授权 ›"
            row.action.setTextColor(if (yes) Theme.palette.ok else Theme.palette.warn)
        }
        val items = Trash.entries(context)
        val bytes = items.sumOf { it.size }
        trashLine.text = "回收站 · ${items.size} 项 · " +
                android.text.format.Formatter.formatFileSize(context, bytes)
        crashLine.text = "崩溃日志 · ${CrashLog.count(context)} 份"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    private fun allFiles(): Boolean =
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED

    private fun notifications(): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED

    private fun camera(): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)))
            } catch (_: Throwable) {
                // 系统设置也不可用时保持页面，不静默崩溃。
            }
        }
    }

    private fun openAllFiles() {
        launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName)))
    }

    private fun openUsage() {
        launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openNotifications() {
        launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        })
    }

    private fun requestCamera() {
        onRequestCamera?.invoke()
        if (onRequestCamera == null) {
            val activity = findActivity()
            if (activity == null) {
                openAppDetails()
            } else {
                activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), 9101)
                postDelayed({ refresh() }, 500L)
            }
        }
    }

    private fun findActivity(): Activity? {
        var c: Context = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun openAppDetails() {
        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)))
    }

    private fun section(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Theme.palette.muted)
        setPadding(dp(2f), dp(14f), 0, dp(4f))
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radiusDp).toFloat()
                setColor(fill)
                setStroke(dp(1f).coerceAtLeast(1), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
