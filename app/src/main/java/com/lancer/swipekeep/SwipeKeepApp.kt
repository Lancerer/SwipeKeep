package com.lancer.swipekeep

import android.app.Application

class SwipeKeepApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
