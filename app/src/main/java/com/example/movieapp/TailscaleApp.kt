package com.example.movieapp

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class TailscaleApp : Application(), DefaultLifecycleObserver {

    override fun onCreate() {
        super<Application>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        TailscaleController.connect(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!DownloadHelper.hasActiveDownloads(this)) {
            TailscaleController.disconnect(this)
        }
    }
}