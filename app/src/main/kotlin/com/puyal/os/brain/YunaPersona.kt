package com.puyal.os.brain

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class YunaIntent {
    OPEN_YOUTUBE,
    OPEN_WHATSAPP,
    WHATSAPP_CHAT,
    SCREEN_SCAN,
    TORCH_ON,
    TORCH_OFF,
    CLOUD_PUYAL,
    GREET_KANNAN,
    STRANGER_GUARD,
    CHITCHAT
}

data class YunaReply(
    val intent: YunaIntent,
    val spokenText: String,
    val routeToCloud: Boolean = false
)

class YunaVoiceBrain(context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val ta = Locale("ta", "IN")
                val res = tts?.setLanguage(ta)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("en", "IN"))
                }
                ttsReady = true
            }
        }
    }

    fun handleCommand(rawText: String, isOwnerVerified: Boolean): YunaReply {
        if (!isOwnerVerified) {
            return YunaReply(
                intent = YunaIntent.STRANGER_GUARD,
                spokenText = "ஏய்! இது எங்க அண்ணன் கண்ணன் போன், எடுக்காதீங்க!"
            )
        }
        val q = rawText.lowercase().trim()
        return when {
            q.contains("hi") || q.contains("ஹாய்") || q.contains("vanakkam") || q.contains("வணக்கம்") ->
                YunaReply(YunaIntent.GREET_KANNAN, "ஹாய் கண்ணன், எப்படி இருக்கீங்க? நல்லா இருக்கீங்களா?")
            q.contains("youtube") || q.contains("யூடியூப்") ->
                YunaReply(YunaIntent.OPEN_YOUTUBE, "சரி கண்ணன், யூடியூப் உடனே ஓபன் பண்றேன்.")
            q.contains("whatsapp") || q.contains("வாட்ஸ்அப்") ->
                if (q.contains("message") || q.contains("chat") || q.contains("மெசேஜ்") || q.contains("அனுப்பு")) {
                    YunaReply(YunaIntent.WHATSAPP_CHAT, "வாட்ஸ்அப் சாட் ரெடி பண்றேன் கண்ணன்.")
                } else {
                    YunaReply(YunaIntent.OPEN_WHATSAPP, "வாட்ஸ்அப் ஓபன் பண்றேன் கண்ணன்.")
                }
            q.contains("torch on") || q.contains("டார்ச் ஆன்") ->
                YunaReply(YunaIntent.TORCH_ON, "டார்ச் லைட் ஆன் பண்ணிட்டேன்.")
            q.contains("torch off") || q.contains("டார்ச் ஆஃப்") ->
                YunaReply(YunaIntent.TORCH_OFF, "டார்ச் லைட் ஆஃப் பண்ணிட்டேன்.")
            q.contains("scan") || q.contains("screen") || q.contains("ஸ்கேன்") ->
                YunaReply(YunaIntent.SCREEN_SCAN, "ஸ்கிரீனை ஸ்கேன் பண்றேன்.")
            q.contains("load") || q.contains("code") || q.contains("obsidian") || q.contains("லோட்") ->
                YunaReply(YunaIntent.CLOUD_PUYAL, "பெரிய வேலை, புயல் கிளவுடுக்கு அனுப்புறேன்.", routeToCloud = true)
            else ->
                YunaReply(YunaIntent.CHITCHAT, "சொல்லுங்க கண்ணன், என்ன பண்ணலாம்?")
        }
    }

    fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yuna_utt")
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
