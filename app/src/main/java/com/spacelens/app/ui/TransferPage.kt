package com.spacelens.app.ui

import android.Manifest
import com.spacelens.app.QrScannerActivity
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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
 * 地址/二维码放进「连接信息」底部面板，不再占据首屏。
 */
class TransferPage(context: Context) : FrameLayout(context) {

    companion object {
        const val REQ_FILE = 5010
        const val REQ_SCAN = 5011
        private const val TABBAR_H_DP = 80f
        private const val BALL_BOTTOM_SCREEN_DP = 116f
        // 1.2.0 起 pager 延伸到屏幕底，胶囊悬浮在上面；球位置直接用屏幕坐标。
        private const val BALL_BOTTOM_DP = BALL_BOTTOM_SCREEN_DP
        private const val LABEL_H_DP = 22f
        private const val LABEL_WIDTH_DP = 64f
        private const val MAIN_BALL_DP = 62f
        private const val MAIN_RIGHT_DP = 16f
        private const val SAT_BALL_DP = 54f
        private const val SAT_GAP_DP = 8f
        private const val SAT_STEP_DP = SAT_BALL_DP + SAT_GAP_DP
        private const val SAT_FIRST_BOTTOM_DP = BALL_BOTTOM_DP + MAIN_BALL_DP + SAT_GAP_DP
        private const val LABEL_RIGHT_DP = MAIN_RIGHT_DP + SAT_BALL_DP + 8f
    }

    private val status = TextView(context)
    private val port = TextView(context)
    private val refresh = TextView(context)
    private val qrButton = TextView(context)
    private val stateToggle = LinearLayout(context)
    private val stateDot = View(context)
    private val stateText = TextView(context)
    private val closedView = LinearLayout(context)
    private val web = WebView(context)
    private val errorView = LinearLayout(context)
    private val errorText = TextView(context)
    private val panel = FrameLayout(context)
    private val sheet = LinearLayout(context)
    private val address = TextView(context)
    private val pinText = TextView(context)
    private val qr = ImageView(context)
    private val startBtn = Button(context)
    private val stopBtn = Button(context)
    private val handler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var stopPoll: Runnable? = null
    private var starting = false
    private var stopping = false
    private var manualStopped = false
    private var pendingShowPanel = false
    private var startFailed = false
    var onPanelVisibility: ((Boolean) -> Unit)? = null
    var onSheetVisibility: ((Boolean) -> Unit)? = null
    var onPickRecvDir: (() -> Unit)? = null
    private var sheetOpen = false
    private var loadedUrl = ""
    private var panelShown = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val scrim = View(context)
    private val mainBall = FrameLayout(context)
    private val mainBallLabel = TextView(context)
    private val satBalls = ArrayList<FrameLayout>()
    private val satLabels = ArrayList<TextView>()
    private var ballsExpanded = false
    private var ballAnim: Motion.Animation? = null
    private var scrimAnim: Motion.Animation? = null
    private var pressAnim: Motion.Animation? = null
    private var longPress: Runnable? = null
    private var longPressed = false
    private var ballDownX = 0f
    private var ballDownY = 0f
    private val satViews = arrayOf("browse", "send", "clip", "about")
    private val satNames = arrayOf("浏览", "传文件", "剪贴板", "我的")
    private val satIcons = arrayOf(Icon.FOLDER, Icon.SPEED, Icon.LIST, Icon.USER)
    private val prefs = context.getSharedPreferences("filelens_transfer", Context.MODE_PRIVATE)

