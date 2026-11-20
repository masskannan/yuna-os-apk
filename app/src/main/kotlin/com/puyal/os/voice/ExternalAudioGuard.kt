package com.puyal.os.voice

import android.content.Context
import android.media.AudioManager

object ExternalAudioGuard {
    fun isCallOrVoipActive(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val m = am.mode
            m == AudioManager.MODE_IN_CALL ||
                m == AudioManager.MODE_IN_COMMUNICATION ||
                m == AudioManager.MODE_RINGTONE
        } catch (_: Throwable) {
            false
        }
    }
}
