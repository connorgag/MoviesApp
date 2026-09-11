package com.example.movieapp

import android.app.DownloadManager
import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID

object DownloadHelper {

    /**
     * Checks if there are any active downloads in the system for this app.
     * Includes Running, Pending, and Paused downloads.
     * Also checks for active YouTube background workers.
     */
    fun hasActiveDownloads(context: Context, excludeWorkerId: UUID? = null): Boolean {
        // 0. Check internal MovieDownloadService & DownloadRepository
        if (DownloadRepository.hasActiveOrPendingDownloads()) {
            return true
        }

        // 1. Check DownloadManager
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_RUNNING or 
            DownloadManager.STATUS_PENDING or 
            DownloadManager.STATUS_PAUSED
        )
        
        val cursor = try {
            downloadManager.query(query)
        } catch (_: Exception) {
            null
        }

        val hasActiveDm = cursor?.use { it.count > 0 } ?: false
        if (hasActiveDm) return true

        // 2. Check WorkManager for youtube_download workers
        val workManager = WorkManager.getInstance(context)
        val workInfos = try {
            workManager.getWorkInfosByTag("youtube_download").get()
        } catch (_: Exception) {
            null
        }

        val hasActiveWorker = workInfos?.any { workInfo ->
            !workInfo.state.isFinished && (excludeWorkerId == null || workInfo.id != excludeWorkerId)
        } ?: false

        return hasActiveWorker
    }

    /**
     * Helper to check if the app is currently in the foreground.
     */
    fun isAppInForeground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        val packageName = context.packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND && 
                appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }
}
