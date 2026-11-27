package com.example

import android.app.Application
import engine.db.currentAndroidContext
import engine.fs.TempImageStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Application entry point. Publishes the process-wide Android context so the
 * engine layer (databases, signal store, hardware tools) can resolve services
 * without being wired to any particular Activity.
 *
 * It also bounds the vision-tool image cache at start-up, so a cold launch reclaims whatever
 * an earlier session left behind instead of only pruning on the next capture.
 */
class ApexPosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        currentAndroidContext = { applicationContext }
        thread(name = "temp-image-prune", isDaemon = true) {
            runCatching {
                TempImageStore.prune(File(cacheDir, TempImageStore.DIR), keep = 2)
                TempImageStore.pruneLegacy(cacheDir)
            }
        }
    }
}
