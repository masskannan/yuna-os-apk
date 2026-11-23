package com.yuna.os.notif

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class YunaNotifService : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: YunaNotifService? = null
        private val lastRepliedAt = mutableMapOf<String, Long>()
        private const val COOLDOWN_MS = 5 * 60 * 1000L

        fun saveSkillRule(ctx: Context, contact: String, replyText: String, autoSend: Boolean) {
            val prefs = ctx.getSharedPreferences("yuna_skills", Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(prefs.getString("vip_rules", "[]")) }.getOrDefault(JSONArray())
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optString("contact").equals(contact, ignoreCase = true)) out.put(o)
            }
            out.put(JSONObject().apply {
                put("contact", contact.trim())
                put("reply", replyText.trim())
                put("auto", autoSend)
            })
            prefs.edit().putString("vip_rules", out.toString()).apply()
        }

        fun getSkillRulesJson(ctx: Context): String {
            return ctx.getSharedPreferences("yuna_skills", Context.MODE_PRIVATE)
                .getString("vip_rules", "[]") ?: "[]"
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return
        if (title.isEmpty() || text.isEmpty()) return

        val rules = runCatching { JSONArray(getSkillRulesJson(this)) }.getOrDefault(JSONArray())
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val target = rule.optString("contact", "")
            val reply = rule.optString("reply", "")
            if (target.isEmpty() || reply.isEmpty()) continue
            if (title.contains(target, ignoreCase = true) && rule.optBoolean("auto", false)) {
                val now = System.currentTimeMillis()
                val last = lastRepliedAt[target.lowercase()] ?: 0L
                if (now - last < COOLDOWN_MS) return
                if (sendRemoteReply(sbn.notification, reply)) {
                    lastRepliedAt[target.lowercase()] = now
                    sendBroadcast(Intent("com.yuna.os.GHOST_SHOW_CAPTION")
                        .putExtra("text", "$title-க்கு பதில் அனுப்பப்பட்டது: $reply"))
                }
                return
            }
        }
    }

    private fun sendRemoteReply(notif: Notification, replyText: String): Boolean {
        val actions = notif.actions ?: return false
        for (act in actions) {
            val remotes = act.remoteInputs ?: continue
            if (remotes.isEmpty()) continue
            val intent = Intent()
            val bundle = Bundle()
            for (ri in remotes) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remotes, intent, bundle)
            return runCatching {
                act.actionIntent.send(this, 0, intent)
                true
            }.getOrDefault(false)
        }
        return false
    }

    fun getReadOnlyReport(): String {
        val list = activeNotifications ?: return "தற்போது புதிய நோட்டிபிகேஷன்கள் இல்லை."
        val byApp = linkedMapOf<String, MutableList<String>>()
        for (sbn in list) {
            if (sbn.isOngoing) continue
            val pkg = sbn.packageName.substringAfterLast(".")
            val title = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val txt = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            if (title.isNotEmpty() || txt.isNotEmpty()) {
                byApp.getOrPut(pkg) { mutableListOf() }.add("$title: ${txt.take(45)}")
            }
        }
        if (byApp.isEmpty()) return "தற்போது புதிய நோட்டிபிகேஷன்கள் இல்லை."
        return byApp.entries.take(6).joinToString("\n") { (app, items) ->
            "• $app (${items.size}): ${items.first()}"
        }
    }
}
