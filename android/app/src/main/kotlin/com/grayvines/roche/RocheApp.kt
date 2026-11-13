package com.grayvines.roche

import android.app.Application

class RocheApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        graph.prewarm()
    }
}
