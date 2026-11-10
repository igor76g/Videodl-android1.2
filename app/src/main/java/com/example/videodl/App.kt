package com.example.videodl

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.start(this)
    }
}
