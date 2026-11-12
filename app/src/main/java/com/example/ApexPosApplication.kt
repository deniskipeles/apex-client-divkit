package com.example

import android.app.Application
import engine.db.currentAndroidContext

/**
 * Application entry point. Publishes the process-wide Android context so the
 * engine layer (databases, signal store, hardware tools) can resolve services
 * without being wired to any particular Activity.
 */
class ApexPosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        currentAndroidContext = { applicationContext }
    }
}
