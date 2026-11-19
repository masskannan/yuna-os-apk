package com.puyal.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.puyal.os.guard.YunaTierGuards

class PuyalAccessService : AccessibilityService() {
    companion object {
        @Volatile var instance: PuyalAccessService? = null
        @Volatile var lastScreenText: String = ""
        @Volatile var lastUpdatedMs: Long = 0L

        private val BANKING_BLOCKLIST = setOf(
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app",
            "net.one97.paytm", "in.org.npci.upiapp", "com.sbi.lotusintouch",
            "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile"
        )
    }

    private val mainH = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && YunaTierGuards.onHardwareKeyEvent(event)) return true
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in BANKING_BLOCKLIST) { lastScreenText = "[REDACTED: Banking/UPI App Protected]"; return }
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collectText(root, sb, 0)
        val txt = sb.toString().trim()
        if (txt.isNotEmpty()) { lastScreenText = txt.take(1200); lastUpdatedMs = SystemClock.elapsedRealtime() }
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 8 || sb.length > 1200) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) sb.append(t).append(" | ")
        else if (!d.isNullOrEmpty()) sb.append(d).append(" | ")
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb, depth + 1)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun scanNow(): String {
        val root = rootInActiveWindow ?: return lastScreenText.ifEmpty { "No active screen" }
        val pkg = root.packageName?.toString() ?: ""
        if (pkg in BANKING_BLOCKLIST) return "[REDACTED: Banking/UPI App Protected]"
        val sb = StringBuilder(); collectText(root, sb, 0)
        return sb.toString().trim().ifEmpty { lastScreenText }
    }

    private fun tapNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var steps = 0
        while (cur != null && steps < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            steps++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return tapAt(r.exactCenterX(), r.exactCenterY())
        }
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 260L): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun clickAnyAlias(aliases: List<String>): String {
        val root = rootInActiveWindow ?: return "No active screen"
        val pkg = root.packageName?.toString() ?: ""
        if (pkg in BANKING_BLOCKLIST) return "Blocked on Banking App"
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            val t = (cur.text?.toString() ?: "") + " " + (cur.contentDescription?.toString() ?: "")
            val lowT = t.lowercase()
            if (aliases.any { lowT.contains(it.lowercase()) }) {
                if (tapNodeOrParent(cur)) return "Tapped: ${t.trim().take(30)}"
            }
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { q.add(it) }
        }
        return "Not found: ${aliases.firstOrNull()}"
    }

    fun clickByText(label: String): String = clickAnyAlias(listOf(label))

    fun typeAndSearch(query: String): String {
        val root = rootInActiveWindow ?: return "No active screen"
        val pkg = root.packageName?.toString() ?: ""
        if (pkg in BANKING_BLOCKLIST) return "Blocked on Banking App"

        fun findEditable(r: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(r)
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                if (c.isEditable) return c
                for (i in 0 until c.childCount) c.getChild(i)?.let { q.add(it) }
            }
            return null
        }

        val ed = findEditable(root)
        if (ed != null) {
            ed.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
            ed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ed.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return "Typed & searched: $query"
        }
        // If search bar isn't open yet, tap Search icon then type after 450ms
        val tapped = clickAnyAlias(listOf("search", "தேடு"))
        if (tapped.startsWith("Tapped")) {
            mainH.postDelayed({
                val r2 = rootInActiveWindow ?: return@postDelayed
                val ed2 = findEditable(r2) ?: return@postDelayed
                ed2.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
                ed2.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ed2.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }, 450)
            return "Opening Search & typing: $query"
        }
        return "No search bar on screen"
    }

    fun scrollDirection(down: Boolean): String {
        val root = rootInActiveWindow ?: return "No active screen"
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            if (cur.isScrollable && cur.performAction(act)) return if (down) "Scrolled down" else "Scrolled up"
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { q.add(it) }
        }
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val ok = if (down) swipe(cx, dm.heightPixels * 0.75f, cx, dm.heightPixels * 0.25f) else swipe(cx, dm.heightPixels * 0.30f, cx, dm.heightPixels * 0.78f)
        return if (ok) (if (down) "Swiped down" else "Swiped up") else "Cannot scroll"
    }

    fun pullQuickSettingsAndTap(aliases: List<String>, delayMs: Long = 450L): String {
        if (!performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) return "Quick Settings unavailable"
        mainH.postDelayed({
            val res = clickAnyAlias(aliases)
            if (res.startsWith("Not found")) {
                val dm = resources.displayMetrics
                swipe(dm.widthPixels * 0.85f, dm.heightPixels * 0.3f, dm.widthPixels * 0.15f, dm.heightPixels * 0.3f)
                mainH.postDelayed({ clickAnyAlias(aliases) }, 350)
            }
        }, delayMs)
        return "Toggling ${aliases.first()} in Quick Settings"
    }

    fun clearAllRecents(): String {
        if (!performGlobalAction(GLOBAL_ACTION_RECENTS)) return "Recents unavailable"
        mainH.postDelayed({
            val aliases = listOf("clear all", "close all", "dismiss all", "clear")
            val res = clickAnyAlias(aliases)
            if (res.startsWith("Not found")) {
                val dm = resources.displayMetrics
                swipe(dm.widthPixels * 0.2f, dm.heightPixels * 0.5f, dm.widthPixels * 0.85f, dm.heightPixels * 0.5f)
                mainH.postDelayed({ clickAnyAlias(aliases) }, 350)
            }
        }, 480)
        return "Clearing all recent apps"
    }

    fun runNav(actionId: Int): Boolean = performGlobalAction(actionId)

    fun lockScreenNow(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) "Screen Locked"
        else "Lock screen requires Android 9+"
    }

    fun takeScreenshotNow(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)) "Screenshot captured"
        else "Screenshot requires Android 9+"
    }

    fun launchAppFromService(pkg: String): Boolean {
        return try {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i); true
        } catch (_: Exception) { false }
    }

    fun launchUriFromService(uriStr: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) { false }
    }
}
