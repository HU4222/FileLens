package com.spacelens.app.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.spacelens.app.TransferService
import java.net.NetworkInterface
import kotlin.math.max

/**
 * 传输页：进入即启动服务，WebView 作为主界面。
 * 地址/二维码放进「其他设备」底部面板，不再占据首屏。
 */
class TransferPage(context: Context) : FrameLayout(context) {

    companion object {
        const val REQ_FILE = 5010
    }

    private val status = TextView(context)
    private val other = TextView(context)
    private val web = WebView(context)
    private val errorView = LinearLayout(context)
    private val errorText = TextView(context)
    private val panel = FrameLayout(context)
    private val sheet = LinearLayout(context)
    private val address = TextView(context)
    private val pinText = TextView(context)
    private val qr = ImageView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var starting = false
    private var loadedUrl = ""
    private var panelShown = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val prefs = context.getSharedPreferences("filelens_transfer", Context.MODE_PRIVATE)

    init {
        setBackgroundColor(Theme.palette.bg)

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(12f), 0)
            setBackgroundColor(Theme.palette.panel)
        }
        status.apply {
            text = "未启动"
            textSize = 13f
            setTextColor(Theme.palette.muted)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        other.apply {
            text = "其他设备"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.brand)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = rounded(Theme.palette.brandSoft, Theme.palette.brandSoft, Radius.PILL)
            setOnClickListener { showPanel() }
        }
        bar.addView(other, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48f)))

        web.apply {
            visibility = View.INVISIBLE
            setBackgroundColor(Theme.palette.panel)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                        webView: WebView?,
                        callback: ValueCallback<Array<Uri>>?,
                        params: FileChooserParams?,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val activity = context as? android.app.Activity ?: return false
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                params?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    return try {
                        activity.startActivityForResult(
                                Intent.createChooser(intent, "选择文件"), REQ_FILE)
                        true
                    } catch (_: Throwable) {
                        fileCallback = null
                        false
                    }
                }
            }
            setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        addRequestHeader("X-DL-Token", TransferService.localToken)
                        setTitle(fileName)
                        setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                            .enqueue(request)
                    Toast.makeText(context, "已开始下载：$fileName", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(context, "下载失败：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun isAndroid(): Boolean = true
            }, "androidBridge")
        }
        content.addView(web, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        errorView.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Theme.palette.bg)
            visibility = GONE
        }
        errorText.apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setLineSpacing(dp(4f).toFloat(), 1f)
        }
        errorView.addView(errorText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val retry = Button(context).apply {
            text = "重试"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
            setOnClickListener {
                errorView.visibility = GONE
                startService()
            }
        }
        val rlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        rlp.gravity = Gravity.CENTER
        rlp.topMargin = dp(12f)
        errorView.addView(retry, rlp)
        addView(errorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildPanel()
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateUi()
    }

    private fun buildPanel() {
        panel.apply {
            visibility = GONE
            setBackgroundColor(0x660B1020)
            setOnClickListener { hidePanel() }
        }
        sheet.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(18f))
            setBackgroundColor(Theme.palette.panel)
            background = rounded(Theme.palette.panel, Theme.palette.line, Radius.PANEL)
            setOnClickListener { }
        }
        val sheetLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        sheetLp.gravity = Gravity.BOTTOM
        sheetLp.leftMargin = dp(12f)
        sheetLp.rightMargin = dp(12f)
        sheetLp.bottomMargin = dp(12f)
        panel.addView(sheet, sheetLp)

        sheet.addView(TextView(context).apply {
            text = "其他设备访问"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        address.apply {
            textSize = 15f
            setTextColor(Theme.palette.text2)
            setPadding(0, dp(10f), 0, 0)
            setTextIsSelectable(true)
            setOnClickListener { copyAddress() }
        }
        sheet.addView(address, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        pinText.apply {
            textSize = 12f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(4f), 0, dp(4f))
        }
        sheet.addView(pinText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        qr.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(6f), 0, dp(6f))
        }
        val qlp = LinearLayout.LayoutParams(dp(180f), dp(180f))
        qlp.gravity = Gravity.CENTER_HORIZONTAL
        sheet.addView(qr, qlp)

        val stop = Button(context).apply {
            text = "停止服务"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.danger, Theme.palette.danger, Radius.PILL)
            setOnClickListener { confirmStop() }
        }
        sheet.addView(stop, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42f)))
        val close = TextView(context).apply {
            text = "收起"
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(10f), 0, 0)
            setOnClickListener { hidePanel() }
        }
        sheet.addView(close, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    fun onShown() {
        updateUi()
        if (!TransferService.running) startService()
    }

    fun onResume() {
        updateUi()
    }

    fun handleBack(): Boolean {
        if (web.canGoBack()) {
            web.goBack()
            return true
        }
        return false
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) return
        val callback = fileCallback
        fileCallback = null
        if (callback == null) return
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return
        }
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        } else {
            data.data?.let { uris.add(it) }
        }
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private fun startService() {
        if (TransferService.running || starting) return
        val activity = context as? android.app.Activity
        if (Build.VERSION.SDK_INT >= 33 && activity != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5002)
        }
        starting = true
        errorView.visibility = GONE
        status.text = "正在启动…"
        try {
            ContextCompat.startForegroundService(context,
                    Intent(context, TransferService::class.java))
        } catch (t: Throwable) {
            fail("启动失败：${t.message ?: "系统拒绝前台服务"}")
            return
        }
        poll?.let { handler.removeCallbacks(it) }
        var tries = 0
        val job = object : Runnable {
            override fun run() {
                tries++
                if (TransferService.running) {
                    starting = false
                    updateUi()
                    return
                }
                if (tries >= 25) {
                    starting = false
                    fail("启动失败：" + (TransferService.lastError ?: "端口占用或系统限制"))
                    return
                }
                handler.postDelayed(this, 200L)
            }
        }
        poll = job
        handler.postDelayed(job, 200L)
    }

    private fun updateUi() {
        if (TransferService.running && TransferService.port > 0) {
            starting = false
            errorView.visibility = GONE
            web.visibility = View.VISIBLE
            val p = TransferService.port
            status.text = "传文件中 · 端口 $p"
            status.setTextColor(Theme.palette.ok)
            val url = "http://127.0.0.1:$p/?k=" + Uri.encode(TransferService.localToken)
            if (loadedUrl != url) {
                loadedUrl = url
                web.loadUrl(url)
            }
            if (TransferService.pin.isNotEmpty()) {
                pinText.text = "配对码：${TransferService.pin}"
            }
            val ip = lanIp()
            address.text = if (ip != null) {
                "http://$ip:$p/  （点此复制）"
            } else {
                "未找到局域网 IP，请先连接 Wi-Fi"
            }
            if (ip != null) qr.setImageBitmap(makeQr("http://$ip:$p/"))
            maybeShowHint()
        } else {
            web.visibility = View.INVISIBLE
            status.text = "未启动"
            status.setTextColor(Theme.palette.muted)
        }
    }

    private fun fail(message: String) {
        starting = false
        status.text = "未启动"
        status.setTextColor(Theme.palette.muted)
        errorText.text = message
        errorView.visibility = VISIBLE
    }

    private fun showPanel() {
        if (panelShown) return
        panelShown = true
        panel.visibility = VISIBLE
        panel.alpha = 0f
        sheet.translationY = dp(24f).toFloat()
        Motion.tween(MotionDurations.FADE, update = { t -> panel.alpha = t })
        Motion.tween(MotionDurations.SHEET, Ease::softOut, { t ->
            sheet.translationY = (1f - t) * dp(24f)
        })
    }

    private fun hidePanel() {
        if (!panelShown) return
        panelShown = false
        Motion.tween(MotionDurations.FADE, update = { t ->
            panel.alpha = 1f - t
        }, end = {
            panel.visibility = GONE
        })
    }

    private fun confirmStop() {
        val activity = context as? android.app.Activity ?: return
        Dialogs.showMessage(activity, "停止传输服务",
                "停止后电脑和其他设备将立即断开；手机上打开的文件页面也会停止。",
                positive = "停止服务", onPositive = {
                    hidePanel()
                    try {
                        context.stopService(Intent(context, TransferService::class.java))
                    } catch (_: Throwable) {
                    }
                    loadedUrl = ""
                    web.loadUrl("about:blank")
                    updateUi()
                },
                negative = "取消", onNegative = {})
    }

    private fun copyAddress() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("FileLens 地址", address.text))
        Toast.makeText(context, "地址已复制", Toast.LENGTH_SHORT).show()
    }

    private fun maybeShowHint() {
        if (prefs.getBoolean("hint_shown", false)) return
        prefs.edit().putBoolean("hint_shown", true).apply()
        Toast.makeText(context,
                "这个页面也能在电脑浏览器里打开，地址在右上角「其他设备」里",
                Toast.LENGTH_LONG).show()
    }

    private fun lanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (nif in interfaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses.toList()) {
                    val ip = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && !addr.isLinkLocalAddress && ip.indexOf(':') < 0) {
                        return ip
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun makeQr(text: String): Bitmap? = try {
        val hints = mapOf<EncodeHintType, Any>(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 420, 420, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Throwable) {
        null
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(max(1, dp(1f)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
