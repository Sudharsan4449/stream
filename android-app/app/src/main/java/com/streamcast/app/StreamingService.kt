package com.streamcast.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat

class StreamingService : Service() {

    private var server: WebDavServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_TREE_URI = "EXTRA_TREE_URI"
        const val EXTRA_PORT = "EXTRA_PORT"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "streamcast_service_channel"

        // Public static states for UI synchronization
        @Volatile
        var isServiceRunning = false
            private set
        @Volatile
        var activeTreeUri: Uri? = null
            private set
        @Volatile
        var activePort = 8080
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uriString = intent.getStringExtra(EXTRA_TREE_URI)
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    isServiceRunning = true
                    activeTreeUri = uri
                    activePort = port
                    startServiceForeground(uri, port)
                }
            }
            ACTION_STOP -> {
                stopServiceInternal()
            }
        }
        return START_NOT_STICKY
    }

    private fun startServiceForeground(uri: Uri, port: Int) {
        // 1. Create Notification Channel
        createNotificationChannel()

        // 2. Pending Intent to open MainActivity
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Pending Intent to Stop Service via Notification Button
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamCast Server Active")
            .setContentText("WebDAV streaming service is active.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setOngoing(true)
            .build()

        // 4. Start Foreground with type mediaPlayback for Android 14 compliance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 5. Acquire Locks
        acquireLocks()

        // 6. Start Ktor WebDAV Server
        try {
            val storageEngine = SAFStorageEngine(applicationContext, uri)
            server = WebDavServer(applicationContext, storageEngine, port)
            server?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            stopServiceInternal()
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamCast::ServerWakeLock").apply {
            acquire()
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StreamCast::ServerWifiLock").apply {
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
    }

    private fun stopServiceInternal() {
        isServiceRunning = false
        activeTreeUri = null
        activePort = 8080
        server?.stop()
        server = null
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StreamCast Media Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServiceInternal()
    }
}
