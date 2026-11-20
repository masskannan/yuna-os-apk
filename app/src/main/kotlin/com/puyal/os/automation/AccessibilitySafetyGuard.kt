package com.puyal.os.automation

import android.view.accessibility.AccessibilityNodeInfo

object AccessibilitySafetyGuard {
    private val blockedPkgs = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.snapwork.hdfc",
        "com.axis.mobile"
    )
    private val pkgRegex = Regex("(?i)(upi|bank|pay|wallet|pin|auth|biometric)")
    private val payTextRegex = Regex("(?i)(upi|payments|send money|pay now|₹|enter pin)")

    fun isSafeTargetScreen(root: AccessibilityNodeInfo?, ownPkg: String): Boolean {
        if (root == null) return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg == ownPkg) return false
        if (pkg in blockedPkgs || pkgRegex.containsMatchIn(pkg)) return false
        if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
            if (containsSensitiveText(root, 0)) return false
        }
        return true
    }

    private fun containsSensitiveText(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 8) return false
        if (node.isPassword) return true
        val txt = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (payTextRegex.containsMatchIn(txt) || payTextRegex.containsMatchIn(desc)) return true
        for (i in 0 until node.childCount) {
            if (containsSensitiveText(node.getChild(i), depth + 1)) return true
        }
        return false
    }
}
