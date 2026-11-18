package com.puyal.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.puyal.os.service.PuyalCoreService
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var rootContainer: LinearLayout
    private lateinit var chatFeed: LinearLayout
    private lateinit var greetingBlock: LinearLayout
    private lateinit var drawerCard: LinearLayout
    private lateinit var plusSheet: LinearLayout
    private lateinit var inputPill: LinearLayout
    private lateinit var inputBox: EditText

    private lateinit var servicePill: TextView
    private lateinit var telemetryText: TextView
    private lateinit var rootBadge: TextView
    private lateinit var permOverlayBadge: TextView
    private lateinit var permMicBadge: TextView
    private lateinit var permNotifBadge: TextView
    private lateinit var permBatteryBadge: TextView

    private var oomScoreValue = "checking..."
    private var isDrawerOpen = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge mounting cleanly above Android's 3-button navigation bar & IME keyboard
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        buildGeminiCloneUi()
        applySystemBarAndImeInsets()
        probeRootAndOomAsync()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        probeRootAndOomAsync()
    }

    private fun applySystemBarAndImeInsets() {
        val d = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(sysBars.bottom, ime.bottom)
            view.setPadding(
                sysBars.left,
                sysBars.top,
                sysBars.right,
                bottomInset + (8 * d).toInt()
            )
            windowInsets
        }
    }

    private fun buildGeminiCloneUi() {
        val d = resources.displayMetrics.density

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }

        // 1. Top Bar (56dp height — exact Gemini mobile header proportions, 100% PUYAL branding)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * d).toInt()
            )
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
        }

        val menuBtn = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding((8 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            setOnClickListener {
                isDrawerOpen = !isDrawerOpen
                drawerCard.visibility = if (isDrawerOpen) View.VISIBLE else View.GONE
            }
        }

        val brandTitle = TextView(this).apply {
            text = "🌪️ Puyal"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        servicePill = TextView(this).apply {
            text = "OFFLINE"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = pillDrawable("#161D26", "#334155", 99 * d)
            setTextColor(Color.parseColor("#94A3B8"))
            setOnClickListener {
                if (PuyalCoreService.isRunning) {
                    startService(Intent(this@MainActivity, PuyalCoreService::class.java).apply {
                        action = PuyalCoreService.ACTION_STOP_SERVICE
                    })
                } else {
                    ContextCompat.startForegroundService(
                        this@MainActivity,
                        Intent(this@MainActivity, PuyalCoreService::class.java)
                    )
                }
                topBar.postDelayed({ refreshDashboard(); probeRootAndOomAsync() }, 350)
            }
        }

        topBar.addView(menuBtn)
        topBar.addView(brandTitle)
        topBar.addView(servicePill)
        rootContainer.addView(topBar)

        // 2. Collapsible System & Permission Drawer (Sovereign OS Controls)
        drawerCard = buildSystemDrawer(d)
        drawerCard.visibility = View.GONE
        rootContainer.addView(drawerCard)

        // 3. Center Scrollable Stage (Greeting + Chips + Live Responses)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
        }

        greetingBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (36 * d).toInt(), 0, (16 * d).toInt())
        }
        greetingBlock.addView(TextView(this).apply {
            text = "வணக்கம் பாஸ்"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })
        greetingBlock.addView(TextView(this).apply {
            text = "என்ன உதவி செய்யணும்?"
            textSize = 24f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (4 * d).toInt(), 0, (22 * d).toInt())
        })

        // 2x2 Quick Action Chips (matching Gemini pill-card proportions)
        val chipRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipRow1.addView(makeSuggestionCard("🚀 Start Immortal Core", 0) {
            ContextCompat.startForegroundService(this, Intent(this, PuyalCoreService::class.java))
            appendMessage("user", "Start Puyal Immortal Core")
            appendMessage("puyal", "Immortal Core active! 3D 🌪️ bubble launched with 0-FPS screen-off guard.")
            topBar.postDelayed({ refreshDashboard(); probeRootAndOomAsync() }, 350)
        })
        chipRow1.addView(makeSuggestionCard("🎙️ Test 3D Mic Swirl", (10 * d).toInt()) {
            triggerServiceState("LISTENING")
            appendMessage("puyal", "Mic Listening ON — 3D 🌪️ helical spin + Cyan sonar ring active.")
        })

        val chipRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        chipRow2.addView(makeSuggestionCard("💬 Open :7777 UI", 0) {
            openUrl("http://127.0.0.1:7777")
        })
        chipRow2.addView(makeSuggestionCard("⚙️ System & Perms", (10 * d).toInt()) {
            isDrawerOpen = true
            drawerCard.visibility = View.VISIBLE
        })

        greetingBlock.addView(chipRow1)
        greetingBlock.addView(chipRow2)
        stage.addView(greetingBlock)

        chatFeed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stage.addView(chatFeed)

        scroll.addView(stage)
        rootContainer.addView(scroll)

        // 4. Plus Attachment/Tool Sheet (hidden above input pill)
        plusSheet = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        }
        plusSheet.addView(makeMiniTool("📦 :7778 Store") { openUrl("http://127.0.0.1:7778") })
        plusSheet.addView(makeMiniTool("⚡ EXEC Burst") { triggerServiceState("ACTION_BURST") })
        plusSheet.addView(makeMiniTool("🟠 BG Drift") { triggerServiceState("PROCESSING") })
        rootContainer.addView(plusSheet)

        // 5. Bottom Floating Pill Input Bar (28dp corner radius, ~58dp height, mounted cleanly above 3-button nav bar)
        inputPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (58 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            background = pillDrawable("#161D26", "#263344", 28 * d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (14 * d).toInt()
                rightMargin = (14 * d).toInt()
                topMargin = (6 * d).toInt()
                bottomMargin = (4 * d).toInt()
            }
        }

        val plusBtn = TextView(this).apply {
            text = "＋"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt())
            setOnClickListener {
                plusSheet.visibility = if (plusSheet.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        inputBox = EditText(this).apply {
            hint = "Puyal கிட்ட கேளுங்க..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            background = null
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }

        val micBtn = TextView(this).apply {
            text = "🎙️"
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt())
            setOnClickListener {
                triggerServiceState("LISTENING")
                appendMessage("puyal", "Voice Mic Hook ON — Level 3 Sherpa-ONNX ready.")
            }
        }

        val sendBtn = TextView(this).apply {
            text = "🌪️"
            textSize = 18f
            gravity = Gravity.CENTER
            background = pillDrawable("#0E293B", "#38BDF8", 99 * d)
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                leftMargin = (4 * d).toInt()
            }
            setOnClickListener {
                val q = inputBox.text.toString().trim()
                if (q.isNotEmpty()) {
                    inputBox.setText("")
                    appendMessage("user", q)
                    triggerServiceState("ACTION_BURST")
                    appendMessage("puyal", "Received: \"$q\" — Local Rust/Wasm Brain connects in Level 2 & 4.")
                } else {
                    triggerServiceState("IDLE")
                    Toast.makeText(this@MainActivity, "Puyal Idle (0-FPS)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        inputPill.addView(plusBtn)
        inputPill.addView(inputBox)
        inputPill.addView(micBtn)
        inputPill.addView(sendBtn)
        rootContainer.addView(inputPill)

        setContentView(rootContainer)
    }

    private fun buildSystemDrawer(d: Float): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = pillDrawable("#11171F", "#263344", 18 * d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = (14 * d).toInt()
                rightMargin = (14 * d).toInt()
                bottomMargin = (8 * d).toInt()
            }
        }

        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hdr.addView(TextView(this).apply {
            text = "SOVEREIGN KERNEL & PERMS"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        rootBadge = TextView(this).apply {
            text = "SU: ..."
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#F59E0B"))
        }
        hdr.addView(rootBadge)
        card.addView(hdr)

        telemetryText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(0, (6 * d).toInt(), 0, (8 * d).toInt())
        }
        card.addView(telemetryText)

        permOverlayBadge = addPermRow(card, "Overlay Bubble (Draw Over Apps)") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        permMicBadge = addPermRow(card, "Mic & Camera") {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }
        permNotifBadge = addPermRow(card, "Foreground Notification") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        permBatteryBadge = addPermRow(card, "Battery Unrestricted") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
        return card
    }

    private fun addPermRow(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val b = TextView(this).apply {
            text = "..."
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
        }
        row.addView(b)
        parent.addView(row)
        return b
    }

    private fun makeSuggestionCard(label: String, leftMarginPx: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            background = pillDrawable("#161D26", "#263344", 16 * d)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = leftMarginPx
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeMiniTool(label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = pillDrawable("#161D26", "#334155", 99 * d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * d).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun appendMessage(role: String, text: String) {
        greetingBlock.visibility = View.GONE
        val d = resources.displayMetrics.density
        val msg = TextView(this).apply {
            this.text = if (role == "user") text else "🌪️  $text"
            textSize = 14f
            setTextColor(Color.parseColor("#F1F5F9"))
            setPadding((14 * d).toInt(), (11 * d).toInt(), (14 * d).toInt(), (11 * d).toInt())
            background = if (role == "user") {
                pillDrawable("#1E293B", "#334155", 18 * d)
            } else {
                pillDrawable("#11171F", "#1E293B", 18 * d)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (role == "user") Gravity.END else Gravity.START
                bottomMargin = (10 * d).toInt()
            }
        }
        chatFeed.addView(msg)
    }

    private fun triggerServiceState(stateName: String) {
        if (!PuyalCoreService.isRunning) {
            ContextCompat.startForegroundService(this, Intent(this, PuyalCoreService::class.java))
        }
        startService(Intent(this, PuyalCoreService::class.java).apply {
            action = PuyalCoreService.ACTION_SET_STATE
            putExtra(PuyalCoreService.EXTRA_STATE, stateName)
        })
        telemetryText.postDelayed({ refreshDashboard() }, 200)
    }

    private fun pillDrawable(bgHex: String, strokeHex: String, radiusPx: Float): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.parseColor(bgHex))
            setStroke((1 * d).toInt(), Color.parseColor(strokeHex))
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Browser error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun styleBadge(badge: TextView, ok: Boolean, okText: String = "READY", badText: String = "FIX") {
        val d = resources.displayMetrics.density
        badge.text = if (ok) okText else badText
        badge.setTextColor(Color.parseColor(if (ok) "#00FF88" else "#F59E0B"))
        badge.background = pillDrawable(if (ok) "#071E14" else "#1F1607", if (ok) "#00FF88" else "#F59E0B", 99 * d)
    }

    private fun refreshDashboard() {
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val svcOk = PuyalCoreService.isRunning

        styleBadge(permOverlayBadge, overlayOk)
        styleBadge(permMicBadge, micOk)
        styleBadge(permNotifBadge, notifOk)
        styleBadge(permBatteryBadge, batteryOk, "OK", "FIX")

        val d = resources.displayMetrics.density
        servicePill.text = if (svcOk) "● IMMORTAL" else "OFFLINE"
        servicePill.setTextColor(Color.parseColor(if (svcOk) "#00FF88" else "#94A3B8"))
        servicePill.background = pillDrawable(if (svcOk) "#071E14" else "#161D26", if (svcOk) "#00FF88" else "#334155", 99 * d)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        telemetryText.text = "PID ${Process.myPid()} ($abi) • OOM: $oomScoreValue • Bubble: ${PuyalCoreService.currentStateName}"
    }

    private fun probeRootAndOomAsync() {
        Thread {
            val pid = Process.myPid()
            val rootOk = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val done = p.waitFor(3, TimeUnit.SECONDS)
                if (!done) { p.destroyForcibly(); false } else p.exitValue() == 0
            } catch (_: Exception) {
                false
            }
            val oom = try {
                File("/proc/$pid/oom_score_adj").readText().trim()
            } catch (_: Exception) {
                if (rootOk) "-1000" else "0"
            }
            oomScoreValue = oom
            runOnUiThread {
                rootBadge.text = if (rootOk) "SU: GRANTED" else "SU: NO ROOT"
                rootBadge.setTextColor(Color.parseColor(if (rootOk) "#00FF88" else "#F59E0B"))
                refreshDashboard()
            }
        }.start()
    }
}
