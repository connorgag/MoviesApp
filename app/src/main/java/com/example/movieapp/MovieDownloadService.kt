package com.example.movieapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MovieDownloadService : Service() {

    private val TAG = "MovieDownloadService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var downloadJob: Job? = null

    companion object {
        const val CHANNEL_ID = "movie_downloads_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.movieapp.action.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.movieapp.action.CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        fun startDownload(context: Context, downloadId: String) {
            val intent = Intent(context, MovieDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, downloadId: String) {
            val intent = Intent(context, MovieDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID)

        Log.d(TAG, "onStartCommand action=$action, downloadId=$downloadId")

        when (action) {
            ACTION_CANCEL -> {
                if (downloadId != null) {
                    DownloadRepository.cancelDownload(downloadId)
                }
            }
            ACTION_START, null -> {
                // Keep-alive or start queue
            }
        }

        if (DownloadRepository.hasActiveOrPendingDownloads()) {
            startForeground(NOTIFICATION_ID, buildNotification("Starting download...", 0, "Initializing"))
            ensureProcessingLoop()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun ensureProcessingLoop() {
        if (downloadJob?.isActive == true) return

        downloadJob = serviceScope.launch {
            while (DownloadRepository.hasActiveOrPendingDownloads()) {
                val next = DownloadRepository.getNextQueuedOrRetrying()
                if (next == null) {
                    // All remaining items might be PAUSED or COMPLETED
                    delay(1000)
                    if (!DownloadRepository.hasActiveOrPendingDownloads()) break
                    continue
                }

                downloadSingleFile(next)
            }

            Log.d(TAG, "Queue finished. Stopping foreground service.")
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun downloadSingleFile(item: ActiveDownload) {
        var retryCount = 0
        var backoffMs = 3000L

        while (true) {
            val currentItem = DownloadRepository.getDownload(item.id) ?: return
            if (currentItem.status == DownloadStatus.PAUSED) {
                return
            }

            // Always ensure Tailscale is connected before starting network attempt
            TailscaleController.connect(applicationContext)

            DownloadRepository.updateStatus(item.id, if (retryCount > 0) DownloadStatus.RETRYING else DownloadStatus.DOWNLOADING)
            val retryInfo = if (retryCount > 0) " (Retry #$retryCount)" else ""
            updateNotification("Downloading ${currentItem.title}$retryInfo", currentItem.progress, "Connecting to server...")

            val success = try {
                executeHttpDownload(currentItem)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${currentItem.title}: ${e.message}", e)
                false
            }

            if (success) {
                DownloadRepository.updateStatus(item.id, DownloadStatus.COMPLETED)
                showCompletionNotification(currentItem.title)
                return
            }

            // Check if user cancelled while downloading
            if (DownloadRepository.getDownload(item.id) == null) {
                Log.d(TAG, "Download was cancelled by user: ${item.title}")
                return
            }

            retryCount++
            DownloadRepository.updateStatus(item.id, DownloadStatus.RETRYING, "Network error. Retrying in ${backoffMs / 1000}s...")
            updateNotification("Retrying ${currentItem.title}", currentItem.progress, "Reconnecting Tailscale/Network in ${backoffMs / 1000}s...")

            delay(backoffMs)
            backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(30000L)
        }
    }

    private suspend fun executeHttpDownload(item: ActiveDownload): Boolean = withContext(Dispatchers.IO) {
        val destFile = item.destinationFile
        val parentDir = destFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        var existingLength = if (destFile.exists()) destFile.length() else 0L

        val url = URL(item.url)
        var connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true

        if (!item.cookie.isNull_or_empty()) {
            connection.setRequestProperty("Cookie", item.cookie)
        }
        if (!item.userAgent.isNull_or_empty()) {
            connection.setRequestProperty("User-Agent", item.userAgent)
        }

        // Support HTTP Range resumption
        if (existingLength > 0) {
            connection.setRequestProperty("Range", "bytes=$existingLength-")
        }

        var responseCode = connection.responseCode

        // Handle redirect manually if needed
        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
            val newUrlStr = connection.getHeaderField("Location")
            if (newUrlStr != null) {
                connection.disconnect()
                val redirectUrl = URL(newUrlStr)
                connection = redirectUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                if (!item.cookie.isNull_or_empty()) connection.setRequestProperty("Cookie", item.cookie)
                if (!item.userAgent.isNull_or_empty()) connection.setRequestProperty("User-Agent", item.userAgent)
                if (existingLength > 0) connection.setRequestProperty("Range", "bytes=$existingLength-")
                responseCode = connection.responseCode
            }
        }

        var appendMode = false
        var totalBytes = -1L

        when (responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> {
                // 206 Partial Content
                appendMode = true
                val contentLength = connection.contentLengthLong
                totalBytes = if (contentLength > 0) existingLength + contentLength else -1L
            }
            HttpURLConnection.HTTP_OK -> {
                // 200 OK (server doesn't support range or downloading from start)
                if (existingLength > 0) {
                    destFile.delete()
                    existingLength = 0L
                }
                appendMode = false
                totalBytes = connection.contentLengthLong
            }
            416 -> {
                // Range Not Satisfiable: destination file is already full length
                DownloadRepository.updateProgress(item.id, existingLength, existingLength, 0L)
                return@withContext true
            }
            else -> {
                Log.e(TAG, "Server returned HTTP $responseCode for ${item.url}")
                return@withContext false
            }
        }

        val inputStream: InputStream = connection.inputStream
        val outputStream = FileOutputStream(destFile, appendMode)

        var downloadedBytes = existingLength
        val buffer = ByteArray(64 * 1024) // 64 KB buffer

        var lastUpdateTime = System.currentTimeMillis()
        var bytesSinceLastUpdate = 0L
        var speedBytesPerSec = 0L

        try {
            while (true) {
                val currentInRepo = DownloadRepository.getDownload(item.id)
                if (currentInRepo == null || currentInRepo.status == DownloadStatus.PAUSED) {
                    outputStream.flush()
                    outputStream.close()
                    inputStream.close()
                    connection.disconnect()
                    return@withContext false
                }

                val read = inputStream.read(buffer)
                if (read == -1) break

                outputStream.write(buffer, 0, read)
                downloadedBytes += read
                bytesSinceLastUpdate += read

                val now = System.currentTimeMillis()
                val elapsed = now - lastUpdateTime
                if (elapsed >= 1000) {
                    speedBytesPerSec = (bytesSinceLastUpdate * 1000) / elapsed
                    bytesSinceLastUpdate = 0L
                    lastUpdateTime = now

                    DownloadRepository.updateProgress(item.id, downloadedBytes, totalBytes, speedBytesPerSec)
                    val progressInt = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                    val subtext = "${formatSize(downloadedBytes)} / ${formatSize(totalBytes)} (${formatSpeed(speedBytesPerSec)})"
                    updateNotification("Downloading ${item.title}", progressInt, subtext)
                }
            }

            outputStream.flush()
            DownloadRepository.updateProgress(item.id, downloadedBytes, if (totalBytes > 0) totalBytes else downloadedBytes, 0L)
            return@withContext true
        } finally {
            try { outputStream.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            try { connection.disconnect() } catch (_: Exception) {}
        }
    }

    private fun CharSequence?.isNull_or_empty(): Boolean = this == null || this.isEmpty()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Movie Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active movie download progress over VPN"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, progress: Int, subtext: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtext)
            .setSmallIcon(R.drawable.ic_downloads)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateNotification(title: String, progress: Int, subtext: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, progress, subtext))
    }

    private fun showCompletionNotification(title: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_downloads)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown"
        val mb = bytes / (1024.0 * 1024.0)
        val gb = mb / 1024.0
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.2f GB", gb)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(Locale.US, "%.0f KB/s", kb)
        }
    }
}