    init {
        clipChildren = false
        setBackgroundColor(Theme.palette.bg)

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(12f), 0)
            setBackgroundColor(Theme.palette.panel)
        }
        status.apply {
            text = "传输 · " + Build.MODEL
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Theme.palette.text2)
        }
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        port.apply {
            text = "8848"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.brand)
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            visibility = View.GONE
            setOnClickListener { openAbout() }
        }
        bar.addView(port, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        refresh.apply {
            text = "刷新"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text2)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = rounded(Color.argb(15, 255, 255, 255),
                    Theme.palette.line, Radius.PILL)
            setOnClickListener {
                web.reload()
                readCurrentView()
            }
        }
        val refreshLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        refreshLp.leftMargin = dp(4f)
        bar.addView(refresh, refreshLp)

        qrButton.apply {
            text = "二维码"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text2)
            setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            background = rounded(Color.argb(15, 255, 255, 255),
                    Theme.palette.line, Radius.PILL)
            setOnClickListener { openAbout() }
        }
        val qrLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        qrLp.leftMargin = dp(2f)
        bar.addView(qrButton, qrLp)

        stateToggle.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = rounded(Theme.palette.panelSunken, Theme.palette.line, Radius.PILL)
            setOnClickListener { toggleService() }
        }
        stateDot.apply {
            setBackground(rounded(Theme.palette.warn, Theme.palette.warn, Radius.PILL))
        }
        val dotLp = LinearLayout.LayoutParams(dp(8f), dp(8f))
        dotLp.rightMargin = dp(5f)
        stateToggle.addView(stateDot, dotLp)
        stateText.apply {
            text = "关闭"
            textSize = 12f
            setTextColor(Theme.palette.text2)
        }
        stateToggle.addView(stateText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val toggleLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        toggleLp.leftMargin = dp(6f)
        bar.addView(stateToggle, toggleLp)
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
            setPadding(0, 0, 0, dp(92f))
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectEmbedCss(view)
                    injectViewBridge(view)
                }
            }
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
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onViewChanged(view: String) {
                    handler.post { updateBallLabel(view) }
                }

                @JavascriptInterface
                fun pin(): String = TransferService.pin

                @JavascriptInterface
                fun stopService() {
                    handler.post { this@TransferPage.stopService() }
                }

                @JavascriptInterface
                fun scanQr() {
                    handler.post { this@TransferPage.scanQr() }
                }

                @JavascriptInterface
                fun pickRecvDir() {
                    handler.post { onPickRecvDir?.invoke() }
                }

                @JavascriptInterface
                fun lanUrl(): String {
                    val ip = lanIp() ?: return ""
                    val p = TransferService.port
                    return if (p > 0) "http://$ip:$p" else ""
                }

                @JavascriptInterface
                fun onSheet(open: Boolean) {
                    handler.post { setWebSheetOpen(open) }
                }
            }, "__dlNative")
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

        closedView.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Theme.palette.bg)
            visibility = View.GONE
        }
        closedView.addView(TextView(context).apply {
            text = "传输服务未开启"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text)
        }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        closedView.addView(TextView(context).apply {
            text = "开启后，电脑和其他设备可访问手机文件"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.muted)
            setPadding(0, dp(8f), 0, dp(18f))
        }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val openBtn = Button(context).apply {
            text = "开启传输服务"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
            setOnClickListener {
                pendingShowPanel = true
                startService()
            }
        }
        closedView.addView(openBtn, LinearLayout.LayoutParams(dp(180f), dp(44f)))
        addView(closedView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildBalls()
        updateUi()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 不用写死屏幕 dp：按 TransferPage 的实测高度把球放到胶囊上方 16dp。
        if (b - t > 0) applyBallGeometry(b - t)
        super.onLayout(changed, l, t, r, b)
    }

    private fun applyBallGeometry(hostHeight: Int) {
        val capsuleTopY = hostHeight - dp(18f + 62f)
        val mainBottom = hostHeight - (capsuleTopY - dp(16f))
        (mainBall.layoutParams as? LayoutParams)?.let {
            if (it.bottomMargin != mainBottom) {
                it.bottomMargin = mainBottom
                mainBall.layoutParams = it
            }
        }
        for (i in satBalls.indices) {
            val satBottom = mainBottom + dp(MAIN_BALL_DP + SAT_GAP_DP + i * SAT_STEP_DP)
            val labelBottom = satBottom + dp((SAT_BALL_DP - LABEL_H_DP) / 2f)
            (satBalls[i].layoutParams as? LayoutParams)?.let {
                if (it.bottomMargin != satBottom) {
                    it.bottomMargin = satBottom
                    satBalls[i].layoutParams = it
                }
            }
            (satLabels[i].layoutParams as? LayoutParams)?.let {
                if (it.bottomMargin != labelBottom) {
                    it.bottomMargin = labelBottom
                    satLabels[i].layoutParams = it
                }
            }
        }
    }

    private fun buildBalls() {
        scrim.setBackgroundColor(Color.argb(89, 0, 0, 0))
        scrim.alpha = 0f
        scrim.isClickable = true
        scrim.visibility = View.GONE
        scrim.setOnClickListener { collapseBalls() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        for (i in satViews.indices) {
            val satFill = if (Theme.palette.dark) Color.argb(77, 255, 255, 255)
            else Color.argb(115, 255, 255, 255)
            val ball = makeBall(SAT_BALL_DP.toInt(), satFill, 10f, satNames[i], satIcons[i])
            val ballLp = LayoutParams(dp(SAT_BALL_DP), dp(SAT_BALL_DP), Gravity.BOTTOM or Gravity.RIGHT)
            ballLp.rightMargin = dp(MAIN_RIGHT_DP)
            ballLp.bottomMargin = dp(SAT_FIRST_BOTTOM_DP + i * SAT_STEP_DP)
            ball.visibility = View.INVISIBLE
            ball.alpha = 0f
            ball.scaleX = 0.72f
            ball.scaleY = 0.72f
            ball.translationY = dp(20f).toFloat()
            ball.setOnClickListener {
                switchView(satViews[i])
                pulseSatellite(ball)
            }
            addView(ball, ballLp)
            satBalls.add(ball)

            val label = TextView(context).apply {
                text = satNames[i]
                textSize = 10f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
                background = rounded(Color.argb(89, 0, 0, 0),
                        Color.TRANSPARENT, 8f)
                visibility = View.INVISIBLE
                alpha = 0f
            }
            val labelLp = LayoutParams(dp(LABEL_WIDTH_DP), dp(LABEL_H_DP), Gravity.BOTTOM or Gravity.RIGHT)
            labelLp.rightMargin = dp(LABEL_RIGHT_DP)
            labelLp.bottomMargin = dp(SAT_FIRST_BOTTOM_DP + i * SAT_STEP_DP +
                    (SAT_BALL_DP - LABEL_H_DP) / 2f)
            addView(label, labelLp)
            satLabels.add(label)
        }

        mainBall.addView(glassBackground(
                Color.argb(107, 139, 135, 255), 31f),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mainBallLabel.apply {
            text = "浏览"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text)
        }
        mainBall.addView(mainBallLabel, LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mainBall.setOnTouchListener { _, ev -> onMainBallTouch(ev) }
        val mainLp = LayoutParams(dp(MAIN_BALL_DP), dp(MAIN_BALL_DP), Gravity.BOTTOM or Gravity.RIGHT)
        mainLp.rightMargin = dp(MAIN_RIGHT_DP)
        mainLp.bottomMargin = dp(BALL_BOTTOM_DP)
        addView(mainBall, mainLp)
        android.util.Log.i("FileLensBall", "satellites=${satBalls.size}")
    }

    private fun makeBall(
            size: Int,
            fill: Int,
            textSize: Float,
            text: String,
            icon: Icon? = null,
    ): FrameLayout {
        val root = FrameLayout(context)
        root.addView(glassBackground(fill, size / 2f),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (icon != null) {
            root.addView(object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    Draw.icon(canvas, icon, width / 2f, height / 2f,
                            dp(22f).toFloat(), Theme.palette.text2, 2f * resources.displayMetrics.density)
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            root.addView(TextView(context).apply {
                this.text = text
                this.textSize = textSize
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.text)
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        root.isClickable = true
        return root
    }

    private fun glassBackground(fill: Int, radiusDp: Float): View =
            object : View(context) {
                override fun onDraw(canvas: Canvas) {
                    Glass.draw(canvas,
                            RectF(0f, 0f, width.toFloat(), height.toFloat()),
                            dp(radiusDp).toFloat(), Theme.palette.dark, fill,
                            resources.displayMetrics.density)
                }
            }

    private fun onMainBallTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                ballDownX = ev.x
                ballDownY = ev.y
                scaleMain(0.94f)
                val job = Runnable {
                    longPressed = true
                    showPanel()
                }
                longPress = job
                handler.postDelayed(job, 300L)
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (Math.abs(ev.x - ballDownX) > slop || Math.abs(ev.y - ballDownY) > slop) {
                    longPress?.let { handler.removeCallbacks(it) }
                    longPress = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPress?.let { handler.removeCallbacks(it) }
                longPress = null
                scaleMain(1f)
                if (ev.actionMasked == MotionEvent.ACTION_UP && !longPressed) {
                    if (ballsExpanded) collapseBalls() else expandBalls()
                }
            }
        }
        return true
    }

    private fun scaleMain(target: Float) {
        pressAnim?.cancel()
        val from = mainBall.scaleX
        pressAnim = Motion.tween(130, Ease::softOut, { t ->
            val v = from + (target - from) * t
            mainBall.scaleX = v
            mainBall.scaleY = v
        })
    }

    private fun pulseSatellite(ball: FrameLayout) {
        ball.scaleX = 0.9f
        ball.scaleY = 0.9f
        Motion.tween(120, Ease::softOut, { t ->
            val v = 0.9f + 0.1f * t
            ball.scaleX = v
            ball.scaleY = v
        }, {
            collapseBalls()
        })
    }

    private fun expandBalls() {
        if (ballsExpanded) return
        ballAnim?.cancel()
        scrimAnim?.cancel()
        scrim.visibility = View.VISIBLE
        val fromScrim = scrim.alpha
        scrimAnim = Motion.tween(150, Ease::linear, { t ->
            scrim.alpha = fromScrim + (1f - fromScrim) * t
        })
        for (i in satBalls.indices) {
            satBalls[i].visibility = View.VISIBLE
            satLabels[i].visibility = View.VISIBLE
        }
        val fromScale = FloatArray(satBalls.size) { satBalls[it].scaleX }
        val fromAlpha = FloatArray(satBalls.size) { satBalls[it].alpha }
        val fromY = FloatArray(satBalls.size) { satBalls[it].translationY }
        ballAnim = Motion.tween(280, Ease::linear, { t ->
            val elapsed = t * 280f
            for (i in satBalls.indices) {
                val delay = i * 35f
                val local = ((elapsed - delay) / (280f - delay)).coerceIn(0f, 1f)
                val e = ballEase(local)
                satBalls[i].translationY = fromY[i] * (1f - e)
                satBalls[i].scaleX = fromScale[i] + (1f - fromScale[i]) * e
                satBalls[i].scaleY = satBalls[i].scaleX
                val a = (local / 0.4f).coerceIn(0f, 1f)
                satBalls[i].alpha = fromAlpha[i] + (1f - fromAlpha[i]) * a
                satLabels[i].alpha = satBalls[i].alpha
            }
        }, {
            for (i in satBalls.indices) {
                satBalls[i].translationY = 0f
            }
            ballsExpanded = true
        })
    }

    private fun collapseBalls() {
        if (!ballsExpanded) return
        ballAnim?.cancel()
        scrimAnim?.cancel()
        val fromScrim = scrim.alpha
        scrimAnim = Motion.tween(150, Ease::linear, { t ->
            scrim.alpha = fromScrim * (1f - t)
        }, {
            scrim.visibility = View.GONE
        })
        val fromScale = FloatArray(satBalls.size) { satBalls[it].scaleX }
        val fromAlpha = FloatArray(satBalls.size) { satBalls[it].alpha }
        val fromY = FloatArray(satBalls.size) { satBalls[it].translationY }
        val targetY = dp(20f).toFloat()
        ballAnim = Motion.tween(200, Ease::linear, { t ->
            val elapsed = t * 200f
            for (i in satBalls.indices) {
                // 从上往下收：最上面的 i=3 先动。
                val delay = (satBalls.size - 1 - i) * 25f
                val local = ((elapsed - delay) / (200f - delay)).coerceIn(0f, 1f)
                val e = ballEase(local)
                satBalls[i].translationY = fromY[i] + (targetY - fromY[i]) * e
                satBalls[i].scaleX = fromScale[i] + (0.72f - fromScale[i]) * e
                satBalls[i].scaleY = satBalls[i].scaleX
                satBalls[i].alpha = fromAlpha[i] * (1f - e)
                satLabels[i].alpha = satBalls[i].alpha
            }
        }, {
            for (i in satBalls.indices) {
                satBalls[i].visibility = View.INVISIBLE
                satBalls[i].translationY = targetY
                satBalls[i].alpha = 0f
                satBalls[i].scaleX = 0.72f
                satBalls[i].scaleY = 0.72f
                satLabels[i].visibility = View.INVISIBLE
                satLabels[i].alpha = 0f
            }
            ballsExpanded = false
        })
    }

    private fun ballEase(t: Float): Float {
        fun bezier(p: Float, a: Float, b: Float): Float {
            val u = 1f - p
            return 3f * u * u * p * a + 3f * u * p * p * b + p * p * p
        }
        var lo = 0f
        var hi = 1f
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (bezier(mid, 0.22f, 0.3f) < t) lo = mid else hi = mid
        }
        return bezier((lo + hi) / 2f, 0.9f, 1f)
    }

    private fun switchView(view: String) {
        web.evaluateJavascript(
                "document.querySelector('[data-view=\"$view\"]')?.click()", null)
        handler.postDelayed({ readCurrentView() }, 200L)
    }

    /** 接收目录改完后，直接把网页「我的」那两处路径改成新值，不依赖用户切页刷新。 */
    fun refreshRecvDir(path: String) {
        val quoted = org.json.JSONObject.quote(path)
        val js = "(function(){var p=$quoted;" +
                "var kvs=[...document.querySelectorAll('#aboutBody .kv')];" +
                "for(var i=0;i<kvs.length;i++){if(kvs[i].textContent.indexOf('接收目录')>=0){" +
                "var b=kvs[i].querySelector('b');if(b)b.textContent=p;}}" +
                "var cards=[...document.querySelectorAll('#aboutBody .card')];" +
                "for(var j=0;j<cards.length;j++){if(cards[j].textContent.indexOf('接收目录')>=0){" +
                "var m=cards[j].querySelector('p.mono');if(m)m.textContent=p;" +
                "var inp=cards[j].querySelector('input');if(inp)inp.value=p;}}" +
                "})()"
        web.evaluateJavascript(js, null)
    }

    private fun injectEmbedCss(view: WebView?) {
        val js = """
            (function(){
              if(document.getElementById('dl-embed-style'))return;
              var s=document.createElement('style');s.id='dl-embed-style';
              s.textContent='.nav{display:none!important}.topbar{display:none!important}.views{padding-top:8px!important}';
              document.head.appendChild(s);
              if(location.hostname==='127.0.0.1'||location.hostname==='localhost'){
                var h=document.getElementById('dl-hide-local-download');
                if(!h){h=document.createElement('style');h.id='dl-hide-local-download';document.head.appendChild(h);}
                h.textContent='[data-act="download"],[data-act="sheetDownload"]{display:none!important}';
              }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun injectViewBridge(view: WebView?) {
        val js = """
            (function(){
              if (window.__flBallHooked) return;
              window.__flBallHooked = true;
              function current(){
                var e=document.querySelector('.view.is-active')||document.querySelector('[data-view].is-active');
                if(!e)return '';
                if(e.id&&e.id.indexOf('view-')===0)return e.id.substring(5);
                return e.getAttribute('data-view')||'';
              }
              function notify(){
                try{var v=current();if(v&&window.__dlNative)window.__dlNative.onViewChanged(v);}catch(e){}
              }
              document.addEventListener('click',function(ev){
                var n=ev.target;
                while(n&&n!==document){
                  if(n.getAttribute&&n.getAttribute('data-view')){setTimeout(notify,80);break;}
                  n=n.parentElement;
                }
              },true);
              setTimeout(notify,0);
            })();
            (function(){
              if(window.__flSheetHooked)return; window.__flSheetHooked=true;
              window.__dlSheet=function(o){try{window.__dlNative.onSheet(!!o)}catch(e){}};
              var last=false;
              function check(){
                var el=document.getElementById('sheet')||document.querySelector('.sheet');
                var open=!!(el&&el.offsetParent!==null&&getComputedStyle(el).display!=='none'&&
                  el.getBoundingClientRect().height>0);
                if(open!==last){last=open;window.__dlSheet(open);}
              }
              new MutationObserver(check).observe(document.body,
                {attributes:true,subtree:true,attributeFilter:['class','style']});
              setInterval(check,300);
            })();
            (function(){
              if(window.__flScanHooked)return; window.__flScanHooked=true;
              document.addEventListener('click',function(ev){
                var b=ev.target.closest&&ev.target.closest('[data-act="scanConnect"]');
                if(!b)return;
                ev.preventDefault();ev.stopPropagation();ev.stopImmediatePropagation();
                try{window.__dlNative.scanQr()}catch(e){}
              },true);
            })();
            (function(){
              if(window.__flCrumbHooked)return; window.__flCrumbHooked=true;
              var cb=document.getElementById('crumbs');
              if(!cb)return;
              function fix(){
                var f=cb.querySelector('.crumb');
                if(!f||f.textContent.trim()!=='/')return;
                f.textContent='内部存储';
              }
              new MutationObserver(fix).observe(cb,
                {childList:true,subtree:true,characterData:true});
              fix();
            })();
            (function(){
              if(window.__flRecvHooked)return; window.__flRecvHooked=true;
              document.addEventListener('click',function(ev){
                var kv=ev.target.closest&&ev.target.closest('.kv');
                if(!kv||kv.textContent.indexOf('接收目录')<0)return;
                ev.preventDefault();ev.stopPropagation();
                try{window.__dlNative.pickRecvDir()}catch(e){}
              },true);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun readCurrentView() {
        web.evaluateJavascript(
                "(function(){var e=document.querySelector('.view.is-active')||document.querySelector('[data-view].is-active');if(!e)return '';return e.id?e.id.replace(/^view-/,''):(e.getAttribute('data-view')||'');})()",
                { value ->
                    val v = value?.trim()?.trim('"').orEmpty()
                    if (v.isNotEmpty()) updateBallLabel(v)
                })
    }

    private fun updateBallLabel(view: String) {
        val label = when (view) {
            "browse" -> "浏览"
            "send" -> "传文件"
            "clip" -> "剪贴板"
            "about" -> "我的"
            else -> return
        }
        mainBallLabel.text = label
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
            background = Glass.drawable(
                    dp(Glass.radius()).toFloat(), Theme.palette.dark,
                    resources.displayMetrics.density)
            setOnClickListener { }
        }
        val sheetLp = LayoutParams(
                LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.60f).toInt())
        sheetLp.gravity = Gravity.BOTTOM
        sheetLp.leftMargin = dp(12f)
        sheetLp.rightMargin = dp(12f)
        // 胶囊顶边在离底 80dp，面板底边再留 16dp。
        sheetLp.bottomMargin = dp(96f)
        panel.addView(sheet, sheetLp)
        sheet.addView(TextView(context).apply {
            text = "连接信息"
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

        startBtn.apply {
            text = "启动服务"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.brand, Theme.palette.brand, Radius.PILL)
            setOnClickListener { startService() }
        }
        sheet.addView(startBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42f)))
        stopBtn.apply {
            text = "停止服务"
            textSize = 12.5f
            setTextColor(Color.WHITE)
            background = rounded(Theme.palette.danger, Theme.palette.danger, Radius.PILL)
            setOnClickListener { confirmStop() }
        }
        sheet.addView(stopBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42f)))
        val close = TextView(context).apply {
            text = "关闭面板"
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
        // 传输服务默认关闭，只有用户点状态点或「开启传输服务」才启动。
        updateUi()
    }

    fun onResume() {
        updateUi()
    }

    fun handleBack(): Boolean {
        if (panelShown) {
            hidePanel()
            return true
        }
        if (ballsExpanded) {
            collapseBalls()
            return true
        }
        if (web.canGoBack()) {
            web.goBack()
            return true
        }
        return false
    }

    private fun setWebSheetOpen(open: Boolean) {
        if (sheetOpen == open) return
        sheetOpen = open
        if (open) {
            collapseBalls()
            mainBall.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
                    .setDuration(150L).withEndAction { mainBall.visibility = View.GONE }.start()
            onSheetVisibility?.invoke(true)
        } else {
            mainBall.animate().cancel()
            mainBall.visibility = if (TransferService.running) View.VISIBLE else View.GONE
            mainBall.alpha = 1f
            mainBall.scaleX = 1f
            mainBall.scaleY = 1f
            onSheetVisibility?.invoke(false)
        }
    }

    private fun openAbout() {
        // 保留网页原有的「让对端连接」组件，不再注入第二张卡片。
        web.evaluateJavascript(
                "if(window.__dlSwitchView)__dlSwitchView('about');" +
                        "document.querySelector('[data-view=\"about\"]')?.click();" +
                        "setTimeout(function(){var e=document.querySelector('#aboutBody .card');" +
                        "if(e)e.scrollIntoView({block:'start'});},120);", null)
    }

    private fun scanQr() {
        val activity = context as? android.app.Activity ?: return
        try {
            activity.startActivityForResult(
                    Intent(activity, QrScannerActivity::class.java), REQ_SCAN)
        } catch (t: Throwable) {
            toast("无法打开扫码页：${t.message ?: "相机不可用"}")
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_SCAN) {
            if (resultCode == android.app.Activity.RESULT_OK) {
                val text = data?.getStringExtra(QrScannerActivity.EXTRA_RESULT).orEmpty()
                val ok = Regex("^https?://[^\\s]+$").matches(text)
                if (!ok) {
                    toast("不是有效的连接码")
                } else {
                    // 原「让对端连接」的扫码逻辑是跳转到扫到的地址；这里保持同样行为。
                    toast("扫码成功，正在连接…")
                    web.loadUrl(text)
                }
            }
            return
        }
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
        if (TransferService.running || starting || stopping) return
        manualStopped = false
        val activity = context as? android.app.Activity
        if (Build.VERSION.SDK_INT >= 33 && activity != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5002)
        }
        starting = true
        startFailed = false
        errorView.visibility = GONE
        closedView.visibility = GONE
        status.text = "传输 · " + Build.MODEL
        updateStateToggle()
        updatePanelActions()
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
                    handler.postDelayed({ openAbout() }, 900L)
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
        if (stopping) {
            status.text = "传输 · " + Build.MODEL
            status.setTextColor(Theme.palette.muted)
            updateStateToggle()
            updatePanelActions()
            return
        }
        if (TransferService.running && TransferService.port > 0) {
            starting = false
            errorView.visibility = GONE
            closedView.visibility = GONE
            web.visibility = View.VISIBLE
            mainBall.visibility = if (panelShown) View.GONE else View.VISIBLE
            val p = TransferService.port
            status.text = "传输 · " + Build.MODEL
            status.setTextColor(Theme.palette.text2)
            port.text = "$p"
            port.visibility = View.VISIBLE
            updateStateToggle()
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
            updatePanelActions()
            maybeShowHint()
        } else {
            starting = false
            web.visibility = View.INVISIBLE
            closedView.visibility = View.VISIBLE
            port.visibility = View.GONE
            mainBall.visibility = View.GONE
            for (i in satBalls.indices) {
                satBalls[i].visibility = View.GONE
                satLabels[i].visibility = View.GONE
            }
            status.text = "传输 · " + Build.MODEL
            status.setTextColor(Theme.palette.muted)
            updateStateToggle()
            updatePanelActions()
        }
    }

    private fun toggleService() {
        when {
            TransferService.running -> confirmStop()
            starting || stopping -> Unit
            else -> {
                pendingShowPanel = true
                startService()
            }
        }
    }

    private fun updateStateToggle() {
        val color: Int
        val label: String
        when {
            starting -> {
                color = Color.rgb(156, 163, 175)
                label = "启动中…"
            }
            stopping -> {
                color = Color.rgb(156, 163, 175)
                label = "停止中…"
            }
            TransferService.running -> {
                color = Color.rgb(34, 197, 94)
                label = "开启"
            }
            startFailed -> {
                color = Color.rgb(156, 163, 175)
                label = "启动失败"
            }
            else -> {
                color = Color.rgb(245, 158, 11)
                label = "关闭"
            }
        }
        stateDot.setBackground(rounded(color, color, Radius.PILL))
        stateText.text = label
    }

    private fun updatePanelActions() {
        startBtn.visibility = if (TransferService.running || stopping) View.GONE else View.VISIBLE
        stopBtn.visibility = if (TransferService.running || stopping) View.VISIBLE else View.GONE
        startBtn.isEnabled = !starting && !stopping
        stopBtn.isEnabled = !stopping
        stopBtn.text = if (stopping) "停止中…" else "停止服务"
    }

    private fun fail(message: String) {
        starting = false
        pendingShowPanel = false
        startFailed = true
        status.text = "传输 · " + Build.MODEL + " · 未启动"
        status.setTextColor(Theme.palette.muted)
        errorText.text = message
        errorView.visibility = VISIBLE
        closedView.visibility = GONE
        updateStateToggle()
        updatePanelActions()
    }

    private fun showPanel() {
        if (panelShown) return
        collapseBalls()
        mainBall.visibility = View.GONE
        for (i in satBalls.indices) {
            satBalls[i].visibility = View.GONE
            satLabels[i].visibility = View.GONE
        }
        panelShown = true
        onPanelVisibility?.invoke(true)
        updatePanelActions()
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
            onPanelVisibility?.invoke(false)
            mainBall.visibility = if (TransferService.running) View.VISIBLE else View.GONE
            updateUi()
        })
    }

    private fun confirmStop() {
        val activity = context as? android.app.Activity ?: return
        Dialogs.showMessage(activity, "停止传输服务",
                "停止后电脑和其他设备将立即断开；手机上打开的文件页面也会停止。",
                positive = "停止服务", onPositive = {
                    stopService()
                },
                negative = "取消", onNegative = {})
    }

    private fun stopService() {
        if (stopping || !TransferService.running) return
        stopping = true
        manualStopped = true
        pendingShowPanel = false
        status.text = "传输 · " + Build.MODEL
        status.setTextColor(Theme.palette.muted)
        if (panelShown) hidePanel()
        updateStateToggle()
        updatePanelActions()
        try {
            context.stopService(Intent(context, TransferService::class.java))
        } catch (_: Throwable) {
        }
        loadedUrl = ""
        web.loadUrl("about:blank")
        web.visibility = View.INVISIBLE
        var tries = 0
        val job = object : Runnable {
            override fun run() {
                if (!TransferService.running) {
                    stopping = false
                    stopPoll = null
                    updateUi()
                    return
                }
                tries++
                if (tries >= 25) {
                    stopping = false
                    stopPoll = null
                    status.text = "停止超时，请重试"
                    status.setTextColor(Theme.palette.muted)
                    updatePanelActions()
                    toast("停止超时，请重试")
                    return
                }
                handler.postDelayed(this, 200L)
            }
        }
        stopPoll = job
        handler.postDelayed(job, 200L)
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
                "这个页面也能在电脑浏览器里打开，点右上角端口数字查看地址",
                Toast.LENGTH_LONG).show()
    }

    private fun lanIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (nif in interfaces.sortedBy {
                when {
                    it.name == "wlan0" -> 0
                    it.name.startsWith("wlan") -> 1
                    else -> 2
                }
            }) {
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

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(max(1, dp(1f)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
