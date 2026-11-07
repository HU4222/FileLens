package com.spacelens.app.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.spacelens.app.BuildConfig
import kotlin.math.min

/** 应用内统一主题弹窗（不引 AppCompat）。 */
object Dialogs {

    fun showMessage(
            activity: Activity,
            title: String,
            message: String,
            positive: String = "知道了",
            onPositive: (() -> Unit)? = null,
            negative: String? = null,
            onNegative: (() -> Unit)? = null,
    ) {
        val p = Theme.palette
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (22 * d).toInt(), (22 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = Radius.PANEL * d
                setColor(p.panel)
                setStroke((1 * d).toInt().coerceAtLeast(1), p.line)
            }
        }

        root.addView(TextView(activity).apply {
            text = title
            setTextColor(p.text)
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
        })
        root.addView(TextView(activity).apply {
            text = message
            setTextColor(p.text2)
            textSize = 13f
            setLineSpacing(5f * d, 1f)
            setPadding(0, (12 * d).toInt(), 0, (20 * d).toInt())
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (negative != null) {
            row.addView(button(activity, negative, false) {
                dialog.dismiss()
                onNegative?.invoke()
            })
        }
        row.addView(button(activity, positive, true) {
            dialog.dismiss()
            onPositive?.invoke()
        })
        root.addView(row)

        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.45f)
        }
        dialog.setOnShowListener {
            val w = activity.resources.displayMetrics.widthPixels
            val width = min(w - (44 * d).toInt(), (360 * d).toInt())
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        sheetIn(root, d)
        dialog.show()
    }

