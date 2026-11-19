package com.puyal.os.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class PuyalAccessService : AccessibilityService() {
    companion object {
        @Volatile var instance: PuyalAccessService? = null
        private var lastActionMs = 0L
        private val BANKING_BLOCKLIST = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm",
            "com.sbi.lotusintouch",
            "com.csam.icici.bank.imobile",
            "com.snapwork.hdfc"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in BANKING_BLOCKLIST) return
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun runNav(actionId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastActionMs < 400L) return false
        lastActionMs = now
        return performGlobalAction(actionId)
    }

    fun launchAppFromService(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return true
    }

    fun launchUriFromService(uriStr: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        return true
    }
}
