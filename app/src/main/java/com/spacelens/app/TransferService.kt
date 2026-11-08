package com.spacelens.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spacelens.app.core.RecvDir
import java.io.File
import java.net.ServerSocket

/**
 * 前台服务：持有内置 HTTP 服务，保证退到后台后进程不被系统杀掉。
 * 端口不写死：8848 被占就退 8850，再被占继续找 8851-8860。
 */
class TransferService : Service() {

    companion object {
        const val CHANNEL_ID = "filelens-transfer"
        const val NOTIFY_ID = 2101
        @Volatile var running = false
        @Volatile var port = 0
        @Volatile var pin = ""
        @Volatile var lastError: String? = null
        /** 本机 WebView 用的界面令牌；回环请求仍按本机处理。 */
        @Volatile var localToken: String = ""

        fun choosePort(): Int {
            for (p in intArrayOf(8848, 8850, 8851, 8852, 8853, 8854, 8855, 8856, 8857, 8858, 8859, 8860)) {
                try {
                    ServerSocket(p).use { }
                    return p
                } catch (_: Throwable) {
                }
            }
            return 0
        }
    }

    private var server: HttpServer? = null

    override fun onCreate() {
        super.onCreate()
        lastError = null
        val chosen = choosePort()
        if (chosen <= 0) {
            running = false
            lastError = "8848–8860 端口全部被占用"
            stopSelf()
            return
        }
        port = chosen
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
        localToken = java.security.SecureRandom().let { rnd ->
            val bytes = ByteArray(24)
            rnd.nextBytes(bytes)
            android.util.Base64.encodeToString(
                    bytes,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
        }
        val http = HttpServer(this, chosen, { storageRoot() }, { recvRoot() }, localToken)
        if (!http.start()) {
            lastError = http.lastError ?: "HTTP 服务绑定失败"
            server = null
            running = false
            stopSelf()
            return
        }
        server = http
        pin = http.pin
        running = true
    }

    private fun storageRoot(): File {
        val granted = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        return if (granted) Environment.getExternalStorageDirectory()
        else getExternalFilesDir(null) ?: filesDir
    }

    private fun recvRoot(): File = RecvDir.resolve(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        port = 0
        pin = ""
        try {
            server?.stop()
        } catch (_: Throwable) {
        }
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("FileLens · 互传运行中")
                .setContentText("端口 $port · 打开 App 查看地址")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "FileLens 互传", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
