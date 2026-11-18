package com.puyal.os.service

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

class PuyalNotifService : NotificationListenerService() {

    companion object {
        val actionLedger = ConcurrentLinkedDeque<String>()
        private val vipKeywords = listOf("amma", "அம்மா", "akka", "அக்கா", "vel", "load", "லோட்")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return

        val extras = sbn.notification?.extras ?: return
        val sender = extras.getCharSequence("android.title")?.toString() ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        val isVip = vipKeywords.any { sender.lowercase().contains(it) || text.lowercase().contains(it) }
        if (!isVip) return

        val actions = sbn.notification?.actions ?: return
        for (act in actions) {
            val remoteInputs = act.remoteInputs ?: continue
            if (remoteInputs.isNotEmpty()) {
                val replyText = if (text.contains("load", true) || text.contains("லோட்")) {
                    "வணக்கம், Vel Logistics பதிவு செய்யப்பட்டது. உடனே தொடர்பு கொள்கிறோம்."
                } else {
                    "இப்போ கொஞ்சம் வேலையா இருக்கேன், சிறிது நேரத்தில் கால் பண்றேன்."
                }
                sendSilentReply(act.actionIntent, remoteInputs, replyText)
                if (actionLedger.size >= 50) actionLedger.pollFirst()
                actionLedger.addLast("WhatsApp auto-replied to $sender")
                break
            }
        }
    }

    private fun sendSilentReply(pendingIntent: android.app.PendingIntent, remoteInputs: Array<RemoteInput>, msg: String) {
        try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, msg)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            pendingIntent.send(this, 0, intent)
        } catch (_: Exception) {}
    }
}
