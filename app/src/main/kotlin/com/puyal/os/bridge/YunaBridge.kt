package com.puyal.os.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.puyal.os.nativebridge.YunaNative
import com.puyal.os.service.PuyalAccessService
import com.puyal.os.service.PuyalNotifService

class YunaBridge(private val ctx: Context, private val onVoiceReq: (String) -> Unit) {
    private var torchOn = false
    private val allowlist = setOf(
        "OPEN_APP", "DEEP_LINK_SEARCH", "GLOBAL_BACK", "GLOBAL_HOME",
        "GLOBAL_RECENTS", "GLOBAL_NOTIFICATIONS", "GLOBAL_QUICK_SETTINGS",
        "TORCH", "BRIGHTNESS_UP", "BRIGHTNESS_DOWN", "NET_PANEL"
    )

    @JavascriptInterface fun scanScreen(): String = PuyalAccessService.instance?.scanNow()?.ifBlank { "No readable text" } ?: "Enable YUNA Accessibility"
    @JavascriptInterface fun getWaSummary(): String = PuyalNotifService.getSummary()
    @JavascriptInterface fun clickOnScreen(label: String): String = PuyalAccessService.instance?.clickByText(label) ?: "Enable YUNA Accessibility"
    @JavascriptInterface fun checkSensitive(txt: String): Boolean = YunaNative.hasSensitiveToken(txt)
    @JavascriptInterface fun startVoice(lang: String) { onVoiceReq(lang.ifBlank { "ta-IN" }) }

    @JavascriptInterface
    fun getPermissionStatus(): String {
        val ov = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        val acc = PuyalAccessService.instance != null
        val wr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(ctx) else true
        return "{\"overlay\":$ov,\"accessibility\":$acc,\"writeSettings\":$wr}"
    }

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
                else { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "YouTube Search: $q" }
            }
            "TORCH" -> {
                try {
                    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull() ?: return "No flash"
                    torchOn = !torchOn; cm.setTorchMode(id, torchOn)
                    if (torchOn) "Torch ON" else "Torch OFF"
                } catch (e: Exception) { "Torch err: ${e.message}" }
            }
            "BRIGHTNESS_UP", "BRIGHTNESS_DOWN" -> {
                if (!Settings.System.canWrite(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return "Grant Write Settings"
                }
                val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                val next = if (action == "BRIGHTNESS_UP") minOf(255, cur + 50) else maxOf(20, cur - 50)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
                "Brightness: ${(next * 100) / 255}%"
            }
            "NET_PANEL" -> {
                val act = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
                ctx.startActivity(Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Net Panel Opened"
            }
            else -> "Done"
        }
    }

    @JavascriptInterface
    fun enqueueCloudTask(prompt: String): String {
        com.puyal.os.cloud.YunaCloudWorker.enqueue(ctx, prompt)
        return "Queued for PUYAL Cloud when online"
    }

    @JavascriptInterface
    fun saveObsidianNote(title: String, md: String): String {
        return try {
            val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS), "Second_Brain").apply { mkdirs() }
            val safe = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "YUNA_Note" }
            val out = java.io.File(dir, "$safe.md")
            out.writeText(md)
            "Saved: ${out.name}"
        } catch (e: Exception) { "Save err: ${e.message}" }
    }

    @JavascriptInterface
    fun getCrashLog(): String {
        val f = java.io.File(ctx.filesDir, "crash_log.txt")
        return if (f.exists()) f.readText().takeLast(1200) else "No crashes recorded"
    }

}
