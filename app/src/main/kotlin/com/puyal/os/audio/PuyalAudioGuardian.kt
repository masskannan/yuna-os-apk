package com.puyal.os.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlin.math.sqrt

object PuyalAudioGuardian {
    private const val THRESHOLD = 0.85f

    fun enableBluetoothSco(ctx: Context): String {
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bt = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (bt != null) { am.setCommunicationDevice(bt); return "Bluetooth Mic Active" }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                return "Bluetooth SCO Active"
            }
            "Phone Mic Active"
        } catch (e: Exception) { "Audio route err: ${e.message}" }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom > 0f) dot / denom else 0f
    }

    fun isOwnerVoice(sample: FloatArray, enrolled: FloatArray): Boolean = cosineSimilarity(sample, enrolled) >= THRESHOLD
}
