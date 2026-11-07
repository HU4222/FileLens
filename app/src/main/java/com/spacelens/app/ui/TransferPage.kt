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
 * 地址/二维码放进「其他设备」底部面板，不再占据首屏。
 */
class TransferPage(context: Context) : FrameLayout(context) {

    companion object {
        const val REQ_FILE = 5010
        private const val MAIN_BALL_DP = 58f
        private const val SAT_BALL_DP = 46f
        private const val SAT_GAP_DP = 52f
        private const val BALL_MARGIN_DP = 16f
        private const val SAT_MARGIN_DP = 22f
    }

    private val status = TextView(context)
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
    private var pressAnim: Motion.Animation? = null
    private var longPress: Runnable? = null
    private var longPressed = false
    private var ballDownX = 0f
    private var ballDownY = 0f
    private val satViews = arrayOf("browse", "send", "clip", "about")
    private val satNames = arrayOf("浏览", "传文件", "剪贴板", "我的")
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
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
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

        buildBalls()
        buildPanel()
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateUi()
    }

    private fun buildBalls() {
        scrim.setBackgroundColor(Color.TRANSPARENT)
        scrim.isClickable = true
        scrim.visibility = View.GONE
        scrim.setOnClickListener { collapseBalls() }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        for (i in satViews.indices) {
            val ball = makeBall(SAT_BALL_DP.toInt(), Glass.fill(), 10f, satNames[i])
            val ballLp = LayoutParams(dp(SAT_BALL_DP), dp(SAT_BALL_DP), Gravity.BOTTOM or Gravity.RIGHT)
            ballLp.rightMargin = dp(SAT_MARGIN_DP)
            ballLp.bottomMargin = dp(SAT_MARGIN_DP)
            ball.visibility = View.INVISIBLE
            ball.alpha = 0f
            ball.scaleX = 0.7f
            ball.scaleY = 0.7f
            ball.setOnClickListener {
                switchView(satViews[i])
                collapseBalls()
            }
            addView(ball, ballLp)
            satBalls.add(ball)

            val label = TextView(context).apply {
                text = satNames[i]
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Theme.palette.text2)
                visibility = View.INVISIBLE
                alpha = 0f
            }
            val labelLp = LayoutParams(dp(MAIN_BALL_DP), dp(12f), Gravity.BOTTOM or Gravity.RIGHT)
            labelLp.rightMargin = dp(BALL_MARGIN_DP)
            labelLp.bottomMargin = 0
            addView(label, labelLp)
            satLabels.add(label)
        }

        mainBall.addView(View(context).apply {
            background = Glass.background(
                    Color.argb(56, 139, 135, 255), Glass.stroke(), dp(29f).toFloat())
            Glass.applyBlur(this, 20f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        mainLp.rightMargin = dp(BALL_MARGIN_DP)
        mainLp.bottomMargin = dp(BALL_MARGIN_DP)
        addView(mainBall, mainLp)
    }

    private fun makeBall(size: Int, fill: Int, textSize: Float, text: String): FrameLayout {
        val root = FrameLayout(context)
        val bg = View(context).apply {
            background = Glass.background(fill, Glass.stroke(), dp(size / 2f).toFloat())
            Glass.applyBlur(this, 20f)
        }
        root.addView(bg, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(TextView(context).apply {
            this.text = text
            this.textSize = textSize
            gravity = Gravity.CENTER
            setTextColor(Theme.palette.text)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.isClickable = true
        return root
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

    private fun expandBalls() {
        if (ballsExpanded) return
        ballAnim?.cancel()
        scrim.visibility = View.VISIBLE
        for (i in satBalls.indices) {
            satBalls[i].visibility = View.VISIBLE
            satLabels[i].visibility = View.VISIBLE
        }
        val fromX = FloatArray(satBalls.size) { satBalls[it].translationX }
        val fromScale = FloatArray(satBalls.size) { satBalls[it].scaleX }
        val fromAlpha = FloatArray(satBalls.size) { satBalls[it].alpha }
        ballAnim = Motion.tween(260, Ease::linear, { t ->
            val elapsed = t * 260f
            for (i in satBalls.indices) {
                val delay = i * 30f
                val local = ((elapsed - delay) / (260f - delay)).coerceIn(0f, 1f)
                val e = ballEase(local)
                val targetX = -dp(SAT_GAP_DP * (i + 1))
                satBalls[i].translationX = fromX[i] + (targetX - fromX[i]) * e
                satBalls[i].scaleX = fromScale[i] + (1f - fromScale[i]) * e
                satBalls[i].scaleY = satBalls[i].scaleX
                satBalls[i].alpha = fromAlpha[i] + (1f - fromAlpha[i]) * e
                satLabels[i].translationX = satBalls[i].translationX
                satLabels[i].alpha = satBalls[i].alpha
            }
        }, {
            ballsExpanded = true
        })
    }

    private fun collapseBalls() {
        if (!ballsExpanded) return
        ballAnim?.cancel()
        val fromX = FloatArray(satBalls.size) { satBalls[it].translationX }
        val fromScale = FloatArray(satBalls.size) { satBalls[it].scaleX }
        val fromAlpha = FloatArray(satBalls.size) { satBalls[it].alpha }
        ballAnim = Motion.tween(200, Ease::linear, { t ->
            val elapsed = t * 200f
            for (i in satBalls.indices) {
                val delay = i * 30f
                val local = ((elapsed - delay) / (200f - delay)).coerceIn(0f, 1f)
                val e = ballEase(local)
                satBalls[i].translationX = fromX[i] * (1f - e)
                satBalls[i].scaleX = fromScale[i] + (0.7f - fromScale[i]) * e
                satBalls[i].scaleY = satBalls[i].scaleX
                satBalls[i].alpha = fromAlpha[i] * (1f - e)
                satLabels[i].translationX = satBalls[i].translationX
                satLabels[i].alpha = satBalls[i].alpha
            }
        }, {
            for (i in satBalls.indices) {
                satBalls[i].visibility = View.INVISIBLE
                satLabels[i].visibility = View.INVISIBLE
            }
            scrim.visibility = View.GONE
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
            background = Glass.background(
                    Glass.fill(), Glass.stroke(), dp(Glass.radius()).toFloat())
            setOnClickListener { }
        }
        val sheetLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        sheetLp.gravity = Gravity.BOTTOM
        sheetLp.leftMargin = dp(12f)
        sheetLp.rightMargin = dp(12f)
        sheetLp.bottomMargin = dp(12f)
        panel.addView(sheet, sheetLp)
        sheet.addView(View(context).apply {
            setBackgroundColor(Glass.highlight())
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(0.5f)))

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
        // 用户主动停止后，本次 App 生命周期内不再自动拉起；面板里有手动「启动服务」。
        if (!TransferService.running && !manualStopped && !stopping) startService()
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
        errorView.visibility = GONE
        status.text = "正在启动…"
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
            status.text = "正在停止…"
            status.setTextColor(Theme.palette.muted)
            updatePanelActions()
            return
        }
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
            updatePanelActions()
            maybeShowHint()
        } else {
            web.visibility = View.INVISIBLE
            status.text = "未启动"
            status.setTextColor(Theme.palette.muted)
            updatePanelActions()
        }
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
        status.text = "未启动"
        status.setTextColor(Theme.palette.muted)
        errorText.text = message
        errorView.visibility = VISIBLE
        updatePanelActions()
    }

    private fun showPanel() {
        if (panelShown) return
        collapseBalls()
        panelShown = true
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
        status.text = "正在停止…"
        status.setTextColor(Theme.palette.muted)
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

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    private fun rounded(fill: Int, stroke: Int, radius: Float): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(fill)
                setStroke(max(1, dp(1f)), stroke)
            }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
