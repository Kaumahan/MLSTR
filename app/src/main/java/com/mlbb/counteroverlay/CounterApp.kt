package com.mlbb.counteroverlay

import android.app.Application

class CounterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
