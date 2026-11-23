package com.yuna.os.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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
        private val SENSITIVE_KEYWORDS = listOf("pay", "upi", "pin", "delete", "remove", "transfer", "confirm order")
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Lightweight passive hook; active tree extraction only when requested
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun getActivePackage(): String = rootInActiveWindow?.packageName?.toString() ?: "unknown"

    fun extractUiTreeJson(maxNodes: Int = 60): JSONArray {
        val arr = JSONArray()
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
                        if (txt.isNotEmpty()) put("txt", txt.take(40))
                        if (desc.isNotEmpty()) put("desc", desc.take(40))
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
        if (SENSITIVE_KEYWORDS.any { targetTxt.lowercase().contains(it) }) {
            sendBroadcast(Intent(YunaGhostOverlayService.ACTION_REQUEST_CONFIRM)
                .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "தொடரவா: $targetTxt?"))
            return
        }

        when (act) {
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
}