    private fun button(activity: Activity, text: String, primary: Boolean, click: () -> Unit): TextView {
        val p = Theme.palette
        val d = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            this.text = text
            textSize = 13f
            typeface = Draw.SANS_MEDIUM
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else p.brand)
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = Radius.PILL * d
                setColor(if (primary) p.brand else p.brandSoft)
            }
            setOnClickListener { click() }
            val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.leftMargin = (8 * d).toInt()
            layoutParams = lp
        }
    }

    private fun sheetIn(root: View, d: Float) {
        root.alpha = 0f
        root.translationY = 24f * d
        root.post {
            Motion.tween(MotionDurations.SHEET, Ease::softOut, { t ->
                root.alpha = t
                root.translationY = (1f - t) * 24f * d
            })
        }
    }

    fun showAbout(activity: Activity) {
        showMessage(
                activity,
                "关于 文件管家",
                "文件管家\n版本 ${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）\n\n" +
                        "空间 / 搜索 / 清理 / 局域网互传。\n" +
                        "传输只监听本机局域网，不访问公网。删除只走回收站，可恢复；应用缓存清理需要 Shizuku。\n\n" +
                        "设计与实现：HU4222\n" +
                        "构建：Kotlin + 系统 View 自绘 · 零第三方 UI 库",
        )
    }

    fun showVersion(activity: Activity) {
        showMessage(
                activity,
                "版本信息",
                "文件管家\n版本 ${BuildConfig.VERSION_NAME}\nversionCode ${BuildConfig.VERSION_CODE}\n" +
                        "minSdk 26 / targetSdk 34\n\nHU4222",
        )
    }

    fun showPermissionFallback(
            activity: Activity,
            onSaf: () -> Unit,
            onSettings: () -> Unit,
    ) {
        showMessage(
                activity,
                "需要存储读取权限",
                "SpaceLens 需要读取共享存储才能画出空间树。\n\n" +
                        "你可以：\n" +
                        "· 去系统设置授予「所有文件访问」，扫描完整内部存储；\n" +
                        "· 或者只选择一个目录扫描（SAF 授权），功能降级但完全可用。\n\n" +
                        "应用不会申请 INTERNET 权限，也不会上传任何数据。",
                positive = "去设置",
                onPositive = onSettings,
                negative = "只扫目录",
                onNegative = onSaf,
        )
    }

    /** 手机长按详情卡：占父级比例 + 四个动作（复制 / 打开 / 系统文件 / 选择）。 */
    fun showNodeDetail(
            activity: Activity,
            title: String,
            message: String,
            canSelect: Boolean,
            selectReason: String,
            onCopy: () -> Unit,
            onOpen: () -> Unit,
            onReveal: () -> Unit,
            onSelect: () -> Unit,
    ) {
        val p = Theme.palette
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (22 * d).toInt(), (22 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = Radius.PANEL * d
                setColor(p.panel)
                setStroke((1 * d).toInt().coerceAtLeast(1), p.line)
            }
        }
        root.addView(TextView(activity).apply {
            text = title
            setTextColor(p.text)
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
        })
        root.addView(TextView(activity).apply {
            text = message
            setTextColor(p.text2)
            textSize = 13f
            setLineSpacing(5f * d, 1f)
            setPadding(0, (12 * d).toInt(), 0, (16 * d).toInt())
        })

        fun action(label: String, primary: Boolean, enabled: Boolean, click: () -> Unit): TextView {
            return TextView(activity).apply {
                text = label
                textSize = 11f
                typeface = Draw.SANS_MEDIUM
                gravity = Gravity.CENTER
                setTextColor(
                        if (primary && enabled) Color.WHITE
                        else if (enabled) p.text2 else p.muted
                )
                background = GradientDrawable().apply {
                    cornerRadius = Radius.SM * d
                    setColor(
                            if (primary && enabled) p.brand
                            else if (enabled) p.panelSoft else p.panelSunken
                    )
                    if (!primary && enabled) setStroke((1 * d).toInt().coerceAtLeast(1), p.line)
                }
                setOnClickListener {
                    if (enabled) {
                        dialog.dismiss()
                        click()
                    } else {
                        android.widget.Toast.makeText(activity,
                                selectReason.ifEmpty { "不可选择" }, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f)
            }
        }

        fun row(left: TextView, right: TextView) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
            }
            val lp = LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f)
            lp.rightMargin = (8 * d).toInt()
            left.layoutParams = lp
            r.addView(left)
            r.addView(right)
            root.addView(r)
        }

        row(action("复制路径", false, true, onCopy), action("用其他应用打开", false, true, onOpen))
        row(action("在系统文件中查看", false, true, onReveal),
                action("选择", true, canSelect, onSelect))

        val close = TextView(activity).apply {
            text = "关闭"
            textSize = 11f
            setTextColor(p.muted)
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, 0)
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(close)

        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.45f)
        }
        dialog.setOnShowListener {
            val w = activity.resources.displayMetrics.widthPixels
            val width = min(w - (36 * d).toInt(), (380 * d).toInt())
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        sheetIn(root, d)
        dialog.show()
    }

    /** 删除预览后的二次确认：必须长按 800ms；3 秒无操作自动收回。 */
    fun showDeleteConfirm(activity: Activity, title: String, message: String, onConfirm: () -> Unit) {
        val p = Theme.palette
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (22 * d).toInt(), (22 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = Radius.PANEL * d
                setColor(p.panel)
                setStroke((1 * d).toInt().coerceAtLeast(1), p.line)
            }
        }
        root.addView(TextView(activity).apply {
            text = title
            setTextColor(p.text)
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
        })
        root.addView(TextView(activity).apply {
            text = message
            setTextColor(p.text2)
            textSize = 13f
            setLineSpacing(5f * d, 1f)
            setPadding(0, (12 * d).toInt(), 0, (18 * d).toInt())
        })
        val hold = TextView(activity).apply {
            text = "长按 800ms 移入回收站"
            textSize = 13f
            typeface = Draw.SANS_MEDIUM
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = Radius.PILL * d
                setColor(p.danger)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (50 * d).toInt())
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var confirmed = false
        var downAt = 0L
        val fire = Runnable {
            if (!confirmed) {
                confirmed = true
                dialog.dismiss()
                onConfirm()
            }
        }
        hold.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = android.os.SystemClock.uptimeMillis()
                    hold.alpha = 0.82f
                    hold.text = "继续长按…"
                    handler.postDelayed(fire, 800L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(fire)
                    hold.alpha = 1f
                    hold.text = "长按 800ms 移入回收站"
                    true
                }
                else -> true
            }
        }
        root.addView(hold)
        val cancel = TextView(activity).apply {
            text = "取消"
            textSize = 11f
            setTextColor(p.muted)
            gravity = Gravity.CENTER
            setPadding(0, (12 * d).toInt(), 0, 0)
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(cancel)
        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.45f)
        }
        val auto = Runnable { if (!confirmed) dialog.dismiss() }
        handler.postDelayed(auto, 3000L)
        dialog.setOnDismissListener {
            handler.removeCallbacks(fire)
            handler.removeCallbacks(auto)
        }
        dialog.setOnShowListener {
            val w = activity.resources.displayMetrics.widthPixels
            val width = min(w - (36 * d).toInt(), (380 * d).toInt())
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        sheetIn(root, d)
        dialog.show()
    }
    fun showSearch(activity: Activity, onSearch: (String) -> Unit) {
        val p = Theme.palette
        val d = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (20 * d).toInt(), (22 * d).toInt(), (16 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = Radius.PANEL * d
                setColor(p.panel)
                setStroke((1 * d).toInt().coerceAtLeast(1), p.line)
            }
        }
        root.addView(TextView(activity).apply {
            text = "搜索"
            setTextColor(p.text)
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
        })
        val input = android.widget.EditText(activity).apply {
            hint = "文件名关键字"
            setSingleLine()
            setTextColor(p.text)
            setHintTextColor(p.muted)
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        }
        root.addView(input)
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val cancel = TextView(activity).apply {
            text = "取消"
            gravity = Gravity.CENTER
            setTextColor(p.muted)
            textSize = 13f
            setOnClickListener { dialog.dismiss() }
            layoutParams = LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f)
        }
        val go = TextView(activity).apply {
            text = "搜索"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Draw.SANS_MEDIUM
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(p.brand)
            }
            setOnClickListener {
                dialog.dismiss()
                onSearch(input.text.toString())
            }
            layoutParams = LinearLayout.LayoutParams(0, (42 * d).toInt(), 1f)
        }
        row.addView(cancel)
        row.addView(go)
        root.addView(row)
        dialog.setContentView(root)
        dialog.window?.let { win ->
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.45f)
        }
        dialog.setOnShowListener {
            val w = activity.resources.displayMetrics.widthPixels
            dialog.window?.setLayout(min(w - (36 * d).toInt(), (380 * d).toInt()),
                    WindowManager.LayoutParams.WRAP_CONTENT)
        }
        sheetIn(root, d)
        dialog.show()
    }}
