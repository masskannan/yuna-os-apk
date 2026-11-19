package com.puyal.os.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.puyal.os.cloud.YunaCloudWorker
import com.puyal.os.guard.YunaTierGuards
import com.puyal.os.nativebridge.YunaNative
import com.puyal.os.service.PuyalAccessService
import com.puyal.os.service.PuyalNotifService
import java.io.File
import java.util.Locale

class YunaBridge(private val ctx: Context, private val onVoiceReq: (String) -> Unit) {
    private val mainH = Handler(Looper.getMainLooper())
    @Volatile private var torchOn = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchOn = enabled }
            }, mainH)
        } catch (_: Exception) {}
        try {
            tts = TextToSpeech(ctx.applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale("ta", "IN"))
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale("en", "IN"))
                    }
                    ttsReady = true
                }
            }
        } catch (_: Exception) {}
    }

    private fun speakShort(msg: String) {
        if (ttsReady) {
            try { tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "yuna_tts") } catch (_: Exception) {}
        }
    }

    private fun startOnMain(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mainH.post { try { ctx.startActivity(intent) } catch (_: Exception) {} }
        return true
    }

    private fun findFlashCameraId(cm: CameraManager): String? {
        for (id in cm.cameraIdList) {
            val hasFlash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) return id
        }
        return cm.cameraIdList.firstOrNull()
    }

    @JavascriptInterface fun scanScreen(): String = PuyalAccessService.instance?.scanNow()?.ifBlank { "No readable text" } ?: "Enable YUNA Accessibility"
    @JavascriptInterface fun getWaSummary(): String = PuyalNotifService.getSummary()
    @JavascriptInterface fun clickOnScreen(label: String): String = PuyalAccessService.instance?.clickByText(label) ?: "Enable YUNA Accessibility"
    @JavascriptInterface fun checkSensitive(txt: String): Boolean = YunaNative.hasSensitiveToken(txt)
    @JavascriptInterface fun startVoice(lang: String) { mainH.post { onVoiceReq(lang.ifBlank { "ta-IN" }) } }
    @JavascriptInterface fun enqueueCloudTask(prompt: String): String { YunaCloudWorker.enqueue(ctx, prompt); return "Queued" }
    @JavascriptInterface fun startBluetoothMic(): String = com.puyal.os.audio.PuyalAudioGuardian.enableBluetoothSco(ctx)

    @JavascriptInterface
    fun verifyOwnerBiometric() {
        val act = ctx as? androidx.fragment.app.FragmentActivity ?: return
        act.runOnUiThread {
            val exec = ContextCompat.getMainExecutor(ctx)
            val prompt = androidx.biometric.BiometricPrompt(act, exec, object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {})
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("YUNA Voice Shield")
                .setSubtitle("Verify Kannan identity")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(info)
        }
    }

    @JavascriptInterface
    fun saveObsidianNote(title: String, body: String): String {
        return try {
            val dir = File(ctx.filesDir, "obsidian_vault").apply { mkdirs() }
            val safe = title.replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { "YUNA_Note" }
            File(dir, "$safe.md").writeText(body)
            "Saved: $safe.md"
        } catch (e: Exception) { "Save err: ${e.message}" }
    }

    @JavascriptInterface
    fun getCrashLog(): String {
        val f = File(ctx.filesDir, "crash_log.txt")
        return if (f.exists()) f.readText().takeLast(1500) else "No crashes recorded"
    }

    @JavascriptInterface
    fun getPermissionStatus(): String {
        val acc = PuyalAccessService.instance != null
        val ovl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        val mic = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val wr = Settings.System.canWrite(ctx)
        val nls = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true
        return "{\"accessibility\":$acc,\"overlay\":$ovl,\"mic\":$mic,\"camera\":$cam,\"writeSettings\":$wr,\"notifListener\":$nls}"
    }

    @JavascriptInterface
    fun executeAction(action: String, target: String): String {
        if (YunaTierGuards.emergencyKilled) return "Blocked: Emergency Kill-Switch active"
        val acc = PuyalAccessService.instance
        val out = when (action) {
            "GLOBAL_BACK" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_BACK) == true) "Back OK" else "Enable YUNA Accessibility"
            "GLOBAL_HOME" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_HOME) == true) "Home OK" else "Enable YUNA Accessibility"
            "GLOBAL_RECENTS" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_RECENTS) == true) "Recents OK" else "Enable YUNA Accessibility"
            "CLEAR_RECENTS" -> acc?.clearAllRecents() ?: "Enable YUNA Accessibility"
            "GLOBAL_NOTIFICATIONS" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true) "Notifications Pulled" else "Enable YUNA Accessibility"
            "GLOBAL_QUICK_SETTINGS" -> if (acc?.runNav(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true) "Quick Settings Pulled" else "Enable YUNA Accessibility"
            "TOGGLE_WIFI" -> acc?.pullQuickSettingsAndTap(listOf("wi-fi", "wifi", "internet", "wlan")) ?: "Enable YUNA Accessibility"
            "TOGGLE_DATA" -> acc?.pullQuickSettingsAndTap(listOf("mobile data", "internet", "data")) ?: "Enable YUNA Accessibility"
            "TOGGLE_BT" -> acc?.pullQuickSettingsAndTap(listOf("bluetooth")) ?: "Enable YUNA Accessibility"
            "LOCK_SCREEN" -> acc?.lockScreenNow() ?: "Enable YUNA Accessibility"
            "SCREENSHOT" -> acc?.takeScreenshotNow() ?: "Enable YUNA Accessibility"
            "SCROLL_DOWN" -> acc?.scrollDirection(true) ?: "Enable YUNA Accessibility"
            "SCROLL_UP" -> acc?.scrollDirection(false) ?: "Enable YUNA Accessibility"
            "TYPE_SEARCH" -> acc?.typeAndSearch(target) ?: "Enable YUNA Accessibility"
            "OPEN_CAMERA" -> {
                startOnMain(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                "Camera Opened"
            }
            "OPEN_APP" -> {
                val pkgs = if (target == "com.whatsapp") listOf("com.whatsapp", "com.whatsapp.w4b") else listOf(target)
                var opened = false
                for (p in pkgs) {
                    if (acc?.launchAppFromService(p) == true) { opened = true; break }
                    val i = ctx.packageManager.getLaunchIntentForPackage(p)
                    if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); startOnMain(i); opened = true; break }
                }
                if (!opened && target == "com.google.android.youtube") {
                    startOnMain(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")))
                    opened = true
                }
                if (opened) "Opened $target" else "App not found: $target"
            }
            "DEEP_LINK_SEARCH" -> {
                val q = target.removePrefix("youtube:")
                val appUri = "vnd.youtube://results?search_query=" + Uri.encode(q)
                val webUri = "https://www.youtube.com/results?search_query=" + Uri.encode(q)
                if (acc?.launchUriFromService(appUri) == true || acc?.launchUriFromService(webUri) == true) "YouTube Search: $q"
                else { startOnMain(Intent(Intent.ACTION_VIEW, Uri.parse(webUri))); "YouTube Search: $q" }
            }
            "TORCH", "TORCH_ON", "TORCH_OFF" -> {
                try {
                    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = findFlashCameraId(cm) ?: return "No flash hardware"
                    val next = when (action) { "TORCH_ON" -> true; "TORCH_OFF" -> false; else -> !torchOn }
                    cm.setTorchMode(id, next)
                    torchOn = next
                    if (next) "Torch ON" else "Torch OFF"
                } catch (e: Exception) { "Torch err: ${e.message}" }
            }
            "BRIGHTNESS_UP", "BRIGHTNESS_DOWN" -> {
                if (!Settings.System.canWrite(ctx)) {
                    startOnMain(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    return "Grant Write Settings permission"
                }
                try { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) } catch (_: Exception) {}
                val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                val next = if (action == "BRIGHTNESS_UP") minOf(255, cur + 55) else maxOf(20, cur - 55)
                Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
                "Brightness: ${(next * 100) / 255}%"
            }
            "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val dir = when (action) { "VOLUME_UP" -> AudioManager.ADJUST_RAISE; "VOLUME_DOWN" -> AudioManager.ADJUST_LOWER; else -> AudioManager.ADJUST_TOGGLE_MUTE }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
                "Volume updated"
            }
            "MEDIA_NEXT", "MEDIA_PLAY_PAUSE" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val kc = if (action == "MEDIA_NEXT") KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, kc))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, kc))
                if (action == "MEDIA_NEXT") "Next track/video" else "Media Play/Pause"
            }
            "NET_PANEL" -> {
                val act = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
                startOnMain(Intent(act))
                "Net Panel Opened"
            }
            "DIAL" -> {
                startOnMain(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target)}")))
                "Dialer Opened"
            }
            else -> "Unknown action: $action"
        }
        speakShort(out)
        return out
    }
}
