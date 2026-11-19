package com.puyal.os.bridge

import android.Manifest
import android.app.role.RoleManager
import android.os.PowerManager
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.puyal.os.service.PuyalAccessService
import com.puyal.os.service.PuyalNotifService
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class YunaBridge(
    private val ctx: Context,
    private val onVoiceReq: (String) -> Unit,
    private val onStopVoiceReq: () -> Unit = {},
    private val onBiometricReq: () -> Unit = {},
    private val onTtsDone: () -> Unit = {},
    private val onMicLevel: (Float) -> Unit = {},
    private val onVoiceDone: (String) -> Unit = {}
) {
    private val mainH = Handler(Looper.getMainLooper())
    private var torchState = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isTamilTts = false
    @Volatile private var pendingTtsText: String? = null
    @Volatile private var pendingTtsPersona: String = "YUNA"
    private val audioMgr = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusReq: AudioFocusRequest? = null

    init {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { torchState = enabled }
            }, mainH)
        } catch (_: Exception) {}
        try {
            tts = TextToSpeech(ctx.applicationContext) { st ->
                if (st == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale("ta", "IN"))
                    isTamilTts = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)
                    if (isTamilTts) {
                        ttsReady = true
                        try {
                            val offlineFemale = tts?.voices?.firstOrNull {
                                it.locale.language == "ta" && !it.isNetworkConnectionRequired &&
                                    (it.name.contains("taf", true) || it.name.contains("female", true) || it.name.contains("local", true))
                            }
                            if (offlineFemale != null) tts?.voice = offlineFemale
                        } catch (_: Exception) {}
                    } else {
                        val enRes = tts?.setLanguage(Locale("en", "IN"))
                        ttsReady = (enRes != TextToSpeech.LANG_MISSING_DATA && enRes != TextToSpeech.LANG_NOT_SUPPORTED)
                    }
                    pendingTtsText?.let { pt -> pendingTtsText = null; speakPersona(pt, pendingTtsPersona) }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == "yuna_end") {
                                abandonDuckFocus()
                                mainH.post { onTtsDone() }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            abandonDuckFocus()
                            mainH.post { onTtsDone() }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            abandonDuckFocus()
                            mainH.post { onTtsDone() }
                        }
                    })
                }
            }
        } catch (_: Exception) {}
    }

    private fun requestDuckFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build()
                focusReq = req
                audioMgr.requestAudioFocus(req)
            }
        } catch (_: Exception) {}
    }

    private fun abandonDuckFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusReq?.let { audioMgr.abandonAudioFocusRequest(it) }
            }
        } catch (_: Exception) {}
    }

    private fun cleanForTts(raw: String): String {
        var s = raw.replace(Regex("[*_`~#⚡•●▶⏸➤]"), " ")
        if (isTamilTts) {
            val map = listOf(
                "YouTube" to "யூடியூப்", "Wi-Fi" to "வைஃபை", "WiFi" to "வைஃபை",
                "WhatsApp" to "வாட்ஸ்அப்", "Bluetooth" to "புளூடூத்", "Hotspot" to "ஹாட்ஸ்பாட்",
                "Camera" to "கேமரா", "Battery" to "பேட்டரி", "Alarm" to "அலாரம்",
                "Timer" to "டைமர்", "Accessibility" to "அணுகல்தன்மை", "Chrome" to "குரோம்",
                "Torch" to "டார்ச்", "Local" to "லோக்கல்", "Volume" to "வால்யூம்", "Brightness" to "பிரைட்னஸ்"
            )
            for ((k, v) in map) {
                s = s.replace(k, v, ignoreCase = true)
            }
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }


    private var mediaPlayer: MediaPlayer? = null

    @Volatile private var lastIncomingNumber: String = ""
    @Volatile private var lastCallerName: String = ""
    @Volatile private var callListenerRegistered: Boolean = false

    private fun isCallAudioActive(): Boolean {
        return try {
            val m = audioMgr.mode
            m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) { false }
    }

    private fun ensureCallStateListener() {
        if (callListenerRegistered) return
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            mainH.post {
                try {
                    @Suppress("DEPRECATION")
                    val psl = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            if (state == TelephonyManager.CALL_STATE_RINGING && !phoneNumber.isNullOrBlank()) {
                                lastIncomingNumber = phoneNumber
                                lastCallerName = resolveCallerName(phoneNumber)
                            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                                lastIncomingNumber = ""
                                lastCallerName = ""
                            }
                        }
                    }
                    @Suppress("DEPRECATION")
                    tm.listen(psl, PhoneStateListener.LISTEN_CALL_STATE)
                    callListenerRegistered = true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun resolveCallerName(phone: String): String {
        if (phone.isBlank()) return ""
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun setSpeakerphone(on: Boolean): String {
        return try {
            audioMgr.isSpeakerphoneOn = on
            if (on) "ஸ்பீக்கர்போன் இயக்கப்பட்டது." else "ஸ்பீக்கர்போன் அணைக்கப்பட்டது."
        } catch (e: Exception) {
            "ஸ்பீக்கர்போன் மாற்ற முடியவில்லை: ${e.message}"
        }
    }

    @JavascriptInterface
    fun getLastCallerInfo(): String {
        ensureCallStateListener()
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "அழைப்பாளர் பெயரை அறிய Phone State அனுமதியை வழங்கவும்."
        }
        val name = lastCallerName.ifBlank { resolveCallerName(lastIncomingNumber) }
        return when {
            name.isNotBlank() -> "$name உங்களை அழைக்கிறார்."
            lastIncomingNumber.isNotBlank() -> "$lastIncomingNumber எண்ணிலிருந்து அழைப்பு வருகிறது."
            isCallAudioActive() -> "தற்போது அழைப்பு நடந்துகொண்டிருக்கிறது (எண் விவரம் கிடைக்கவில்லை)."
            else -> "தற்போது எந்த அழைப்பும் வரவில்லை."
        }
    }


    @JavascriptInterface
    fun speakSmart(text: String, persona: String, baseUrl: String) {
        speakSmartWithVoice(text, persona, baseUrl, "ta-SG-VenbaNeural")
    }

    @JavascriptInterface
    fun speakSmartWithVoice(text: String, persona: String, baseUrl: String, voiceId: String) {
        if (persona.equals("SILENT", true) || text.isBlank()) { mainH.post { onTtsDone() }; return }
        if (isCallAudioActive()) { mainH.post { onTtsDone() }; return }
        val ep = baseUrl.trim().trimEnd('/')
        if (ep.startsWith("http")) {
            Thread {
                val mp3 = fetchCloudTts(ep + "/tts", text, voiceId.ifBlank { "ta-SG-VenbaNeural" })
                if (mp3 != null && mp3.exists() && mp3.length() > 500) {
                    mainH.post { playMp3File(mp3) }
                } else {
                    speakPersona(text, persona)
                }
            }.start()
        } else {
            speakPersona(text, persona)
        }
    }

    private fun fetchCloudTts(endpoint: String, text: String, voiceId: String = "ta-SG-VenbaNeural"): File? {
        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 5000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "YUNA-App/1.0")
            }
            val body = JSONObject().apply { put("text", text.take(480)); put("voice", voiceId) }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return null
            val out = File(ctx.cacheDir, "yuna_tts.mp3")
            conn.inputStream.use { inp -> out.outputStream().use { inp.copyTo(it) } }
            out
        } catch (_: Exception) { null }
    }

    private fun playMp3File(f: File) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            requestDuckFocus()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { mp ->
                    try { mp.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    abandonDuckFocus()
                    onTtsDone()
                }
                setOnErrorListener { mp, _, _ ->
                    try { mp.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    abandonDuckFocus()
                    onTtsDone()
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            abandonDuckFocus()
            onTtsDone()
        }
    }

    @JavascriptInterface
    fun speakPersona(text: String, persona: String) {
        if (!ttsReady && !persona.equals("SILENT", true) && text.isNotBlank()) { pendingTtsText = text; pendingTtsPersona = persona; return }
        if (!ttsReady || persona.equals("SILENT", true) || text.isBlank()) {
            mainH.post { onTtsDone() }
            return
        }
        val clean = cleanForTts(text)
        if (clean.isBlank()) { mainH.post { onTtsDone() }; return }
        mainH.post {
            try {
                requestDuckFocus()
                if (persona.equals("PUYAL", true)) {
                    tts?.setPitch(0.82f)
                    tts?.setSpeechRate(0.95f)
                } else {
                    tts?.setPitch(1.08f)
                    tts?.setSpeechRate(0.98f)
                }
                tts?.playSilentUtterance(120L, TextToSpeech.QUEUE_FLUSH, "yuna_warmup")
                val chunks = clean.split(Regex("(?<=[.!?।\\n])\\s+")).filter { it.isNotBlank() }
                val list = if (chunks.isEmpty()) listOf(clean) else chunks
                list.forEachIndexed { idx, part ->
                    val uid = if (idx == list.lastIndex) "yuna_end" else "yuna_$idx"
                    tts?.speak(part, TextToSpeech.QUEUE_ADD, null, uid)
                }
            } catch (_: Exception) {
                abandonDuckFocus()
                onTtsDone()
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        mainH.post {
            try { mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null } catch (_: Exception) {}
            try { tts?.stop() } catch (_: Exception) {}
            abandonDuckFocus()
            onTtsDone()
        }
    }


    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recEndpoint: String = ""
    @Volatile private var isRecordingWhisper = false

    @JavascriptInterface
    fun startCloudWhisper(baseUrl: String) {
        if (isCallAudioActive()) { mainH.post { android.widget.Toast.makeText(ctx, "அழைப்பு நடக்கிறது — மைக் பாதுகாக்கப்பட்டது", android.widget.Toast.LENGTH_SHORT).show(); onTtsDone() }; return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onVoiceReq("ta-IN")
            return
        }
        mainH.post {
            try {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                val out = File(ctx.cacheDir, "yuna_mic.m4a")
                recFile = out
                recEndpoint = baseUrl.trimEnd('/') + "/stt"
                val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
                mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(16000)
                mr.setAudioEncodingBitRate(32000)
                mr.setMaxDuration(20000)
                mr.setOutputFile(out.absolutePath)
                mr.prepare()
                mr.start()
                recorder = mr
                isRecordingWhisper = true
                Thread {
                    while (isRecordingWhisper) {
                        try {
                            val amp = recorder?.maxAmplitude ?: 0
                            val db = (amp / 3276.7f).coerceIn(0f, 10f)
                            mainH.post { onMicLevel(db) }
                            Thread.sleep(75)
                        } catch (_: Exception) { break }
                    }
                }.start()
            } catch (_: Exception) {
                isRecordingWhisper = false
                onVoiceReq("ta-IN")
            }
        }
    }

    @JavascriptInterface
    fun stopCloudWhisper() {
        if (!isRecordingWhisper) return
        isRecordingWhisper = false
        mainH.post {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            val f = recFile ?: return@post
            val ep = recEndpoint
            if (!f.exists() || f.length() < 1000) { onVoiceDone(""); return@post }
            Thread {
                val txt = uploadAudioToWhisper(ep, f)
                mainH.post { onVoiceDone(txt) }
            }.start()
        }
    }

    private fun uploadAudioToWhisper(endpoint: String, audio: File): String {
        return try {
            val boundary = "----YunaAudio" + UUID.randomUUID().toString().replace("-", "")
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 20000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { os ->
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"yuna_mic.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                audio.inputStream().use { it.copyTo(os) }
                os.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val resp = conn.inputStream.bufferedReader().readText()
            JSONObject(resp).optString("text", "").trim()
        } catch (_: Exception) { "" }
    }

    @JavascriptInterface fun startVoice(lang: String) {
        if (isCallAudioActive()) { mainH.post { android.widget.Toast.makeText(ctx, "அழைப்பு நடக்கிறது — மைக் பாதுகாக்கப்பட்டது", android.widget.Toast.LENGTH_SHORT).show(); onTtsDone() }; return }
        mainH.post { onVoiceReq(lang.ifBlank { "ta-IN" }) } }
    @JavascriptInterface fun stopVoice() { mainH.post { onStopVoiceReq() } }
    @JavascriptInterface fun startBluetoothMic() { try { if (audioMgr.isBluetoothScoAvailableOffCall) { audioMgr.mode = AudioManager.MODE_IN_COMMUNICATION; audioMgr.startBluetoothSco() } } catch (_: Exception) {} }
    @JavascriptInterface fun scanScreen(): String = PuyalAccessService.instance?.scanScreenText() ?: "திரையைப் படிக்க அணுகல்தன்மை (Accessibility) அனுமதியை இயக்கவும்."
    @JavascriptInterface fun clickOnScreen(target: String): String = PuyalAccessService.instance?.clickByText(target) ?: "அணுகல்தன்மை (Accessibility) அனுமதியை இயக்கவும்."
    @JavascriptInterface fun getWaSummary(): String = PuyalNotifService.getSummary()
    @JavascriptInterface fun replyLastWa(msg: String): String = PuyalNotifService.replyToLastWa(ctx, msg)
    @JavascriptInterface fun verifyOwnerBiometric() { mainH.post { onBiometricReq() } }

    @JavascriptInterface
    fun lookupContact(name: String): String {
        val q = name.trim()
        if (q.isEmpty()) return "பெயர் காலியாக உள்ளது."
        return try {
            val cr = ctx.contentResolver
            val cur = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                null
            )
            cur?.use {
                if (it.moveToFirst()) {
                    val dn = it.getString(0) ?: q
                    val num = it.getString(1)?.replace(Regex("[^0-9+]"), "") ?: ""
                    if (num.isNotEmpty()) return "FOUND:$dn:$num"
                }
            }
            "தொடர்புகளில் '$q' என்ற பெயர் கிடைக்கவில்லை."
        } catch (_: SecurityException) {
            "தொடர்புகளைப் படிக்க Contacts அனுமதி தேவை."
        } catch (e: Exception) {
            "தொடர்பு தேடலில் பிழை: ${e.message}"
        }
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExemption(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mainH.post {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                }
            }
            "பேட்டரி சேமிப்பு விலக்கு அமைப்பு திறக்கப்பட்டது."
        } catch (e: Exception) { "அமைப்பைத் திறக்க முடியவில்லை: ${e.message}" }
    }


    @JavascriptInterface
    fun openAppByName(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return "NOT_FOUND:empty"
        return try {
            val pm = ctx.packageManager
            val apps = pm.getInstalledApplications(0)
            var exactPkg: String? = null
            var startPkg: String? = null
            var partPkg: String? = null
            var chosenLabel = query
            for (ai in apps) {
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue
                val lbl = pm.getApplicationLabel(ai)?.toString()?.trim() ?: continue
                val low = lbl.lowercase()
                if (low == q) { exactPkg = ai.packageName; chosenLabel = lbl; break }
                if (startPkg == null && low.startsWith(q)) { startPkg = ai.packageName; chosenLabel = lbl }
                if (partPkg == null && (low.contains(q) || q.contains(low))) { partPkg = ai.packageName; chosenLabel = lbl }
            }
            val pkg = exactPkg ?: startPkg ?: partPkg ?: return "NOT_FOUND:$query"
            val intent = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "NOT_FOUND:$query"
            ctx.startActivity(intent)
            "$chosenLabel திறக்கப்பட்டது."
        } catch (e: Exception) { "NOT_FOUND:${e.message}" }
    }

    @JavascriptInterface
    fun getDetailedPermissions(): String {
        ensureCallStateListener()
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        val acc = PuyalAccessService.instance != null
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val call = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val notif = try {
            val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            enabled.contains(ctx.packageName)
        } catch (_: Exception) { false }
        val battery = try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(ctx.packageName) else true
        } catch (_: Exception) { false }
        val assistant = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = ctx.getSystemService(RoleManager::class.java)
                rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            } else false
        } catch (_: Exception) { false }
        return JSONObject().apply {
            put("accessibility", acc); put("overlay", overlay); put("mic", mic)
            put("contacts", contacts); put("call", call); put("notifications", notif)
            put("battery", battery); put("assistant", assistant)
        }.toString()
    }

    @JavascriptInterface
    fun openPermissionSettings(kind: String): String = launchMain {
        mainH.post {
            try {
                val i = when (kind.lowercase()) {
                    "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    "notifications" -> Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Exception) {}
        }
        "அனுமதி அமைப்புப் பக்கம் திறக்கப்பட்டது."
    }


    @JavascriptInterface
    fun readClipboard(): String {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()?.take(1200) ?: ""
        } catch (_: Exception) { "" }
    }

    @JavascriptInterface
    fun getPermissionStatus(): String {
        val overlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(ctx) else true
        val writeSet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(ctx) else true
        val acc = PuyalAccessService.instance != null
        return JSONObject().apply { put("overlay", overlay); put("accessibility", acc); put("writeSettings", writeSet) }.toString()
    }

    @JavascriptInterface
    fun getCrashLog(): String {
        val f = File(ctx.filesDir, "yuna_crash.log")
        return if (f.exists()) f.readText().takeLast(3000) else "பிழைப் பதிவுகள் எதுவும் இல்லை."
    }

    @JavascriptInterface
    fun saveObsidianNote(title: String, md: String): String {
        return try {
            val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
            val dir = File(base, "YUNA_Notes")
            if (!dir.exists()) dir.mkdirs()
            val safe = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
            File(dir, "$safe.md").writeText(md)
            "குறிப்பு சேமிக்கப்பட்டது: $safe.md"
        } catch (e: Exception) { "சேமிப்புப் பிழை: ${e.message}" }
    }

    @JavascriptInterface
    fun executeAction(action: String, target: String): String {
        val acc = PuyalAccessService.instance
        return when (action.uppercase()) {
            "TORCH", "TORCH_ON", "TORCH_OFF" -> toggleTorch(action.uppercase())
            "OPEN_CAMERA" -> launchMain {
                ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "கேமரா திறக்கப்பட்டது."
            }
            "CAMERA_SHUTTER" -> acc?.tapCameraShutter() ?: "கேமரா ஷட்டருக்கு அணுகல்தன்மை (Accessibility) தேவை."
            "OPEN_APP" -> openAppPkg(target)
            "OPEN_MAPS_NAV" -> launchMain {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(target))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=" + Uri.encode(target))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                "$target-க்கு Google Maps வழிசெலுத்தல் திறக்கப்பட்டது."
            }
            "DEEP_LINK_SEARCH" -> launchMain {
                val q = target.removePrefix("youtube:").trim()
                val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube")
                    .putExtra("query", q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(i) } catch (_: Exception) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "YouTube-இல் '$q' தேடப்படுகிறது."
            }
            "YT_SKIP_AD" -> acc?.skipYouTubeAd() ?: "YouTube கட்டுப்பாட்டிற்கு அணுகல்தன்மை தேவை."
            "YT_FIRST_VIDEO" -> acc?.clickFirstYouTubeVideo() ?: "YouTube கட்டுப்பாட்டிற்கு அணுகல்தன்மை தேவை."
            "YT_FORWARD_10" -> acc?.seekYouTubeForward10() ?: "YouTube கட்டுப்பாட்டிற்கு அணுகல்தன்மை தேவை."
            "YT_FULLSCREEN" -> acc?.toggleYouTubeFullScreen() ?: "YouTube கட்டுப்பாட்டிற்கு அணுகல்தன்மை தேவை."
            "GLOBAL_BACK" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true) "பின்னால் செல்லப்பட்டது." else "அணுகல்தன்மை (Accessibility) இயக்கப்படவில்லை."
            "GLOBAL_HOME" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true) "முகப்புத் திரைக்குச் செல்லப்பட்டது." else "அணுகல்தன்மை இயக்கப்படவில்லை."
            "GLOBAL_RECENTS" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) == true) "சமீபத்திய செயலிகள் திறக்கப்பட்டன." else "அணுகல்தன்மை இயக்கப்படவில்லை."
            "CLEAR_RECENTS" -> acc?.clearAllRecents() ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "GLOBAL_NOTIFICATIONS" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true) "அறிவிப்புப் பட்டை திறக்கப்பட்டது." else "அணுகல்தன்மை இயக்கப்படவில்லை."
            "GLOBAL_QUICK_SETTINGS" -> if (acc?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) == true) "விரைவு அமைப்புகள் திறக்கப்பட்டன." else "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TOGGLE_WIFI" -> acc?.pullQuickSettingsAndTap(listOf("Wi-Fi", "Internet", "WLAN", "வைஃபை"), "Wi-Fi") ?: openSettingsPanel(Settings.Panel.ACTION_WIFI, "Wi-Fi")
            "TOGGLE_DATA" -> acc?.pullQuickSettingsAndTap(listOf("Mobile data", "Internet", "Cellular", "Data"), "Mobile Data") ?: openSettingsPanel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Mobile Data")
            "TOGGLE_BT" -> acc?.pullQuickSettingsAndTap(listOf("Bluetooth", "புளூடூத்"), "Bluetooth") ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TOGGLE_HOTSPOT" -> acc?.pullQuickSettingsAndTap(listOf("Hotspot", "Mobile Hotspot", "Personal hotspot"), "Hotspot") ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TOGGLE_LOCATION" -> acc?.pullQuickSettingsAndTap(listOf("Location", "GPS"), "Location") ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TOGGLE_AIRPLANE" -> acc?.pullQuickSettingsAndTap(listOf("Airplane mode", "Flight mode"), "Airplane Mode") ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TOGGLE_ROTATE" -> acc?.pullQuickSettingsAndTap(listOf("Auto-rotate", "Portrait", "Rotation"), "Auto-Rotate") ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TOGGLE_DND" -> acc?.pullQuickSettingsAndTap(listOf("Do Not Disturb", "DND"), "Do Not Disturb") ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "NET_PANEL" -> openSettingsPanel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "Internet Panel")
            "LOCK_SCREEN" -> acc?.lockScreenNow() ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "SCREENSHOT" -> acc?.takeScreenshotNow() ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "SCROLL_DOWN" -> acc?.scrollDirection(true) ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "SCROLL_UP" -> acc?.scrollDirection(false) ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "TYPE_SEARCH" -> acc?.typeAndSearch(target) ?: "அணுகல்தன்மை இயக்கப்படவில்லை."
            "SPEAKER_ON" -> setSpeakerphone(true)
            "SPEAKER_OFF" -> setSpeakerphone(false)
            "WHO_IS_CALLING" -> getLastCallerInfo()
            "VOLUME_UP" -> { audioMgr.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); "ஒலி அளவு கூட்டப்பட்டது." }
            "VOLUME_DOWN" -> { audioMgr.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); "ஒலி அளவு குறைக்கப்பட்டது." }
            "VOLUME_MUTE" -> { audioMgr.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); "ஒலி அமைதி நிலை மாற்றப்பட்டது." }
            "MEDIA_NEXT" -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); "அடுத்த பாடலுக்கு மாற்றப்பட்டது." }
            "MEDIA_PLAY_PAUSE" -> { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); "இசை Play/Pause செய்யப்பட்டது." }
            "BRIGHTNESS_UP" -> adjustBrightness(true)
            "BRIGHTNESS_DOWN" -> adjustBrightness(false)
            "BRIGHTNESS_AUTO" -> launchMain { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC); "தானியங்கி பிரகாசம் இயக்கப்பட்டது." }
                        "ADD_CALENDAR_EVENT" -> launchMain {
                val parts = target.split("|", limit = 2)
                val title = parts.getOrNull(0)?.ifBlank { "YUNA Event" } ?: "YUNA Event"
                val beginMs = parts.getOrNull(1)?.toLongOrNull() ?: (System.currentTimeMillis() + 3600000L)
                val i = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginMs + 3600000L)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                "நாட்காட்டியில் '$title' நிகழ்வு திறக்கப்பட்டது."
            }
            "SET_REMINDER" -> launchMain {
                val parts = target.split("|", limit = 2)
                val title = parts.getOrNull(0)?.ifBlank { "YUNA Reminder" } ?: "YUNA Reminder"
                val beginMs = parts.getOrNull(1)?.toLongOrNull() ?: (System.currentTimeMillis() + 3600000L)
                val i = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, "⏰ " + title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMs)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, beginMs + 1800000L)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(i)
                "நினைவூட்டல் '$title' பதிவுப் பக்கம் திறக்கப்பட்டது."
            }
            "SET_ALARM" -> setAlarm(target)
            "SET_TIMER" -> setTimer(target)
            "GET_BATTERY_TIME" -> getBatteryAndTime()
            "DIAL" -> launchMain {
                val num = Uri.encode(target.replace(Regex("[^0-9+*#]"), ""))
                val hasCallPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                val act = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
                ctx.startActivity(Intent(act, Uri.parse("tel:$num")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (hasCallPerm) "$num எண்ணுக்கு நேரடி அழைப்பு விடப்படுகிறது." else "$num எண்ணுக்கு அழைப்புப் பக்கம் திறக்கப்பட்டது."
            }
            else -> "தெரியாத கட்டளை: $action"
        }
    }

    private fun setAlarm(timeStr: String): String = launchMain {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 6
        val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h)
            putExtra(AlarmClock.EXTRA_MINUTES, m)
            putExtra(AlarmClock.EXTRA_MESSAGE, "YUNA Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        "${String.format(Locale.US, "%02d:%02d", h, m)}-க்கு அலாரம் அமைக்கப்பட்டது."
    }

    private fun setTimer(secsStr: String): String = launchMain {
        val secs = secsStr.toIntOrNull()?.coerceIn(5, 86400) ?: 300
        val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secs)
            putExtra(AlarmClock.EXTRA_MESSAGE, "YUNA Timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
        "${secs} வினாடிகளுக்கு டைமர் அமைக்கப்பட்டது."
    }

    private fun getBatteryAndTime(): String {
        val bm = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = bm?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val now = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
        val batText = if (pct >= 0) "பேட்டரி $pct%${if (charging) " (சார்ஜ் ஏறுகிறது)" else ""}" else "பேட்டரி நிலை தெரியவில்லை"
        return "தற்போதைய நேரம் $now. $batText."
    }

    private fun launchMain(block: () -> String): String {
        return try { block() } catch (e: Exception) { "செயல் தோல்வி: ${e.message}" }
    }

    private fun toggleTorch(mode: String): String {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "இந்த சாதனத்தில் ஃபிளாஷ் விளக்கு இல்லை."
            val next = when (mode) { "TORCH_ON" -> true; "TORCH_OFF" -> false; else -> !torchState }
            cm.setTorchMode(id, next)
            torchState = next
            if (next) "டார்ச் ஆன் செய்யப்பட்டது." else "டார்ச் அணைக்கப்பட்டது."
        } catch (e: android.hardware.camera2.CameraAccessException) { "கேமரா பயன்பாட்டில் உள்ளதால் டார்ச் இயக்க முடியவில்லை." } catch (e: Exception) { "டார்ச் பிழை: ${e.message}" }
    }

    private fun openAppPkg(pkg: String): String = launchMain {
        val pm = ctx.packageManager
        var i = pm.getLaunchIntentForPackage(pkg)
        if (i == null && pkg == "com.whatsapp") i = pm.getLaunchIntentForPackage("com.whatsapp.w4b")
        i = i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return@launchMain "செயலி நிறுவப்படவில்லை ($pkg)."
        ctx.startActivity(i)
        "செயலி திறக்கப்பட்டது."
    }

    private fun openSettingsPanel(action: String, label: String): String = launchMain {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "$label அமைப்புப் பக்கம் திறக்கப்பட்டது."
        } else "அணுகல்தன்மை (Accessibility) இயக்கவும்."
    }

    private fun sendMediaKey(code: Int) {
        audioMgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioMgr.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun adjustBrightness(up: Boolean): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(ctx)) {
            mainH.post {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return "பிரகாசத்தை மாற்ற Write Settings அனுமதியை இயக்கவும்."
        }
        return try {
            mainH.post {
                try {
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    val cur = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
                    val next = (cur + if (up) 48 else -48).coerceIn(15, 255)
                    Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
                } catch (_: Exception) {}
            }
            if (up) "திரை பிரகாசம் கூட்டப்பட்டது." else "திரை பிரகாசம் குறைக்கப்பட்டது."
        } catch (e: Exception) { "பிரகாசப் பிழை: ${e.message}" }
    }
}
