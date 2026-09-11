package com.example.movieapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object TailscaleController {

    private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    private const val TAILSCALE_RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"
    private const val ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN"
    private const val TAG = "TailscaleController"

    fun isTailscaleInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun connect(context: Context) = send(context, ACTION_CONNECT)

    fun disconnect(context: Context) = send(context, ACTION_DISCONNECT)

    private fun send(context: Context, action: String) {
        if (!isTailscaleInstalled(context)) {
            Log.w(TAG, "Tailscale is not installed - cannot send $action")
            return
        }
        val intent = Intent(action).apply {
            setClassName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER)
        }
        try {
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent $action to Tailscale")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send $action to Tailscale", e)
        }
    }
}