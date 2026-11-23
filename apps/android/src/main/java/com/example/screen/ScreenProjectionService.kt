package com.example.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service required by Android 14+ before `MediaProjectionManager.getMediaProjection`.
 * It only owns the notification/lifecycle; the projection itself is held by ScreenCaptureHost.
 */
class ScreenProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen capture active")
            .setContentText("A workspace tool is capturing the terminal screen.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        active = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "screen_projection"
        private const val NOTIFICATION_ID = 0x5C12

        @Volatile var active: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenProjectionService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenProjectionService::class.java))
        }
    }
}
