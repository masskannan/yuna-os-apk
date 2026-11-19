package com.puyal.os.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.puyal.os.service.PuyalCoreService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                val svcIntent = Intent(context, PuyalCoreService::class.java).putExtra("boot_only", true)
                ContextCompat.startForegroundService(context, svcIntent)
            } catch (_: Exception) {}
        }
    }
}
