package com.spacelens.app

import android.app.Application
import com.spacelens.app.core.CrashLog

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 尽早安装，覆盖主线程之外的崩溃。
        CrashLog.install(this)
    }
}
