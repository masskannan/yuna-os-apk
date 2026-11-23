package com.yuna.os.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sqrt

enum class EngineState { IDLE, LISTENING, PROCESSING, SPEAKING }
enum class AudioFormatType { OGG_OPUS, WAV_PCM }
data class EncodedSpeech(val bytes: ByteArray, val format: AudioFormatType)

interface YunaVoiceCallbacks {
    fun onStateChanged(state: EngineState)
    fun onPartialResponse(text: String)
    fun onRequestScreenshot()
    fun onError(message: String)
}

class YunaVoiceService : Service() {
    inner class LocalBinder : Binder() { fun getEngine(): YunaVoiceEngine? = voiceEngine }
    private val binder = LocalBinder()
    private var voiceEngine: YunaVoiceEngine? = null

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMicForegroundNotification()
        return START_STICKY
    }

    fun attachAndStartEngine(cloudBaseUrl: String, yunaToken: String, callbacks: YunaVoiceCallbacks) {
        if (voiceEngine == null) {
            voiceEngine = YunaVoiceEngine(applicationContext, cloudBaseUrl, yunaToken, callbacks)
        }
        voiceEngine?.start()
    }

    override fun onDestroy() {
        voiceEngine?.release()
        voiceEngine = null
        super.onDestroy()
    }

    private fun startMicForegroundNotification() {
        val channelId = "yuna_voice_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "YUNA Voice Assistant", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("YUNA AI")
            .setContentText("Listening for voice commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        // FIX: FOREGROUND_SERVICE_TYPE_MICROPHONE requires API 30 (R)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }
    }
}

