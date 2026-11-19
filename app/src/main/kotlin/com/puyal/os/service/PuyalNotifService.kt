package com.puyal.os.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.puyal.os.nativebridge.YunaNative
import java.util.concurrent.CopyOnWriteArrayList

class PuyalNotifService : NotificationListenerService() {
    companion object {
        private val waQueue = CopyOnWriteArrayList<Pair<String, String>>()
        private val WA_PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")

        fun getSummary(): String {
            if (waQueue.isEmpty()) return "WhatsApp unread notifications எதுவும் pending இல்ல பாஸ்."
            return "Unread WhatsApp:\n" + waQueue.takeLast(5).joinToString("\n") { it.second }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg !in WA_PKGS) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
        val safeText = if (YunaNative.hasSensitiveToken(text)) "[REDACTED OTP/PIN]" else text
        val key = sbn.key ?: title
        waQueue.removeAll { it.first == key }
        if (waQueue.size >= 15) waQueue.removeAt(0)
        waQueue.add(key to "• $title: $safeText")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        waQueue.removeAll { it.first == key }
    }
}
