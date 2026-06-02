package com.streamcast.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class TvDownloadService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "ACTION_DOWNLOAD"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_FILENAME = "EXTRA_FILENAME"
        private const val CHANNEL_ID = "streamcast_download_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            val url = intent.getStringExtra(EXTRA_URL)
            val filename = intent.getStringExtra(EXTRA_FILENAME) ?: "downloaded_video.mp4"
            
            if (url != null) {
                startForegroundService(filename)
                startDownload(url, filename)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundService(filename: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "StreamCast Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Video")
            .setContentText(filename)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgress(progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Video")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startDownload(urlString: String, filename: String) {
        serviceScope.launch {
            try {
                // Find target directory
                val dirs = ContextCompat.getExternalFilesDirs(this@TvDownloadService, Environment.DIRECTORY_MOVIES)
                // Prefer index 1 (USB) if available, otherwise index 0 (Internal)
                val targetDir = if (dirs.size > 1 && dirs[1] != null) dirs[1] else dirs[0]
                if (targetDir == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TvDownloadService, "No storage found", Toast.LENGTH_SHORT).show()
                    }
                    stopSelf()
                    return@launch
                }

                val outputFile = File(targetDir, filename)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TvDownloadService, "Downloading to: ${outputFile.absolutePath}", Toast.LENGTH_LONG).show()
                }

                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode !in 200..299) {
                    throw Exception("Server returned HTTP ${connection.responseCode}")
                }

                val fileLength = connection.contentLength
                val input: InputStream = connection.inputStream
                val output = FileOutputStream(outputFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                var lastProgress = 0

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        if (progress > lastProgress) {
                            lastProgress = progress
                            updateProgress(progress)
                        }
                    }
                    output.write(data, 0, count)
                }
                
                output.flush()
                output.close()
                input.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TvDownloadService, "Download complete!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TvDownloadService, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
