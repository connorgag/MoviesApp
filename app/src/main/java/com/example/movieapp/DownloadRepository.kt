package com.example.movieapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    RETRYING,
    COMPLETED,
    FAILED
}

data class ActiveDownload(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val fileName: String,
    val destinationFile: File,
    val cookie: String? = null,
    val userAgent: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val progress: Int = 0,
    val speedBytesPerSec: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null
)

object DownloadRepository {

    private val _downloads = MutableStateFlow<List<ActiveDownload>>(emptyList())
    val downloads: StateFlow<List<ActiveDownload>> = _downloads.asStateFlow()

    fun enqueueDownload(
        url: String,
        title: String,
        fileName: String,
        destinationFile: File,
        cookie: String? = null,
        userAgent: String? = null
    ): ActiveDownload {
        val existing = _downloads.value.find { it.destinationFile.absolutePath == destinationFile.absolutePath && it.status != DownloadStatus.FAILED }
        if (existing != null) {
            return existing
        }

        val download = ActiveDownload(
            url = url,
            title = title,
            fileName = fileName,
            destinationFile = destinationFile,
            cookie = cookie,
            userAgent = userAgent,
            downloadedBytes = if (destinationFile.exists()) destinationFile.length() else 0L,
            status = DownloadStatus.QUEUED
        )

        _downloads.value = _downloads.value + download
        return download
    }

    fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    ) {
        val currentList = _downloads.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = currentList[index]
            val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
            currentList[index] = item.copy(
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                progress = progress,
                speedBytesPerSec = speedBytesPerSec,
                status = DownloadStatus.DOWNLOADING
            )
            _downloads.value = currentList
        }
    }

    fun updateStatus(id: String, status: DownloadStatus, errorMessage: String? = null) {
        val currentList = _downloads.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = currentList[index]
            currentList[index] = item.copy(
                status = status,
                errorMessage = errorMessage,
                speedBytesPerSec = if (status != DownloadStatus.DOWNLOADING) 0L else item.speedBytesPerSec
            )
            _downloads.value = currentList
        }
    }

    fun cancelDownload(id: String) {
        val currentList = _downloads.value.toMutableList()
        val item = currentList.find { it.id == id }
        if (item != null) {
            currentList.remove(item)
            _downloads.value = currentList
            if (item.destinationFile.exists() && item.status != DownloadStatus.COMPLETED) {
                try { item.destinationFile.delete() } catch (_: Exception) {}
            }
        }
    }

    fun hasActiveOrPendingDownloads(): Boolean {
        return _downloads.value.any { 
            it.status == DownloadStatus.QUEUED || 
            it.status == DownloadStatus.DOWNLOADING || 
            it.status == DownloadStatus.RETRYING ||
            it.status == DownloadStatus.PAUSED
        }
    }

    fun getNextQueuedOrRetrying(): ActiveDownload? {
        return _downloads.value.firstOrNull { 
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RETRYING 
        }
    }

    fun getDownload(id: String): ActiveDownload? {
        return _downloads.value.find { it.id == id }
    }
}
