package com.spacelens.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

/** 传输页：启动内置 HTTP 服务，电脑浏览器访问，手机 WebView 本机管理。 */
class TransferPage(context: Context) : LinearLayout(context) {

    private val status = TextView(context)
    private val address = TextView(context)
    private val pinText = TextView(context)
    private val start = Button(context)
    private val stop = Button(context)
    private val qr = ImageView(context)
    private val hint = TextView(context)
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    init {
        orientation = VERTICAL
        setPadding(dp(18f), dp(6f), dp(18f), dp(8f))
        setBackgroundColor(Color.TRANSPARENT)

        addView(TextView(context).apply {
            text = "局域网互传"
            textSize = 18f
            typeface = Draw.SANS_MEDIUM
            setTextColor(Theme.palette.text)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        status.apply {
            text = "未启动"
            textSize = 13f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(5f), 0, dp(2f))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        address.apply {
            text = "启动后显示电脑访问地址"
            textSize = 13f
            setTextColor(Theme.palette.text2)
            setTextIsSelectable(true)
            setPadding(0, dp(2f), 0, 0)
        }
        addView(address, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        pinText.apply {
            text = ""
            textSize = 12f
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(2f), 0, dp(6f))
        }
        addView(pinText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        start.text = "启动服务"
        start.textSize = 12.5f
        start.setTextColor(Color.WHITE)
        start.background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
        start.setOnClickListener { requestNotificationThenStart() }
        stop.text = "停止服务"
        stop.textSize = 12.5f
        stop.setTextColor(Theme.palette.brand)
        stop.background = rounded(Theme.palette.brandSoft, Theme.palette.brandSoft, Radius.PILL)
        stop.visibility = GONE
        stop.setOnClickListener { stopTransfer() }
        buttons.addView(start, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val slp = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        slp.leftMargin = dp(8f)
        buttons.addView(stop, slp)
        addView(buttons, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        qr.apply {
            visibility = GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, dp(10f), 0, dp(6f))
        }
        val qlp = LayoutParams(dp(150f), dp(150f))
        qlp.gravity = Gravity.CENTER_HORIZONTAL
        addView(qr, qlp)

        hint.apply {
            text = "电脑浏览器打开上面的地址，输入配对码；手机也可在下方页面管理文件。"
            textSize = 11.5f
            setTextColor(Theme.palette.muted)
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(0, dp(4f), 0, dp(6f))
        }
        addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val web = WebView(context).apply {
            visibility = GONE
            setBackgroundColor(Theme.palette.panel)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
        }
        webView = web
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updateUi()
    }

    fun onShown() {
        updateUi()
    }

    fun onResume() {
        updateUi()
    }

    private fun requestNotificationThenStart() {
        val activity = context as? android.app.Activity
        if (Build.VERSION.SDK_INT >= 33 && activity != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5002)
        }
        startTransfer()
    }

    private fun startTransfer() {
        if (TransferService.running) {
            updateUi()
            return
        }
        status.text = "正在启动…"
        start.isEnabled = false
        try {
            ContextCompat.startForegroundService(context,
                    Intent(context, TransferService::class.java))
        } catch (t: Throwable) {
            status.text = "启动失败：${t.message ?: "系统拒绝前台服务"}"
            start.isEnabled = true
            return
        }
        poll?.let { handler.removeCallbacks(it) }
        var tries = 0
        val job = object : Runnable {
            override fun run() {
                tries++
                if (TransferService.running) {
                    start.isEnabled = true
                    updateUi()
                    return
                }
                if (tries >= 25) {
                    status.text = "启动失败：端口占用或系统限制（可重试）"
                    start.isEnabled = true
                    return
                }
                handler.postDelayed(this, 200L)
            }
        }
        poll = job
        handler.postDelayed(job, 200L)
    }

    private fun stopTransfer() {
        try {
            context.stopService(Intent(context, TransferService::class.java))
        } catch (_: Throwable) {
        }
        webView?.loadUrl("about:blank")
        webView?.visibility = GONE
        qr.visibility = GONE
        status.text = "已停止"
        address.text = "启动后显示电脑访问地址"
        pinText.text = ""
        start.visibility = VISIBLE
        stop.visibility = GONE
        start.isEnabled = true
    }

    private fun updateUi() {
        if (TransferService.running && TransferService.port > 0) {
            val p = TransferService.port
            val ip = lanIp()
            start.visibility = GONE
            stop.visibility = VISIBLE
            start.isEnabled = true
            status.text = "运行中 · 端口 $p"
            address.text = if (ip != null) "http://$ip:$p/" else "未找到局域网 IP，请先连接 Wi-Fi"
            pinText.text = if (TransferService.pin.isNotEmpty()) "配对码：${TransferService.pin}" else ""
            if (ip != null) {
                qr.visibility = VISIBLE
                qr.setImageBitmap(makeQr("http://$ip:$p/"))
            } else {
                qr.visibility = GONE
            }
            val web = webView ?: return
            web.visibility = VISIBLE
            val local = "http://127.0.0.1:$p/"
            if (web.url != local) web.loadUrl(local)
        } else {
            start.visibility = VISIBLE
            stop.visibility = GONE
            status.text = "未启动"
            address.text = "启动后显示电脑访问地址"
            pinText.text = ""
            qr.visibility = GONE
            webView?.visibility = GONE
        }
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
