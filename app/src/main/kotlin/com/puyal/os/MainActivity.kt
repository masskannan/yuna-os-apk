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
    private lateinit var settingsOverlay: ScrollView
    private lateinit var plusSheet: LinearLayout
    private lateinit var inputBox: EditText

    private lateinit var nav3Btn: TextView
    private lateinit var nav2Btn: TextView
    private lateinit var navGestBtn: TextView
    private lateinit var svcToggleBtn: TextView
    private lateinit var telemetryText: TextView
    private lateinit var permOverlayBadge: TextView
    private lateinit var permMicBadge: TextView
    private lateinit var permNotifBadge: TextView
    private lateinit var permBatteryBadge: TextView

    private var navMode = "3BTN" // 3BTN, 2BTN, GESTURE
    private var oomScoreValue = "checking..."

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        buildCleanGeminiUi()
        applyNavAndImeInsets()
        probeRootAndOomAsync()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        probeRootAndOomAsync()
    }

    private fun applyNavAndImeInsets() {
        val d = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val sysBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val extraNavDp = when (navMode) {
                "3BTN" -> 8
                "2BTN" -> 6
                else -> 4
            }
            val bottomInset = maxOf(sysBars.bottom, ime.bottom) + (extraNavDp * d).toInt()
            view.setPadding(sysBars.left, sysBars.top, sysBars.right, bottomInset)
            windowInsets
        }
        ViewCompat.requestApplyInsets(rootContainer)
    }

    private fun buildCleanGeminiUi() {
        val d = resources.displayMetrics.density

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }

        // 1. Clean Top Bar (56dp): [New Chat]  [🌪️ Puyal]  [⚙️ Settings]
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * d).toInt()
            )
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
        }

        val newChatBtn = TextView(this).apply {
            text = "＋"
            textSize = 22f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding((8 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            setOnClickListener {
                chatFeed.removeAllViews()
                greetingBlock.visibility = View.VISIBLE
                settingsOverlay.visibility = View.GONE
            }
        }

        val brandTitle = TextView(this).apply {
            text = "🌪️ Puyal"
            textSize = 19f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 20f
            setPadding((10 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            setOnClickListener {
                val show = settingsOverlay.visibility != View.VISIBLE
                settingsOverlay.visibility = if (show) View.VISIBLE else View.GONE
                if (show) {
                    refreshSettings()
                    probeRootAndOomAsync()
                }
            }
        }

        topBar.addView(newChatBtn)
        topBar.addView(brandTitle)
        topBar.addView(settingsBtn)
        rootContainer.addView(topBar)

        // 2. Settings Sheet (Hidden by default — all technical & nav-mode controls live here)
        settingsOverlay = buildSettingsSheet(d)
        settingsOverlay.visibility = View.GONE
        rootContainer.addView(settingsOverlay)

        // 3. Clean Center Chat Stage
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        }

        greetingBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (42 * d).toInt(), 0, (16 * d).toInt())
        }
        greetingBlock.addView(TextView(this).apply {
            text = "வணக்கம் பாஸ்"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
        })
        greetingBlock.addView(TextView(this).apply {
            text = "என்ன உதவி செய்யணும்?"
            textSize = 23f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (4 * d).toInt(), 0, (24 * d).toInt())
        })

        // Clean user-facing prompt suggestions (no dev/test clutter)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(makePromptChip("👁️ Screen-la enna irukku?", 0) { submitPrompt("Screen-la enna irukku?") })
        row1.addView(makePromptChip("🔦 Torch ON pannu", (10 * d).toInt()) { submitPrompt("Torch ON pannu") })

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        row2.addView(makePromptChip("💬 WhatsApp check pannu", 0) { submitPrompt("WhatsApp check pannu") })
        row2.addView(makePromptChip("📋 Today summary kodu", (10 * d).toInt()) { submitPrompt("Today summary kodu") })

        greetingBlock.addView(row1)
        greetingBlock.addView(row2)
        stage.addView(greetingBlock)

        chatFeed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stage.addView(chatFeed)
        scroll.addView(stage)
        rootContainer.addView(scroll)

        // 4. Clean ＋ Input Sheet (strictly Camera, Photo, File, Screen Scan)
        plusSheet = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        }
        plusSheet.addView(makeInputTool("📷 Camera") { toastInfo("Camera Vision ready in Level 5") })
        plusSheet.addView(makeInputTool("🖼️ Photo") { toastInfo("Photo picker ready in Level 5") })
        plusSheet.addView(makeInputTool("📁 File") { toastInfo("File attach ready in Level 2") })
        plusSheet.addView(makeInputTool("👁️ Scan") { submitPrompt("Screen-la enna irukku?") })
        rootContainer.addView(plusSheet)

        // 5. Floating Pill Input Bar (58dp height, 28dp radius, cleanly above 3-button/2-button/gesture nav)
        val inputPill = LinearLayout(this).apply {
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
                appendMessage("puyal", "Mic Listening ON — 3D 🌪️ active (Level 3 Tamil STT hook).")
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
                    submitPrompt(q)
                } else {
                    triggerServiceState("IDLE")
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

    private fun buildSettingsSheet(d: Float): ScrollView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            background = pillDrawable("#11171F", "#263344", 18 * d)
        }

        box.addView(TextView(this).apply {
            text = "⚙️ PUYAL SETTINGS"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, (10 * d).toInt())
        })

        // 1. Navigation Bar Mode Selector (3-Button / 2-Button / Gesture)
        box.addView(settingsLabel("Navigation Bar Clearance"))
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * d).toInt() }
        }
        nav3Btn = makeNavChip("3-Button", 0) { setNavMode("3BTN") }
        nav2Btn = makeNavChip("2-Button", (6 * d).toInt()) { setNavMode("2BTN") }
        navGestBtn = makeNavChip("Gesture", (6 * d).toInt()) { setNavMode("GESTURE") }
        navRow.addView(nav3Btn)
        navRow.addView(nav2Btn)
        navRow.addView(navGestBtn)
        box.addView(navRow)

        // 2. Immortal Floating Bubble Service
        box.addView(settingsLabel("Immortal Core & Bubble"))
        svcToggleBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
            setOnClickListener {
                if (PuyalCoreService.isRunning) {
                    startService(Intent(this@MainActivity, PuyalCoreService::class.java).apply {
                        action = PuyalCoreService.ACTION_STOP_SERVICE
                    })
                } else {
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, PuyalCoreService::class.java))
                }
                postDelayed({ refreshSettings(); probeRootAndOomAsync() }, 350)
            }
        }
        box.addView(svcToggleBtn)

        telemetryText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (8 * d).toInt(), 0, (12 * d).toInt())
        }
        box.addView(telemetryText)

        // 3. Permissions
        box.addView(settingsLabel("Permissions (Tap to Grant)"))
        permOverlayBadge = addPermRow(box, "Overlay Bubble") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        permMicBadge = addPermRow(box, "Microphone & Camera") {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }
        permNotifBadge = addPermRow(box, "Notifications") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        permBatteryBadge = addPermRow(box, "Battery Unrestricted") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        // 4. Local Ports (Isolated from 9999/8888/49321)
        box.addView(settingsLabel("Local Consoles"))
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portRow.addView(makeNavChip(":7777 Web UI", 0) { openUrl("http://127.0.0.1:7777") })
        portRow.addView(makeNavChip(":7778 App Store", (6 * d).toInt()) { openUrl("http://127.0.0.1:7778") })
        box.addView(portRow)

        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (380 * d).toInt()
            ).apply {
                leftMargin = (14 * d).toInt()
                rightMargin = (14 * d).toInt()
                bottomMargin = (6 * d).toInt()
            }
            addView(box)
        }
    }

    private fun setNavMode(mode: String) {
        navMode = mode
        applyNavAndImeInsets()
        refreshSettings()
    }

    private fun settingsLabel(title: String): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (4 * d).toInt(), 0, (6 * d).toInt())
        }
    }

    private fun makeNavChip(label: String, leftMarginPx: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding((8 * d).toInt(), (9 * d).toInt(), (8 * d).toInt(), (9 * d).toInt())
            background = pillDrawable("#161D26", "#334155", 10 * d)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = leftMarginPx
            }
            setOnClickListener { onClick() }
        }
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

    private fun makePromptChip(label: String, leftMarginPx: Int, onClick: () -> Unit): TextView {
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

    private fun makeInputTool(label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding((11 * d).toInt(), (7 * d).toInt(), (11 * d).toInt(), (7 * d).toInt())
            background = pillDrawable("#161D26", "#334155", 99 * d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = (8 * d).toInt() }
            setOnClickListener {
                plusSheet.visibility = View.GONE
                onClick()
            }
        }
    }

    private fun submitPrompt(q: String) {
        plusSheet.visibility = View.GONE
        settingsOverlay.visibility = View.GONE
        appendMessage("user", q)
        triggerServiceState("ACTION_BURST")
        appendMessage("puyal", "Command queued: \"$q\" (Rust/Wasm Brain links in Level 2 & 4).")
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
            toastInfo("Browser error: ${e.message}")
        }
    }

    private fun toastInfo(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun stylePerm(badge: TextView, ok: Boolean) {
        val d = resources.displayMetrics.density
        badge.text = if (ok) "READY" else "GRANT"
        badge.setTextColor(Color.parseColor(if (ok) "#00FF88" else "#F59E0B"))
        badge.background = pillDrawable(if (ok) "#071E14" else "#1F1607", if (ok) "#00FF88" else "#F59E0B", 99 * d)
    }

    private fun styleNavChip(chip: TextView, active: Boolean) {
        val d = resources.displayMetrics.density
        chip.setTextColor(Color.parseColor(if (active) "#00FF88" else "#94A3B8"))
        chip.background = pillDrawable(if (active) "#071E14" else "#161D26", if (active) "#00FF88" else "#334155", 10 * d)
    }

    private fun refreshSettings() {
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val svcOk = PuyalCoreService.isRunning

        stylePerm(permOverlayBadge, overlayOk)
        stylePerm(permMicBadge, micOk)
        stylePerm(permNotifBadge, notifOk)
        stylePerm(permBatteryBadge, batteryOk)

        styleNavChip(nav3Btn, navMode == "3BTN")
        styleNavChip(nav2Btn, navMode == "2BTN")
        styleNavChip(navGestBtn, navMode == "GESTURE")

        val d = resources.displayMetrics.density
        svcToggleBtn.text = if (svcOk) "🟢 FLOATING 🌪️ BUBBLE ACTIVE (TAP TO STOP)" else "🚀 START FLOATING 🌪️ BUBBLE"
        svcToggleBtn.setTextColor(Color.parseColor(if (svcOk) "#00FF88" else "#04110A"))
        svcToggleBtn.background = pillDrawable(if (svcOk) "#071E14" else "#00FF88", "#00FF88", 12 * d)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        telemetryText.text = "PID ${Process.myPid()} ($abi) • OOM: $oomScoreValue • State: ${PuyalCoreService.currentStateName}"
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
            runOnUiThread { refreshSettings() }
        }.start()
    }
}