class YunaVoiceEngine(
    private val context: Context,
    private val cloudBaseUrl: String,
    private val yunaToken: String,
    private val callbacks: YunaVoiceCallbacks
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_DURATION_MS = 20
        private const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_DURATION_MS) / 1000
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val PRE_ROLL_FRAMES = 16 // 320ms ring buffer
        private const val MAX_UTTERANCE_BYTES = SAMPLE_RATE * 2 * 20 // 20s cap
        private const val BARGE_IN_REQUIRED_MS = 200L
        private const val EDGE_TTS_TIMEOUT_MS = 1000L
        private const val POST_TTS_COOLDOWN_MS = 500L
        private const val OFFLINE_DEGRADE_TAMIL = "இணையம் தேவை. தற்பொழுது இணைய இணைப்பு இல்லை."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = AtomicReference(EngineState.IDLE)
    private val isRunning = AtomicBoolean(false)
    private val isAndroidTtsReady = AtomicBoolean(false)
    private val ttsGenId = AtomicLong(0L)

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var autoGain: AutomaticGainControl? = null
    @Volatile private var isRemoteMuted = false
    private val muteReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == "com.yuna.os.VOICE_MUTE_STATE") {
                isRemoteMuted = intent.getBooleanExtra("muted", false)
            }
        }
    }
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var autoGainControl: AutomaticGainControl? = null
    private var captureJob: Job? = null
    private var cooldownJob: Job? = null

    private val highPassFilter = HighPassFilter80Hz(SAMPLE_RATE)
    private val vad = AdaptiveRmsVad(frameDurationMs = FRAME_DURATION_MS)

    private var sseJob: Job? = null
    @Volatile private var activeSseConnection: HttpURLConnection? = null
    private val activeAudioTrackRef = AtomicReference<AudioTrack?>(null)
    private var androidTts: TextToSpeech? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val tier0Router = Tier0IntentRouter(context) {
        mainHandler.post { callbacks.onRequestScreenshot() }
    }

    init { initAndroidTamilTts() }

    private fun initAndroidTamilTts() {
        androidTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = androidTts?.setLanguage(Locale("ta", "IN"))
                isAndroidTtsReady.set(res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)
            }
        }
        androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == ttsGenId.get().toString()) onTtsPlaybackFinished()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == ttsGenId.get().toString()) onTtsPlaybackFinished()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == ttsGenId.get().toString()) onTtsPlaybackFinished()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isRunning.set(false)
            postError("RECORD_AUDIO permission not granted")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, maxOf(minBuf * 2, FRAME_BYTES * 8))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            isRunning.set(false)
            record.release()
            postError("AudioRecord failed to initialize")
            return
        }

        runCatching {
            val f = IntentFilter("com.yuna.os.VOICE_MUTE_STATE")
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(muteReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(muteReceiver, f)
        }
        audioRecord = record
        val sid = record.audioSessionId
        runCatching { if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(sid)?.apply { enabled = true } }
        runCatching { if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(sid)?.apply { enabled = true } }
        runCatching { if (AutomaticGainControl.isAvailable()) autoGain = AutomaticGainControl.create(sid)?.apply { enabled = true } }
        requestVoiceFocus()
        attachHardwareDsp(record.audioSessionId)
        record.startRecording()
        setState(EngineState.LISTENING)
        captureJob = scope.launch(Dispatchers.IO) { runCaptureLoop(record) }
    }


    private fun requestVoiceFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }
    private fun abandonVoiceFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun attachHardwareDsp(sessionId: Int) {
        if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        if (AcousticEchoCanceler.isAvailable()) echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        if (AutomaticGainControl.isAvailable()) autoGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
    }

    private fun runCaptureLoop(record: AudioRecord) {
        val rawShorts = ShortArray(FRAME_SAMPLES)
        val preRoll = ArrayDeque<ByteArray>(PRE_ROLL_FRAMES)
        val utteranceBuffer = ByteArrayOutputStream()
        var isCapturingSpeech = false
        var bargeInSpeechMs = 0L

        while (scope.isActive && isRunning.get()) {
            val readCount = record.read(rawShorts, 0, FRAME_SAMPLES)
            if (readCount <= 0) continue

            highPassFilter.processInPlace(rawShorts, readCount)
            val currentState = state.get()
            val isSpeakingTts = (currentState == EngineState.SPEAKING)
            val isSpeechFrame = vad.processFrame(rawShorts, readCount, strictMode = isSpeakingTts)
            val frameBytes = shortsToLittleEndian(rawShorts, readCount)

            if (isSpeakingTts) {
                if (isSpeechFrame) {
                    bargeInSpeechMs += FRAME_DURATION_MS
                    if (bargeInSpeechMs >= BARGE_IN_REQUIRED_MS) {
                        bargeInSpeechMs = 0L
                        triggerBargeIn()
                        isCapturingSpeech = true
                        utteranceBuffer.reset()
                        preRoll.forEach { utteranceBuffer.write(it) }
                        preRoll.clear()
                        utteranceBuffer.write(frameBytes)
                    }
                } else {
                    bargeInSpeechMs = 0L
                }
                continue
            }

            if (currentState != EngineState.LISTENING) continue

            if (!isCapturingSpeech) {
                if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
                preRoll.addLast(frameBytes)
            }

            if (isSpeechFrame) {
                if (!isCapturingSpeech) {
                    isCapturingSpeech = true
                    preRoll.forEach { utteranceBuffer.write(it) }
                    preRoll.clear()
                }
                if (utteranceBuffer.size() < MAX_UTTERANCE_BYTES) utteranceBuffer.write(frameBytes)
            } else if (isCapturingSpeech) {
                if (utteranceBuffer.size() < MAX_UTTERANCE_BYTES) utteranceBuffer.write(frameBytes)
                if (vad.isUtteranceComplete() || utteranceBuffer.size() >= MAX_UTTERANCE_BYTES) {
                    isCapturingSpeech = false
                    val pcmBytes = utteranceBuffer.toByteArray()
                    utteranceBuffer.reset()
                    handleCompletedUtterance(pcmBytes)
                }
            }
        }
    }

    private fun handleCompletedUtterance(pcmBytes: ByteArray) {
        if (pcmBytes.size < FRAME_BYTES * 5) return
        setState(EngineState.PROCESSING)

        scope.launch {
            val encoded = withContext(Dispatchers.Default) { AudioCompressor.compress(context, pcmBytes, SAMPLE_RATE) }
            if (!isNetworkAvailable()) {
                speakWithGuaranteedFloor(OFFLINE_DEGRADE_TAMIL)
                return@launch
            }
            startCloudSsePipeline(encoded)
        }
    }

    fun handleTextCommand(transcript: String) {
        val tier0Reply = tier0Router.tryExecute(transcript)
        if (tier0Reply != null) {
            setState(EngineState.PROCESSING)
            speakWithGuaranteedFloor(tier0Reply)
            return
        }
        if (!isNetworkAvailable()) {
            speakWithGuaranteedFloor(OFFLINE_DEGRADE_TAMIL)
            return
        }
        setState(EngineState.PROCESSING)
        startCloudSsePipeline(encoded = null, textPrompt = transcript)
    }

    private fun startCloudSsePipeline(encoded: EncodedSpeech?, textPrompt: String? = null) {
        sseJob?.cancel()
        sseJob = scope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val endpoint = if (encoded != null) "${cloudBaseUrl.trimEnd('/')}/stt" else "${cloudBaseUrl.trimEnd('/')}/"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 4000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("X-Yuna-Token", yunaToken)
                    setRequestProperty("Content-Type", if (encoded != null) "application/octet-stream" else "application/json")
                    if (encoded != null) setRequestProperty("X-Audio-Format", encoded.format.name)
                }
                activeSseConnection = conn

                conn.outputStream.use { out ->
                    if (encoded != null) out.write(encoded.bytes)
                    else if (textPrompt != null) out.write(JSONObject().put("prompt", textPrompt).put("stream", false).toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
                    postError("Cloud error: $err")
                    speakWithGuaranteedFloor(OFFLINE_DEGRADE_TAMIL)
                    return@launch
                }

                val rawBody = conn.inputStream.bufferedReader().use { it.readText() }
                if (encoded != null) {
                    // /stt returned transcribed text -> route via Tier-0 or /chat
                    val sttText = try { JSONObject(rawBody).optString("text", "").trim() } catch (_: Exception) { "" }
                    if (sttText.isNotEmpty()) handleTextCommand(sttText)
                    else if (isActive) setState(EngineState.LISTENING)
                    return@launch
                }

                // /chat or / returned JSON { reply, actions } or SSE
                val replyText = if (rawBody.trimStart().startsWith("{")) {
                    val obj = JSONObject(rawBody)
                    val acts = obj.optJSONArray("actions")
                    if (acts != null) {
                        for (i in 0 until acts.length()) {
                            val act = acts.optJSONObject(i) ?: continue
                            val aName = act.optString("action", "")
                            val aTarget = act.optString("target", "")
                            if (aName == "OPEN_APP_DYNAMIC" && aTarget.isNotEmpty()) tier0Router.tryExecute("open $aTarget")
                            else if (aName == "TORCH_ON") tier0Router.tryExecute("torch on")
                            else if (aName == "TORCH_OFF") tier0Router.tryExecute("torch off")
                            else if (aName == "SCREENSHOT") tier0Router.tryExecute("screenshot")
                        }
                    }
                    obj.optString("reply", "")
                } else rawBody

                if (isActive && replyText.isNotEmpty()) {
                    mainHandler.post { callbacks.onPartialResponse(replyText) }
                    speakWithGuaranteedFloor(replyText)
                } else if (isActive) setState(EngineState.LISTENING)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                if (isActive) {
                    postError("Cloud stream error: ${e.message}")
                    speakWithGuaranteedFloor(OFFLINE_DEGRADE_TAMIL)
                }
            } finally {
                conn?.disconnect()
                activeSseConnection = null
            }
        }
    }

    private fun extractSseToken(raw: String): String = try {
        val obj = JSONObject(raw)
        obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content") ?: raw
    } catch (_: Exception) { raw }

    private fun sanitizeForTts(raw: String): String {
        return raw
            .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), " ")
            .replace(Regex("[\\u2600-\\u27BF\\uFE00-\\uFE0F]"), " ")
            .replace(Regex("[*#`~]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun speakWithGuaranteedFloor(rawText: String) {
        val text = sanitizeForTts(rawText)
        if (text.isEmpty()) return
        cooldownJob?.cancel()
        setState(EngineState.SPEAKING)
        val gen = ttsGenId.incrementAndGet()

        scope.launch(Dispatchers.IO) {
            if (!isNetworkAvailable()) {
                speakWithAndroidTamilTts(text, gen)
                return@launch
            }
            val edgePcmAudio = withTimeoutOrNull(EDGE_TTS_TIMEOUT_MS) { fetchEdgeTtsPcm(text) }
            if (edgePcmAudio != null && state.get() == EngineState.SPEAKING && ttsGenId.get() == gen) {
                playPcmViaAudioTrack(edgePcmAudio, text, gen)
            } else if (state.get() == EngineState.SPEAKING && ttsGenId.get() == gen) {
                speakWithAndroidTamilTts(text, gen)
            }
        }
    }

    private fun fetchEdgeTtsPcm(text: String): ByteArray? = try {
        val conn = (URL("${cloudBaseUrl.trimEnd('/')}/tts").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 900
            readTimeout = 900
            doOutput = true
            setRequestProperty("X-Yuna-Token", yunaToken)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(JSONObject().put("text", text).put("format", "pcm16").toString().toByteArray()) }
        if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
    } catch (_: Exception) { null }

    private fun playPcmViaAudioTrack(pcmBytes: ByteArray, fallbackText: String, gen: Long) {
        val cleanSize = pcmBytes.size and -2
        val totalFrames = cleanSize / 2
        if (totalFrames <= 0) { onTtsPlaybackFinished(); return }

        try {
            val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(cleanSize, minBuf))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeAudioTrackRef.set(track)
            track.setNotificationMarkerPosition(totalFrames)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    val claimed = activeAudioTrackRef.getAndSet(null)
                    try { claimed?.release() } catch (_: Exception) {}
                    if (ttsGenId.get() == gen) onTtsPlaybackFinished()
                }
                override fun onPeriodicNotification(t: AudioTrack?) {}
            }, mainHandler)

            track.write(pcmBytes, 0, cleanSize)
            track.play()
        } catch (_: Exception) {
            speakWithAndroidTamilTts(fallbackText, gen)
        }
    }

    private fun speakWithAndroidTamilTts(text: String, gen: Long) {
        if (!isAndroidTtsReady.get()) { onTtsPlaybackFinished(); return }
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, gen.toString())
    }

    private fun onTtsPlaybackFinished() {
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            delay(POST_TTS_COOLDOWN_MS)
            if (isRunning.get() && state.get() == EngineState.SPEAKING) {
                vad.resetHangover()
                setState(EngineState.LISTENING)
            }
        }
    }

    private fun triggerBargeIn() {
        ttsGenId.incrementAndGet()
        cooldownJob?.cancel()
        activeAudioTrackRef.getAndSet(null)?.let { track ->
            try { track.pause(); track.flush(); track.stop(); track.release() } catch (_: IllegalStateException) {}
        }
        androidTts?.stop()
        sseJob?.cancel(); sseJob = null
        activeSseConnection?.disconnect(); activeSseConnection = null
        vad.resetHangover()
        setState(EngineState.LISTENING)
    }

    fun stop() {
        isRunning.set(false)
        triggerBargeIn()
        captureJob?.cancel(); captureJob = null
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        abandonVoiceFocus()
        setState(EngineState.IDLE)
    }

    fun release() {
        stop()
        noiseSuppressor?.release(); echoCanceler?.release(); autoGainControl?.release()
        runCatching { context.unregisterReceiver(muteReceiver) }
        runCatching { noiseSuppressor?.release(); echoCanceler?.release(); autoGain?.release() }
        audioRecord?.release(); androidTts?.shutdown(); scope.cancel()
    }

    private fun setState(newState: EngineState) {
        state.set(newState)
        mainHandler.post { callbacks.onStateChanged(newState) }
    }

    private fun postError(msg: String) = mainHandler.post { callbacks.onError(msg) }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun shortsToLittleEndian(src: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = src[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}

class HighPassFilter80Hz(sampleRateHz: Int, cutoffHz: Double = 80.0) {
    private val alpha: Double
    private var prevInput = 0.0
    private var prevOutput = 0.0
    init { val rc = 1.0 / (2.0 * PI * cutoffHz); alpha = rc / (rc + 1.0 / sampleRateHz) }
    fun processInPlace(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val x = samples[i].toDouble()
            val y = alpha * (prevOutput + x - prevInput)
            prevInput = x; prevOutput = y
            samples[i] = y.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
    }
}

class AdaptiveRmsVad(private val frameDurationMs: Int) {
    private var noiseFloor = 120.0
    private val silenceMs = AtomicLong(0L)
    private val hangoverRequiredMs = 450L

    fun processFrame(samples: ShortArray, count: Int, strictMode: Boolean): Boolean {
        var sumSq = 0.0
        for (i in 0 until count) { val s = samples[i].toDouble(); sumSq += s * s }
        val rms = sqrt(sumSq / count)
        val threshold = maxOf(220.0, noiseFloor * (if (strictMode) 3.8 else 2.6))
        val isSpeech = rms > threshold
        if (isSpeech) silenceMs.set(0L)
        else {
            silenceMs.addAndGet(frameDurationMs.toLong())
            val adaptRate = if (rms < noiseFloor) 0.08 else 0.02
            noiseFloor = ((1.0 - adaptRate) * noiseFloor + adaptRate * rms).coerceIn(60.0, 2500.0)
        }
        return isSpeech
    }

    fun isUtteranceComplete(): Boolean = silenceMs.get() >= hangoverRequiredMs
    fun resetHangover() { silenceMs.set(0L) }
}

object AudioCompressor {
    fun compress(context: Context, pcmBytes: ByteArray, sampleRate: Int): EncodedSpeech {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            encodeOggOpus(context, pcmBytes, sampleRate)?.let { return EncodedSpeech(it, AudioFormatType.OGG_OPUS) }
        }
        return EncodedSpeech(wrapPcmInWav(pcmBytes, sampleRate), AudioFormatType.WAV_PCM)
    }

    private fun encodeOggOpus(context: Context, pcmBytes: ByteArray, sampleRate: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val tempFile = File.createTempFile("yuna_opus_", ".ogg", context.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var trackIndex = -1; var muxerStarted = false; val info = MediaCodec.BufferInfo()
            var offset = 0; var inDone = false; var outDone = false; var ptsUs = 0L

            while (!outDone) {
                if (!inDone) {
                    val idx = codec.dequeueInputBuffer(5_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!.apply { clear() }
                        val rem = pcmBytes.size - offset
                        if (rem <= 0) { codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else {
                            val chunk = minOf(buf.capacity(), rem)
                            buf.put(pcmBytes, offset, chunk)
                            codec.queueInputBuffer(idx, 0, chunk, ptsUs, 0)
                            offset += chunk; ptsUs += (chunk / 2) * 1_000_000L / sampleRate
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { trackIndex = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                }
            }
            if (muxerStarted) muxer.stop()
            tempFile.readBytes()
        } catch (_: Exception) { null } finally {
            try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}; tempFile.delete()
        }
    }

    private fun wrapPcmInWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val byteRate = sampleRate * 2
        val totalDataLen = pcm.size + 36
        fun leInt(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun leShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); leInt(totalDataLen); out.write("WAVEfmt ".toByteArray())
        leInt(16); leShort(1); leShort(1); leInt(sampleRate); leInt(byteRate); leShort(2); leShort(16)
        out.write("data".toByteArray()); leInt(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
}

class Tier0IntentRouter(private val context: Context, private val onScreenshotRequested: () -> Unit) {
    private val timeRegex = Regex("""\b(time|ist|நேரம்|மணி)\b""", RegexOption.IGNORE_CASE)
    private val torchOnRegex = Regex("""\b(torch on|flashlight on|டார்ச் ஆன்|விளக்கு போடு)\b""", RegexOption.IGNORE_CASE)
    private val torchOffRegex = Regex("""\b(torch off|flashlight off|டார்ச் ஆஃப்|விளக்கு அணை)\b""", RegexOption.IGNORE_CASE)
    private val screenshotRegex = Regex("""\b(screenshot|screen shot|ஸ்கிரீன்ஷாட்)\b""", RegexOption.IGNORE_CASE)
    private val openAppRegex = Regex("""^(?:open|launch|start)\s+(.+)$""", RegexOption.IGNORE_CASE)

    fun tryExecute(query: String): String? {
        val text = query.trim()
        if (timeRegex.containsMatchIn(text)) {
            val sdf = SimpleDateFormat("h:mm a", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
            return "தற்போது இந்திய நேரம் ${sdf.format(Date())}."
        }
        if (torchOnRegex.containsMatchIn(text)) return if (setTorch(true)) "டார்ச் ஆன் செய்யப்பட்டது." else "டார்ச் இயக்க முடியவில்லை."
        if (torchOffRegex.containsMatchIn(text)) return if (setTorch(false)) "டார்ச் அணைக்கப்பட்டது." else "டார்ச் அணைக்க முடியவில்லை."
        if (screenshotRegex.containsMatchIn(text)) { onScreenshotRequested(); return "ஸ்கிரீன்ஷாட் எடுக்கப்படுகிறது." }
        openAppRegex.find(text)?.let {
            val app = it.groupValues[1].trim()
            return if (launchAppByName(app)) "$app திறக்கப்படுகிறது." else "$app செயலி கிடைக்கவில்லை."
        }
        return null
    }

    private fun setTorch(enabled: Boolean): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return false
            cm.setTorchMode(id, enabled)
            true
        } catch (_: Exception) { false }
    }

    private fun launchAppByName(appName: String): Boolean {
        return try {
            val pm = context.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val matched = apps.firstOrNull { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) } ?: return false
            val launch = pm.getLaunchIntentForPackage(matched.activityInfo.packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } catch (_: Exception) { false }
    }
}
