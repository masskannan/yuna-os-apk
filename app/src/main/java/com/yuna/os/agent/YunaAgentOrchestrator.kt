package com.yuna.os.agent

import android.content.*
import android.os.Build
import android.provider.Settings
import com.yuna.os.accessibility.PuyalAccessService_v2
import com.yuna.os.overlay.YunaGhostOverlayService
import com.yuna.os.vision.YunaScreenVision
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object YunaAgentOrchestrator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    @Volatile var isCancelled = false
    @Volatile var isScreenShareOn = false
    @Volatile var screenVision: YunaScreenVision? = null

    private var confirmLatch: CountDownLatch? = null
    @Volatile private var confirmDecision = false
    private var receiverRegistered = false

    fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        receiverRegistered = true
        val f = IntentFilter().apply {
            addAction(YunaGhostOverlayService.ACTION_USER_CONFIRMED)
            addAction(YunaGhostOverlayService.ACTION_END_SESSION)
            addAction(YunaGhostOverlayService.ACTION_SCREEN_SHARE_TOGGLE)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    YunaGhostOverlayService.ACTION_USER_CONFIRMED -> {
                        confirmDecision = intent.getBooleanExtra(YunaGhostOverlayService.EXTRA_CONFIRMED, false)
                        if (!confirmDecision) cancel()
                        confirmLatch?.countDown()
                    }
                    YunaGhostOverlayService.ACTION_END_SESSION -> cancel()
                    YunaGhostOverlayService.ACTION_SCREEN_SHARE_TOGGLE -> {
                        isScreenShareOn = intent.getBooleanExtra("enabled", false)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) context.applicationContext.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else context.applicationContext.registerReceiver(r, f)
    }

    fun cancel() {
        isCancelled = true
        confirmLatch?.countDown()
        currentJob?.cancel()
    }

    fun runGoal(context: Context, goal: String, cloudBaseUrl: String, appToken: String) {
        val appCtx = context.applicationContext
        ensureReceiver(appCtx)
        cancel()
        isCancelled = false

        val access = PuyalAccessService_v2.instance
        if (access == null) {
            appCtx.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_CAPTION)
                .putExtra(YunaGhostOverlayService.EXTRA_TEXT, "Accessibility சேவையை ஆன் செய்யுங்கள்"))
            appCtx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        currentJob = scope.launch {
            val history = JSONArray()
            var stepCount = 0
            while (!isCancelled && stepCount < 8) {
                stepCount++
                appCtx.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SET_STATE)
                    .putExtra(YunaGhostOverlayService.EXTRA_STATE, "THINKING"))

                val pkg = access.getActivePackage()
                val uiTree = access.extractUiTreeJson(60)
                val screenDesc = if ((isScreenShareOn || uiTree.length() < 3) && screenVision != null) {
                    screenVision?.captureAndAskVision("Summarize visible screen buttons and text in 2 lines.") ?: ""
                } else ""
                val res = callAgentStep(cloudBaseUrl, appToken, goal, pkg, uiTree, history, screenDesc)
                if (res == null || isCancelled) break

                val caption = res.optString("caption_ta", "")
                if (caption.isNotEmpty()) {
                    appCtx.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_CAPTION)
                        .putExtra(YunaGhostOverlayService.EXTRA_TEXT, caption))
                }

                if (res.optBoolean("needs_confirm", false)) {
                    val reason = res.optString("confirm_reason", "இந்த செயலைத் தொடரவா?")
                    confirmDecision = false
                    confirmLatch = CountDownLatch(1)
                    appCtx.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_REQUEST_CONFIRM)
                        .putExtra(YunaGhostOverlayService.EXTRA_TEXT, reason))
                    confirmLatch?.await(30, TimeUnit.SECONDS)
                    if (!confirmDecision || isCancelled) break
                }

                if (res.optBoolean("done", false)) {
                    if (caption.isNotEmpty()) {
                        appCtx.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SHOW_RESULT)
                            .putExtra(YunaGhostOverlayService.EXTRA_TEXT, caption))
                    }
                    break
                }

                val actions = res.optJSONArray("actions") ?: JSONArray()
                if (actions.length() == 0) break
                history.put(JSONObject().put("step", stepCount).put("actions", actions))

                val stepLatch = CountDownLatch(1)
                access.executeBatchAndVerify(actions) {
                    stepLatch.countDown()
                }
                stepLatch.await(8, TimeUnit.SECONDS)
            }
            appCtx.sendBroadcast(Intent(YunaGhostOverlayService.ACTION_SET_STATE)
                .putExtra(YunaGhostOverlayService.EXTRA_STATE, "IDLE"))
        }
    }

    private fun callAgentStep(
        baseUrl: String, token: String, goal: String, pkg: String, uiTree: JSONArray, history: JSONArray, screenDesc: String = ""
    ): JSONObject? {
        return try {
            val conn = (URL("${baseUrl.trimEnd('/')}/agent/step").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Yuna-Token", token)
                doOutput = true
            }
            val body = JSONObject().apply {
                put("goal", goal)
                put("app_package", pkg)
                put("ui_tree", uiTree)
                put("history", history)
                if (screenDesc.isNotEmpty()) put("screen_context", screenDesc)
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }
}
