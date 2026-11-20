package com.puyal.os.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.puyal.os.guard.YunaTierGuards
import java.lang.ref.WeakReference

class PuyalAccessService : AccessibilityService() {
    companion object {
        private var instanceRef: WeakReference<PuyalAccessService>? = null
        val instance: PuyalAccessService?
            get() = instanceRef?.get()
        val isRunning: Boolean
            get() = instanceRef?.get() != null
    }

    private val mainH = Handler(Looper.getMainLooper())
    @Volatile var lastActivePkg: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceRef?.clear()
        instanceRef = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { event?.packageName?.toString()?.let { if (it != packageName) lastActivePkg = it } }
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            try {
                if (YunaTierGuards.onHardwareKeyEvent(event)) return true
            } catch (_: Exception) {}
        }
        return super.onKeyEvent(event)
    }

    private fun isYouTubeActive(): Boolean {
        return rootInActiveWindow?.packageName?.toString() == "com.google.android.youtube"
    }

    fun scanNow(): String = scanScreenText()

    fun scanScreenText(): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        val blocked = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp")
        if (pkg in blocked) return "பாதுகாப்பு: வங்கி/UPI திரையைப் படிக்க முடியாது." 
        val pkg = rootInActiveWindow?.packageName?.toString() ?: ""
        val blocked = setOf("com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp")
        if (pkg in blocked) return "பாதுகாப்பு: வங்கி/UPI திரையைப் படிக்க முடியாது." 
        val root = rootInActiveWindow ?: return "திரையில் உள்ளடக்கம் எதுவும் கண்டறியப்படவில்லை."
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 14 || sb.length > 1600) return
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) sb.append(t).append(" | ")
            else if (!d.isNullOrEmpty()) sb.append("[").append(d).append("] | ")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return if (sb.isEmpty()) "திரையில் படிக்கக்கூடிய உரை இல்லை." else sb.toString().take(1500)
    }

    private fun findNodesByTextOrDesc(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val low = query.lowercase()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString()?.lowercase() ?: ""
            val d = n.contentDescription?.toString()?.lowercase() ?: ""
            if (t.contains(low) || d.contains(low)) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var steps = 0
        while (cur != null && steps < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            steps++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            return tapAt(r.exactCenterX(), r.exactCenterY())
        }
        return false
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(p, 0, 60)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun clickAnyAlias(aliases: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (a in aliases) {
            val matches = findNodesByTextOrDesc(root, a)
            val exact = matches.firstOrNull {
                it.text?.toString()?.equals(a, true) == true || it.contentDescription?.toString()?.equals(a, true) == true
            }
            if (exact != null && clickNodeOrParent(exact)) return true
            for (m in matches) {
                if (clickNodeOrParent(m)) return true
            }
        }
        return false
    }

    fun clickByText(query: String): String {
        if (query.isBlank()) return "தட்ட வேண்டிய பெயர் கொடுக்கப்படவில்லை."
        val ok = clickAnyAlias(listOf(query))
        return if (ok) "திரையில் '$query' தட்டப்பட்டது." else "திரையில் '$query' கண்டறியப்படவில்லை."
    }

    fun skipYouTubeAd(): String {
        if (!isYouTubeActive()) return "YouTube செயலி தற்போது திரையில் இல்லை."
        val ok = clickAnyAlias(listOf("Skip Ad", "Skip Ads", "Skip", "விளம்பரத்தைத் தவிர்", "தவிர்"))
        return if (ok) "YouTube விளம்பரம் தவிர்க்கப்பட்டது." else "தற்போது Skip Ad பொத்தான் திரையில் இல்லை."
    }

    fun clickFirstYouTubeVideo(): String {
        if (!isYouTubeActive()) return "YouTube செயலி தற்போது திரையில் இல்லை."
        val root = rootInActiveWindow ?: return "YouTube திரை திறந்திருக்கவில்லை."
        val minTop = (resources.displayMetrics.heightPixels * 0.22f).toInt()
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 14) return
            val d = n.contentDescription?.toString() ?: ""
            if (n.isClickable && d.length > 18 && !d.contains("Search", true) && !d.contains("Navigate", true)) {
                candidates.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val first = candidates.firstOrNull { val r = Rect(); it.getBoundsInScreen(r); r.top > minTop }
        return if (first != null && clickNodeOrParent(first)) "முதல் வீடியோ இயக்கப்பட்டது." else "முதல் வீடியோவைக் கண்டறிய முடியவில்லை."
    }

    fun seekYouTubeForward10(): String {
        if (!isYouTubeActive()) return "YouTube செயலி தற்போது திரையில் இல்லை."
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.78f
        val y = dm.heightPixels * 0.25f
        tapAt(x, y)
        mainH.postDelayed({ tapAt(x, y) }, 110)
        return "வீடியோ 10 வினாடிகள் முன்னோக்கி நகர்த்தப்பட்டது."
    }

    fun toggleYouTubeFullScreen(): String {
        if (!isYouTubeActive()) return "YouTube செயலி தற்போது திரையில் இல்லை."
        val ok = clickAnyAlias(listOf("Enter fullscreen", "Exit fullscreen", "Fullscreen", "முழுத்திரை"))
        return if (ok) "YouTube முழுத்திரை நிலை மாற்றப்பட்டது." else "முழுத்திரை பொத்தான் திரையில் கண்டறியப்படவில்லை."
    }

    fun tapCameraShutter(): String {
        val ok = clickAnyAlias(listOf("Shutter", "Take picture", "Capture", "Photo", "புகைப்படம்"))
        return if (ok) "புகைப்படம் எடுக்கப்பட்டது!" else "கேமரா ஷட்டர் பொத்தான் திரையில் கண்டறியப்படவில்லை."
    }

    fun typeAndSearch(query: String): String {
        val root = rootInActiveWindow ?: return "செயலில் உள்ள திரை இல்லை."
        fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isEditable || (n.className?.toString()?.contains("EditText") == true)) return n
            for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
            return null
        }
        var box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        if (box == null) {
            clickAnyAlias(listOf("Search", "தேடு", "Type a message", "Message"))
            mainH.postDelayed({
                val b2 = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(rootInActiveWindow)
                if (b2 != null) applyTextAndSubmit(b2, query)
            }, 320)
            return "'$query' தட்டச்சு செய்யப்படுகிறது..."
        }
        return if (applyTextAndSubmit(box, query)) "'$query' தட்டச்சு செய்யப்பட்டது." else "உரையை உள்ளிட முடியவில்லை."
    }

    private fun applyTextAndSubmit(box: AccessibilityNodeInfo, query: String): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            mainH.postDelayed({ clickAnyAlias(listOf("Search", "Send", "Go", "தேடு", "அனுப்பு")) }, 180)
        }
        return ok
    }

    fun scrollDirection(down: Boolean): String {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val y1 = if (down) dm.heightPixels * 0.72f else dm.heightPixels * 0.30f
        val y2 = if (down) dm.heightPixels * 0.28f else dm.heightPixels * 0.74f
        val p = Path().apply { moveTo(cx, y1); lineTo(cx, y2) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 260)).build(), null, null)
        return if (ok) (if (down) "கீழே நகர்த்தப்பட்டது." else "மேலே நகர்த்தப்பட்டது.") else "ஸ்க்ரோல் செய்ய முடியவில்லை."
    }

    fun pullQuickSettingsAndTap(aliases: List<String>, label: String): String {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        mainH.postDelayed({
            clickAnyAlias(aliases)
            mainH.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 700)
        }, 850)
        return "விரைவு அமைப்புகளில் $label நிலை மாற்றப்பட்டது."
    }

    fun clearAllRecents(): String {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        mainH.postDelayed({
            val ok = clickAnyAlias(listOf("Clear all", "Close all", "Close all apps", "Remove", "Clear", "அனைத்தையும் அழி"))
            if (!ok) mainH.postDelayed({ clickAnyAlias(listOf("Clear all", "Close all", "Close all apps")) }, 450)
        }, 650)
        return "பின்னணி செயலிகள் அனைத்தும் மூடப்படுகின்றன."
    }

    fun lockScreenNow(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            if (ok) "திரை பூட்டப்பட்டது." else "திரையைப் பூட்ட முடியவில்லை."
        } else "Android 9+ தேவை."
    }

    fun takeScreenshotNow(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val ok = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            if (ok) "ஸ்கிரீன்ஷாட் எடுக்கப்பட்டது." else "ஸ்கிரீன்ஷாட் எடுக்க முடியவில்லை."
        } else "Android 9+ தேவை."
    }
}
