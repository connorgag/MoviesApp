package com.example.movieapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DownloadReceiver : BroadcastReceiver() {
    private val TAG = "DownloadReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            Log.d(TAG, "Download complete received")
            
            // If the app is in the background and no more downloads are active, disconnect
            if (!DownloadHelper.isAppInForeground(context)) {
                if (!DownloadHelper.hasActiveDownloads(context)) {
                    Log.d(TAG, "All downloads finished and app is in background - Disconnecting Tailscale")
                    TailscaleController.disconnect(context)
                } else {
                    Log.d(TAG, "Download finished, but others are still active - staying connected")
                }
            }
        }
    }
}
