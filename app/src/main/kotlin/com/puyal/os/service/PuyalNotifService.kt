package com.puyal.os.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

class PuyalNotifService : NotificationListenerService() {
    companion object {
        val waMessages = ConcurrentHashMap<String, String>()
        @Volatile var lastWaReplyAction: Notification.Action? = null
        @Volatile var lastWaSender: String = ""

        fun getSummary(): String {
            if (waMessages.isEmpty()) return "படிக்காத வாட்ஸ்அப் செய்திகள் எதுவும் இல்லை."
            return waMessages.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }

        fun replyToLastWa(ctx: android.content.Context, msg: String): String {
            if (msg.isBlank()) return "அனுப்ப வேண்டிய பதில் உரையைச் சொல்லுங்கள்."
            val action = lastWaReplyAction ?: return "பதில் அனுப்பத் தகுந்த வாட்ஸ்அப் அறிவிப்பு இல்லை."
            val ri = action.remoteInputs?.firstOrNull() ?: return "இந்த அறிவிப்பில் நேரடிப் பதில் வசதி இல்லை."
            return try {
                val intent = Intent()
                val bundle = Bundle()
                bundle.putCharSequence(ri.resultKey, msg)
                RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
                action.actionIntent.send(ctx, 0, intent)
                "வாட்ஸ்அப் வழியாக ${lastWaSender.ifBlank { "பயனர்" }}-க்கு பதில் அனுப்பப்பட்டது: $msg"
            } catch (e: Exception) {
                "வாட்ஸ்அப் பதில் அனுப்புவதில் பிழை: ${e.message}"
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            val notif = sbn.notification ?: return
            if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            val extras = notif.extras ?: return
            val title = extras.getCharSequence("android.title")?.toString() ?: "WhatsApp"
            val text = extras.getCharSequence("android.text")?.toString() ?: return
            if (!text.contains("new messages", ignoreCase = true) && !title.equals("WhatsApp", ignoreCase = true)) {
                waMessages[title] = text
                lastWaSender = title
            }
            notif.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let {
                lastWaReplyAction = it
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == "com.whatsapp" || sbn.packageName == "com.whatsapp.w4b") {
            val title = sbn.notification?.extras?.getCharSequence("android.title")?.toString() ?: return
            waMessages.remove(title)
            if (title == lastWaSender) {
                lastWaReplyAction = null
                lastWaSender = ""
            }
        }
    }
}
