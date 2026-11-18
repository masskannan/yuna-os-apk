package com.puyal.os.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.puyal.os.service.PuyalCoreService

data class PermissionStatusItem(
    val key: String,
    val label: String,
    val connected: Boolean,
    val detail: String,
    val openSettings: () -> Unit
)

object YunaPermissionsHub {

    fun inspectAll(context: Context): List<PermissionStatusItem> {
        val pkg = context.packageName
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val overlayOk = Settings.canDrawOverlays(context)
        val batteryOk = pm.isIgnoringBatteryOptimizations(pkg)
        val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val notifPostOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val enabledAccess = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val accessOk = enabledAccess.contains(pkg, ignoreCase = true)

        val enabledNotifListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
        val notifListenerOk = enabledNotifListeners.contains(pkg, ignoreCase = true)

        val gyroOk = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        val coreOk = PuyalCoreService.isRunning

        fun launchSettings(intent: Intent) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        fun openAppDetails() {
            launchSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }

        return listOf(
            PermissionStatusItem(
                key = "core",
                label = "Core Service (FGS)",
                connected = coreOk,
                detail = if (coreOk) "Connected" else "Not running",
                openSettings = { openAppDetails() }
            ),
            PermissionStatusItem(
                key = "accessibility",
                label = "Accessibility (Hands-Free)",
                connected = accessOk,
                detail = if (accessOk) "Connected" else "Not connected",
                openSettings = { launchSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ),
            PermissionStatusItem(
                key = "notif_listener",
                label = "Notification Access (WA Intel)",
                connected = notifListenerOk && notifPostOk,
                detail = if (notifListenerOk && notifPostOk) "Connected" else "Not connected",
                openSettings = { launchSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            ),
            PermissionStatusItem(
                key = "overlay",
                label = "Display Over Apps",
                connected = overlayOk,
                detail = if (overlayOk) "Connected" else "Not connected",
                openSettings = {
                    launchSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))
                }
            ),
            PermissionStatusItem(
                key = "battery",
                label = "Battery Unrestricted",
                connected = batteryOk,
                detail = if (batteryOk) "Connected" else "Optimized (May sleep)",
                openSettings = {
                    launchSettings(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
                }
            ),
            PermissionStatusItem(
                key = "mic",
                label = "Microphone (Voice)",
                connected = micOk,
                detail = if (micOk) "Connected" else "Not granted",
                openSettings = { openAppDetails() }
            ),
            PermissionStatusItem(
                key = "camera",
                label = "Camera & Torch",
                connected = camOk,
                detail = if (camOk) "Connected" else "Not granted",
                openSettings = { openAppDetails() }
            ),
            PermissionStatusItem(
                key = "sensors",
                label = "Gyroscope Sensor",
                connected = gyroOk,
                detail = if (gyroOk) "Hardware Ready" else "Not available",
                openSettings = { openAppDetails() }
            )
        )
    }

    fun buildConnectionReport(context: Context): String {
        val items = inspectAll(context)
        val okCount = items.count { it.connected }
        val lines = items.joinToString("\n") { item ->
            val mark = if (item.connected) "✔" else "✘"
            "$mark ${item.label}: ${item.detail}"
        }
        return "YUNA Connection Report ($okCount/${items.size} Connected)\n$lines"
    }
}
