package com.puyal.os.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PuyalAccessService : AccessibilityService() {
    companion object {
        @Volatile var instance: PuyalAccessService? = null
        @Volatile var lastScreenText: String = ""
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

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in BANKING_BLOCKLIST) {
            lastScreenText = "[REDACTED: Banking/UPI App Protected]"
            return
        }
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collectText(root, sb, 0)
        if (sb.isNotEmpty()) lastScreenText = "[$pkg] " + sb.toString().take(800)
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 12 || sb.length > 800) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append(" | ") }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun scanNow(): String {
        val root = rootInActiveWindow ?: return lastScreenText
        val pkg = root.packageName?.toString() ?: "unknown"
        if (pkg in BANKING_BLOCKLIST) return "[REDACTED: Banking/UPI App Protected]"
        val sb = StringBuilder()
        collectText(root, sb, 0)
        return if (sb.isNotEmpty()) "[$pkg] " + sb.toString().take(800) else lastScreenText
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
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
