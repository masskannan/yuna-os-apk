package com.puyal.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

class PuyalAccessService : AccessibilityService() {

    companion object {
        @Volatile var isAutomationPausedByHumanTouch = false
        @Volatile var virtualRamClipboard: String = ""
        private val seenHashes = LinkedHashSet<String>()
        private val bankingBlacklist = setOf(
            "com.google.android.apps.nbu.paisa.user",
            "com.phonepe.app",
            "net.one97.paytm",
            "com.sbi.lotusintouch",
            "com.icicibank.pockets",
            "com.axis.mobile"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val evt = event ?: return
        val pkg = evt.packageName?.toString() ?: return

        // Banking Blacklist (FLAG_SECURE Shield)
        if (pkg in bankingBlacklist) {
            isAutomationPausedByHumanTouch = true
            return
        }

        // Human-Touch Collision Pause within 1ms
        if (evt.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            isAutomationPausedByHumanTouch = true
            return
        }

        val root = rootInActiveWindow ?: return
        scrapeAndHashNodes(root, pkg)
    }

    private fun scrapeAndHashNodes(node: AccessibilityNodeInfo?, pkg: String) {
        if (node == null) return
        val txt = node.text?.toString()
        if (!txt.isNullOrBlank() && txt.length > 12) {
            val hash = sha256(txt)
            if (!seenHashes.contains(hash)) {
                if (seenHashes.size >= 100) {
                    seenHashes.remove(seenHashes.first())
                }
                seenHashes.add(hash)

                // Gemini-to-Termux Code Interceptor -> Virtual RAM Clipboard
                if (pkg.contains("googlequicksearchbox") || pkg.contains("chrome")) {
                    if (txt.contains("cd ~/") || txt.contains("pkg install") || txt.contains("git push")) {
                        virtualRamClipboard = txt
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            scrapeAndHashNodes(node.getChild(i), pkg)
        }
    }

    fun performHumanClick(x: Float, y: Float) {
        if (isAutomationPausedByHumanTouch) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onInterrupt() {
        isAutomationPausedByHumanTouch = true
    }
}
