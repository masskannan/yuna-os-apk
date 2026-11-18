package com.puyal.os.bridge

import android.content.Context
import android.content.Intent
import java.io.File
import java.util.concurrent.TimeUnit

object PuyalGuardHelper {
    const val ACTION_OVERLAY_VISIBILITY = "com.puyal.os.OVERLAY_VISIBILITY"
    const val EXTRA_SHOW = "show_overlay"

    private val BANKING_PACKAGES = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.sbi.lotusintouch"
    )

    // #78 Check if su exists before wasting calls on non-root phones
    fun hasRootBinary(): Boolean {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        return paths.any { File(it).exists() }
    }

    // #63 Safe OOM -800 (avoids full OS freeze on memory leak)
    fun applySafeOomGuard(pid: Int): Boolean {
        if (!hasRootBinary()) return false
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo -800 > /proc/$pid/oom_score_adj"))
            proc.waitFor(3, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (_: Exception) { false }
    }

    // #25 Mask OTP / UPI PIN / CVV before logging or Cloud send
    fun sanitizeSensitiveText(raw: String): String {
        return raw
            .replace(Regex("(?i)(otp|pin|cvv|mpin)\\s*[:\\-]?\\s*\\d{3,8}"), "$1: [REDACTED]")
            .replace(Regex("\\b\\d{12,16}\\b"), "[CARD_REDACTED]")
    }

    // #61 Dynamic Banking Package Check
    fun isSensitiveApp(packageName: String?): Boolean {
        return packageName != null && BANKING_PACKAGES.contains(packageName)
    }

    // #12 Hide floating overlay when MainActivity is in foreground
    fun setOverlayVisible(context: Context, visible: Boolean) {
        val intent = Intent(ACTION_OVERLAY_VISIBILITY).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SHOW, visible)
        }
        context.sendBroadcast(intent)
    }
}
