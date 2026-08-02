package com.hqrecorder.app

import android.app.Application
import com.hqrecorder.app.core.AppContainer

class HqRecorderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
