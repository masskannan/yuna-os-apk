package com.yuna.os.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yuna.os.overlay.YunaGhostOverlayService
import org.json.JSONArray
import org.json.JSONObject

class PuyalAccessService_v2 : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PuyalAccessService_v2? = null
        private val SENSITIVE_KEYWORDS = listOf("pay", "upi", "pin", "delete", "remove", "transfer", "confirm order", "uninstall", "format")
        private val RESTRICTED_PKG_TOKENS = listOf(
            "paisa", "phonepe", "paytm", "bhim", "yono", "sbi", "hdfc", "icici", "axis", "kotak", "yesbank",
            "bank", "cred", "authenticator", "bitwarden", "keepass", "1password", "lastpass", "packageinstaller"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSkipAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.google.android.youtube") return
        val now = System.currentTimeMillis()
        if (now - lastSkipAt < 2000L) return
        val root = rootInActiveWindow ?: return
        val skipNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/skip_ad_button")
            .ifEmpty { root.findAccessibilityNodeInfosByText("Skip") }
        for (n in skipNodes) {
            val txt = n.text?.toString()?.trim() ?: ""
            if (n.isClickable && (txt.isEmpty() || txt.equals("Skip", true) || txt.contains("Skip Ad", true))) {
                lastSkipAt = now
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getActivePackage(): String = rootInActiveWindow?.packageName?.toString() ?: "unknown"

    fun isRestrictedApp(pkg: String = getActivePackage()): Boolean {
        val low = pkg.lowercase()
        return RESTRICTED_PKG_TOKENS.any { low.contains(it) }
    }

    fun extractUiTreeJson(maxNodes: Int = 90): JSONArray {
        val arr = JSONArray()
        if (isRestrictedApp()) return arr
        val root = rootInActiveWindow ?: return arr
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()

        while (queue.isNotEmpty() && arr.length() < maxNodes) {
            val node = queue.removeFirst()
            val txt = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val rid = node.viewIdResourceName?.substringAfterLast("/") ?: ""
            if (node.isClickable || node.isEditable || txt.isNotEmpty() || desc.isNotEmpty()) {
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    arr.put(JSONObject().apply {
                        if (rid.isNotEmpty()) put("id", rid)
                        if (txt.isNotEmpty()) put("txt", txt.take(80))
                        if (desc.isNotEmpty()) put("desc", desc.take(60))
                        put("cx", rect.centerX())
                        put("cy", rect.centerY())
                        if (node.isClickable) put("clk", 1)
                        if (node.isEditable) put("edt", 1)
                    })
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return arr
    }

    fun executeBatchAndVerify(actions: JSONArray, onVerified: (JSONArray) -> Unit) {
        if (isRestrictedApp()) {
            sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_CAPTION)
                .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "பாதுகாப்பு: Banking/UPI/Password ஆப்-ல் YUNA தலையிடாது."))
            onVerified(JSONArray())
            return
        }
        sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SET_STATE).putExtra(YunaGhostOverlayService.EXTRA_STATE, "EXECUTING"))
        runStep(actions, 0, onVerified)
    }

    private fun runStep(actions: JSONArray, idx: Int, onVerified: (JSONArray) -> Unit) {
        if (idx >= actions.length()) {
            handler.postDelayed({
                val tree = extractUiTreeJson()
                sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SET_STATE).putExtra(YunaGhostOverlayService.EXTRA_STATE, "IDLE"))
                onVerified(tree)
            }, 1300L)
            return
        }
        val step = actions.optJSONObject(idx) ?: return runStep(actions, idx + 1, onVerified)
        val act = step.optString("action", "")
        val targetTxt = step.optString("target_text", "")
        if (SENSITIVE_KEYWORDS.any { targetTxt.lowercase().contains(it) } && !step.optBoolean("_confirmed", false)) {
            val rcv = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    runCatching { unregisterReceiver(this) }
                    val ok = i?.getBooleanExtra(YunaGhostOverlayService.EXTRA_CONFIRMED, false) == true
                    if (ok) {
                        step.put("_confirmed", true)
                        handler.post { runStep(actions, idx, onVerified) }
                    } else {
                        sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SET_STATE).putExtra(YunaGhostOverlayService.EXTRA_STATE, "IDLE"))
                        onVerified(JSONArray())
                    }
                }
            }
            val flt = android.content.IntentFilter(YunaGhostOverlayService.ACTION_USER_CONFIRMED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, flt, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(rcv, flt)
            sendBroadcast(Intent(YunaGhostOverlayService.ACTION_REQUEST_CONFIRM)
                .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "தொடரவா: $targetTxt?"))
            return
        }

        when (act) {
            "open_app" -> {
                val appName = step.optString("app", "").ifEmpty { targetTxt }
                if (appName.isNotEmpty()) {
                    runCatching {
                        val pm = packageManager
                        val mi = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val match = pm.queryIntentActivities(mi, 0).firstOrNull {
                            it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
                        }
                        val launch = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                        if (launch != null) startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            "intent" -> {
                val uriStr = step.optString("uri", "")
                if (uriStr.isNotEmpty()) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            "click" -> {
                val rid = step.optString("target_id", "")
                val clicked = clickNodeByIdOrText(rid, targetTxt)
                if (!clicked) {
                    val x = step.optInt("x", 0).toFloat()
                    val y = step.optInt("y", 0).toFloat()
                    if (x > 0f && y > 0f) tapCoordinates(x, y)
                }
            }
            "set_text" -> {
                val txt = step.optString("text", "")
                setTextOnFocusedOrId(step.optString("target_id", ""), txt)
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "scroll" -> tapSwipe(500f, 1400f, 500f, 600f)
        }
        handler.postDelayed({ runStep(actions, idx + 1, onVerified) }, 350L)
    }

    private fun clickNodeByIdOrText(rid: String, txt: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = when {
            txt.isNotEmpty() -> root.findAccessibilityNodeInfosByText(txt)
            rid.isNotEmpty() -> root.findAccessibilityNodeInfosByViewId(rid)
            else -> emptyList()
        }
        for (m in matches) {
            var cur: AccessibilityNodeInfo? = m
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    private fun setTextOnFocusedOrId(rid: String, txt: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = if (rid.isNotEmpty()) root.findAccessibilityNodeInfosByViewId(rid).firstOrNull()
        else root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, txt) }
        return target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
    }

    private fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun tapSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 280L))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun takeScreenshotCompat(onDone: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(null); return
        }
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    runCatching {
                        val bmp = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                        res.hardwareBuffer.close()
                        if (bmp == null) { onDone(null); return }
                        val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                        bmp.recycle()
                        val w = 640
                        val h = (sw.height * 640f / sw.width).toInt().coerceAtLeast(480)
                        val scaled = Bitmap.createScaledBitmap(sw, w, h, true)
                        sw.recycle()
                        var nonBlackFound = false
                        var pxY = 40
                        while (pxY < scaled.height && !nonBlackFound) {
                            var pxX = 40
                            while (pxX < scaled.width) {
                                if ((scaled.getPixel(pxX, pxY) and 0x00FFFFFF) != 0) { nonBlackFound = true; break }
                                pxX += 80
                            }
                            pxY += 80
                        }
                        if (!nonBlackFound) { scaled.recycle(); onDone(null); return }
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        scaled.recycle()
                        onDone(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    }.onFailure { onDone(null) }
                }
                override fun onFailure(errorCode: Int) { onDone(null) }
            })
        }.onFailure { onDone(null) }
    }

}
