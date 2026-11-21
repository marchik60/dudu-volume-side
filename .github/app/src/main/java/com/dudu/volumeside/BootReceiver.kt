package com.dudu.volumeside

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (prefs.displayMode != 0 && Settings.canDrawOverlays(context)) {
            val svc = Intent(context, VolumeOverlayService::class.java)
            context.startForegroundService(svc)
        }
    }
}
