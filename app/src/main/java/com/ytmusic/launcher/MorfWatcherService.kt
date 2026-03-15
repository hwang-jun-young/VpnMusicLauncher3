package com.ytmusic.launcher

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MorfWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var watchRunnable: Runnable? = null
    private var morfWasRunning = false

    companion object {
        const val CHANNEL_ID = "morf_watcher_channel"
        const val NOTIFICATION_ID = 1001
        const val CHECK_INTERVAL_MS = 3000L
        const val ACTION_MORF_STOPPED = "com.ytmusic.launcher.MORF_STOPPED"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MorfWatcherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MorfWatcherService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("모프 감시 중..."))
        startWatching()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWatching() {
        watchRunnable = object : Runnable {
            override fun run() {
                val isRunning = isMorfRunning()
                if (isRunning) {
                    morfWasRunning = true
                } else if (morfWasRunning) {
                    morfWasRunning = false
                    sendBroadcast(Intent(ACTION_MORF_STOPPED))
                    updateNotification("모프 종료 → WireGuard 해제")
                    handler.postDelayed({ stopSelf() }, 3000L)
                    return
                }
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchRunnable!!, 3000L)
    }

    private fun isMorfRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.processName.startsWith("app.morphe")
        } ?: false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "모프 감시", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YTmusic")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        watchRunnable?.let { handler.removeCallbacks(it) }
    }
}
