package com.example.movieapp

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.net.HttpURLConnection
import java.net.URL

class YoutubeDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "YoutubeDownloadWorker"

    override suspend fun doWork(): Result {
        val videoUrl = inputData.getString("videoUrl") ?: return Result.failure()
        val apiUrl = inputData.getString("apiUrl") ?: return Result.failure()

        Log.d(TAG, "Starting background YouTube download request for: $videoUrl")

        return try {
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            
            val jsonInputString = "{\"url\": \"$videoUrl\"}"
            connection.outputStream.use { os ->
                val input = jsonInputString.toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage
            Log.d(TAG, "YouTube Download API response: $responseCode $responseMessage")
            
            if (responseCode in 200..299) {
                Result.success()
            } else {
                val errorDetail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "YouTube Download API error details: $errorDetail")
                Result.failure(workDataOf("error" to "Server returned $responseCode: $responseMessage"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube download request failed in background", e)
            Result.failure(workDataOf("error" to (e.message ?: "Unknown error")))
        } finally {
            checkAndDisconnectTailscaleIfNecessary()
        }
    }

    private fun checkAndDisconnectTailscaleIfNecessary() {
        val context = applicationContext
        if (!DownloadHelper.isAppInForeground(context)) {
            // Check if ANY other downloads are still running (excluding this worker which is about to finish)
            if (!DownloadHelper.hasActiveDownloads(context, id)) {
                Log.d(TAG, "YouTube background worker finished and app is in background - Disconnecting Tailscale")
                TailscaleController.disconnect(context)
            } else {
                Log.d(TAG, "YouTube background worker finished, but other downloads are still active")
            }
        }
    }
}
