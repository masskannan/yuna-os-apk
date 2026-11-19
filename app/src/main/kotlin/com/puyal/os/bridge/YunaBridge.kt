package com.puyal.os.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.puyal.os.service.PuyalAccessService

class YunaBridge(private val ctx: Context) {
    private var torchOn = false
    private val allowlist = setOf(
        "OPEN_APP", "DEEP_LINK_SEARCH", "GLOBAL_BACK", "GLOBAL_HOME",
        "GLOBAL_RECENTS", "GLOBAL_NOTIFICATIONS", "GLOBAL_QUICK_SETTINGS",
        "TORCH", "BRIGHTNESS_UP", "BRIGHTNESS_DOWN", "NET_PANEL"
    )

    @JavascriptInterface
    fun executeAction(action: String, target: String): String {
        if (action !in allowlist) return "Blocked by YunaTierGuards"
        val acc = PuyalAccessService.instance
        return when (action) {
            "GLOBAL_BACK" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_BACK) == true) "Back OK" else "Enable Accessibility"
            "GLOBAL_HOME" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_HOME) == true) "Home OK" else "Enable Accessibility"
            "GLOBAL_RECENTS" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_RECENTS) == true) "Recents OK" else "Enable Accessibility"
            "GLOBAL_NOTIFICATIONS" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true) "Shade OK" else "Enable Accessibility"
            "GLOBAL_QUICK_SETTINGS" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true) "QuickSettings OK" else "Enable Accessibility"
            "OPEN_APP" -> {
                if (acc?.launchAppFromService(target) == true) "Opened $target"
                else {
                    val i = ctx.packageManager.getLaunchIntentForPackage(target)
                    if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); "Opened $target" }
                    else "App not found: $target"
                }
            }
            "DEEP_LINK_SEARCH" -> {
                val q = target.removePrefix("youtube:")
                val uri = "https://www.youtube.com/results?search_query=" + Uri.encode(q)
                if (acc?.launchUriFromService(uri) == true) "YouTube Search: $q"
                else {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "YouTube Search: $q"
                }
            }
            "TORCH" -> {
                try {
                    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull() ?: return "No flash"
                    torchOn = !torchOn
                    cm.setTorchMode(id, torchOn)
                    if (torchOn) "Torch ON" else "Torch OFF"
                } catch (e: Exception) { "Torch err: ${e.message}" }
            }
            "BRIGHTNESS_UP", "BRIGHTNESS_DOWN" -> {
                if (!Settings.System.canWrite(ctx)) {
                    val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    return "Grant Write Settings"
                }
                val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                val next = if (action == "BRIGHTNESS_UP") minOf(255, cur + 50) else maxOf(20, cur - 50)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
                "Brightness: ${(next * 100) / 255}%"
            }
            "NET_PANEL" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ctx.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "Net Panel Opened"
            }
            else -> "Done"
        }
    }
}
