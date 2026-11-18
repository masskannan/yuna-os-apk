package com.puyal.os.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PuyalAudioGuardian(private val context: Context) {
    companion object {
        init { System.loadLibrary("puyal_ffi") }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile var isMicYieldedToCall = false
    @Volatile var isListening = false

    external fun nativeSetTtsSpeaking(speaking: Boolean)
    external fun nativeVerifyVoiceprint(liveEmb: FloatArray, adminEmb: FloatArray, timePinFallback: String?): Boolean
    external fun nativeProcessPcmFrame(pcmFrame: ShortArray, sampleRate: Int): String

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                isMicYieldedToCall = true
                stopCaptureImmediately()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isMicYieldedToCall = false
            }
        }
    }

    fun requestAudioFocusAndStart(): Boolean {
        if (isMicYieldedToCall) return false
        val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun attachHardwareEchoCanceler(audioSessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler?.release()
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        }
    }

    fun getCurrentTimePin(): String = SimpleDateFormat("HHmm", Locale.US).format(Date())

    fun stopCaptureImmediately() {
        isListening = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            echoCanceler?.release()
            echoCanceler = null
        } catch (_: Exception) {}
    }
}
