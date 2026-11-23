package com.yuna.os.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.yuna.os.agent.YunaAgentOrchestrator
import android.content.ComponentName
import android.os.PowerManager
import com.yuna.os.accessibility.PuyalAccessService_v2
import com.yuna.os.notif.YunaNotifService
import com.yuna.os.vision.YunaScreenVision

class YunaOverlayPermActivity : Activity() {

    companion object {
        private const val REQ_OVERLAY = 4210
        private const val REQ_MEDIA_PROJ = 4211
        private const val EXTRA_MODE = "mode"
        private const val MODE_OVERLAY = "overlay"
        private const val MODE_SCREEN = "screen"

        fun launchGhostMode(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                context.startActivity(Intent(context, YunaOverlayPermActivity::class.java).apply {
                    putExtra(EXTRA_MODE, MODE_OVERLAY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                startGhostService(context)
            }
        }

        fun requestScreenProjection(context: Context) {
            context.startActivity(Intent(context, YunaOverlayPermActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_SCREEN)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }


        fun ensureAllPermissions(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                launchGhostMode(context); return
            }
            if (PuyalAccessService_v2.instance == null) {
                context.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_CAPTION)
                    .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "App Info -> ⋮ -> Allow restricted settings, பிறகு PuyalAccessService_v2 ஆன் செய்யவும்"))
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            if (YunaNotifService.instance == null) {
                context.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_CAPTION)
                    .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "App Info -> ⋮ -> Allow restricted settings, பிறகு YunaNotifService ஆன் செய்யவும்"))
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        private fun startGhostService(context: Context) {
            val i = Intent(context, YunaGhostOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_SCREEN -> {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                @Suppress("DEPRECATION")
                startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJ)
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_OVERLAY)
                } else {
                    startGhostService(this)
                    finish()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startGhostService(this)
            }
        } else if (requestCode == REQ_MEDIA_PROJ && resultCode == RESULT_OK && data != null) {
            val prefs = getSharedPreferences("yuna_prefs", MODE_PRIVATE)
            val baseUrl = prefs.getString("cloud_url", "https://yuna-ai-gateway-v4.masskannan719.workers.dev") ?: ""
            val token = prefs.getString("app_token", "") ?: ""
            val vision = YunaScreenVision(applicationContext, baseUrl, token)
            vision.initProjection(resultCode, data)
            YunaAgentOrchestrator.screenVision = vision
            YunaAgentOrchestrator.isScreenShareOn = true
            sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_CAPTION)
                .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "Screen Share தயார்!"))
        }
        finish()
    }
}
