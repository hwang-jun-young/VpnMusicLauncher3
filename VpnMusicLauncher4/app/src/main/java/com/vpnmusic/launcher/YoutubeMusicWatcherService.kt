package com.vpnmusic.launcher

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

class YoutubeMusicWatcherService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var watchRunnable: Runnable? = null
    private var morfWasRunning = false

    companion object {
        const val CHANNEL_ID = "vpn_watcher_channel"
        const val NOTIFICATION_ID = 1001
        const val CHECK_INTERVAL_MS = 3000L
        const val ACTION_VPN_DISCONNECTED = "com.vpnmusic.launcher.VPN_DISCONNECTED"

        fun start(context: Context) {
            val intent = Intent(context, YoutubeMusicWatcherService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, YoutubeMusicWatcherService::class.java)
            context.stopService(intent)
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
                    disconnectVpn()
                    updateNotification("모프 종료 → VPN 해제")
                    handler.postDelayed({ stopSelf() }, 5000L)
                    return
                }
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
        handler.postDelayed(watchRunnable!!, 3000L)
    }

    private fun isMorfRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return false
        return processes.any { it.processName.startsWith("app.morphe") }
    }

    private fun disconnectVpn() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(VpnHelper.OPENVPN_PACKAGE)
            intent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(this)
            }
        } catch (_: Exception) {}
        sendBroadcast(Intent(ACTION_VPN_DISCONNECTED))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "VPN 감시", NotificationManager.IMPORTANCE_LOW)
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
