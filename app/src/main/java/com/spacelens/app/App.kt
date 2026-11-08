package com.spacelens.app

import android.app.Application
import com.spacelens.app.core.CrashLog

class App : Application() {

    companion object {
        /** 冷启动打点用：Application 创建时刻（仅 Debug 有意义）。 */
        @JvmField var t0: Long = 0L
    }

    override fun attachBaseContext(base: android.content.Context) {
        if (BuildConfig.DEBUG) t0 = android.os.SystemClock.uptimeMillis()
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        // 尽早安装，覆盖主线程之外的崩溃。
        CrashLog.install(this)
    }
}
