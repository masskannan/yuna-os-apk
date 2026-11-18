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
import android.widget.FrameLayout
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

    private lateinit var rootFrame: FrameLayout
    private lateinit var mainCol: LinearLayout
    private lateinit var chatFeed: LinearLayout
    private lateinit var heroStage: LinearLayout
    private lateinit var drawerOverlay: FrameLayout
    private lateinit var overflowMenu: LinearLayout
    private lateinit var settingsModal: FrameLayout
    private lateinit var plusSheet: LinearLayout
    private lateinit var inputBox: EditText

    private lateinit var modePill: TextView
    private lateinit var telemetryPill: TextView
    private lateinit var nav3Btn: TextView
    private lateinit var nav2Btn: TextView
    private lateinit var navGestBtn: TextView
    private lateinit var svcToggleBtn: TextView
    private lateinit var settingsTeleText: TextView
    private lateinit var permOverlayBadge: TextView
    private lateinit var permMicBadge: TextView
    private lateinit var permNotifBadge: TextView
    private lateinit var permBatteryBadge: TextView

    private val modes = listOf("RUST_CORE", "EVERYDAY", "SCREEN_OP")
    private var modeIdx = 0
    private var navMode = "3BTN"
    private var oomScoreValue = "checking"
    private var isRootGranted = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        buildYunaStealthUi()
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
        ViewCompat.setOnApplyWindowInsetsListener(mainCol) { view, windowInsets ->
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
        ViewCompat.requestApplyInsets(mainCol)
    }

    private fun buildYunaStealthUi() {
        val d = resources.displayMetrics.density
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#06080A"))
        }

        mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#06080A"))
        }

        // 1. Stealth Top Bar (56dp): [☰]   [YUNA] [RUST_CORE ▾]   [⋮]
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
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding((8 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            setOnClickListener {
                overflowMenu.visibility = View.GONE
                drawerOverlay.visibility = View.VISIBLE
            }
        }

        val centerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val brandTxt = TextView(this).apply {
            text = "YUNA"
            textSize = 19f
            letterSpacing = 0.14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, (10 * d).toInt(), 0)
        }
        modePill = TextView(this).apply {
            text = "● ${modes[modeIdx]} ▾"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = glassDrawable("#0D1217", "#2A3844", 99 * d)
            setOnClickListener {
                modeIdx = (modeIdx + 1) % modes.size
                text = "● ${modes[modeIdx]} ▾"
                toastInfo("Mode: ${modes[modeIdx]}")
            }
        }
        centerWrap.addView(brandTxt)
        centerWrap.addView(modePill)

        val overflowBtn = TextView(this).apply {
            text = "⋮"
            textSize = 21f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding((12 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            setOnClickListener {
                overflowMenu.visibility = if (overflowMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        topBar.addView(menuBtn)
        topBar.addView(centerWrap)
        topBar.addView(overflowBtn)
        mainCol.addView(topBar)

        // 2. Center Scroll Stage (Volumetric Storm Mist Hero + 2x2 Specular Bento Grid)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
        }

        heroStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (24 * d).toInt(), 0, (16 * d).toInt())
        }

        // Volumetric subtle glow behind greeting
        val heroGlow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), (24 * d).toInt(), (16 * d).toInt(), (20 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(42, 0, 229, 255),
                    Color.argb(14, 203, 213, 225),
                    Color.TRANSPARENT
                )
            ).apply { cornerRadius = 28 * d }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        heroGlow.addView(TextView(this).apply {
            text = "Vanakkam Boss"
            textSize = 27f
            gravity = Gravity.CENTER
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2F8FE"))
        })

        telemetryPill = TextView(this).apply {
            text = "ARM64 • OOM -1000 • 0-FPS"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            background = glassDrawable("#0C1116", "#22303C", 99 * d)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * d).toInt() }
        }
        heroGlow.addView(telemetryPill)
        heroStage.addView(heroGlow)

        // 2x2 Specular Bento Grid
        val bentoRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * d).toInt() }
        }
        bentoRow1.addView(makeBentoCard("⛶", "Scan Screen", 0) {
            submitPrompt("Screen-la enna irukku?")
        })
        bentoRow1.addView(makeBentoCard("💬", "WA Intel", (12 * d).toInt()) {
            submitPrompt("WhatsApp VIP check pannu")
        })

        val bentoRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * d).toInt() }
        }
        bentoRow2.addView(makeBentoCard("🛡️", "Kernel Shield", 0) {
            openSettingsModal()
        })
        bentoRow2.addView(makeBentoCard("🎛️", "Quick Tools", (12 * d).toInt()) {
            submitPrompt("Torch light ON pannu")
        })

        heroStage.addView(bentoRow1)
        heroStage.addView(bentoRow2)
        stage.addView(heroStage)

        chatFeed = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stage.addView(chatFeed)
        scroll.addView(stage)
        mainCol.addView(scroll)

        // 3. Plus Input Sheet (Camera, Photo, File, Scan)
        plusSheet = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding((14 * d).toInt(), (6 * d).toInt(), (14 * d).toInt(), (6 * d).toInt())
        }
        plusSheet.addView(makeInputTool("📷 Camera") { toastInfo("Camera Vision ready in Level 5") })
        plusSheet.addView(makeInputTool("🖼️ Photo") { toastInfo("Photo picker ready in Level 5") })
        plusSheet.addView(makeInputTool("📁 File") { toastInfo("File attach ready in Level 2") })
        plusSheet.addView(makeInputTool("👁️ Scan") { submitPrompt("Screen-la enna irukku?") })
        mainCol.addView(plusSheet)

        // 4. Smoked-Glass 28dp Floating Input Capsule (Cleanly above 3-Button Nav Bar)
        val inputPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (58 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = glassDrawable("#0E1419", "#243442", 28 * d)
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
            hint = "Yuna kitta kelunga..."
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
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
            setOnClickListener {
                triggerServiceState("LISTENING")
                appendMessage("yuna", "Yuna Listening ON — 3D 🌪️ helical spin active.")
            }
        }

        val vortexSendBtn = TextView(this).apply {
            text = "🌪️"
            textSize = 19f
            gravity = Gravity.CENTER
            background = glassDrawable("#0A2C38", "#00E5FF", 99 * d)
            layoutParams = LinearLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt()).apply {
                leftMargin = (6 * d).toInt()
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
        inputPill.addView(vortexSendBtn)
        mainCol.addView(inputPill)

        rootFrame.addView(mainCol)

        // 5. Slide-Out Left Drawer (New Chat, Files, Skills, Schedules, Customize, ALL CHATS)
        drawerOverlay = buildLeftDrawer(d)
        drawerOverlay.visibility = View.GONE
        rootFrame.addView(drawerOverlay)

        // 6. Top-Right Overflow Menu (⋮)
        overflowMenu = buildOverflowMenu(d)
        overflowMenu.visibility = View.GONE
        rootFrame.addView(overflowMenu)

        // 7. Settings Modal (3-Btn / 2-Btn / Gesture + Immortal Core + Perms + Local Ports)
        settingsModal = buildSettingsModal(d)
        settingsModal.visibility = View.GONE
        rootFrame.addView(settingsModal)

        setContentView(rootFrame)
    }

    private fun buildLeftDrawer(d: Float): FrameLayout {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(175, 0, 0, 0))
            setOnClickListener { visibility = View.GONE }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
            setPadding((18 * d).toInt(), (46 * d).toInt(), (18 * d).toInt(), (24 * d).toInt())
            layoutParams = FrameLayout.LayoutParams((280 * d).toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            setOnClickListener { }
        }

        panel.addView(TextView(this).apply {
            text = "🌪️ YUNA"
            textSize = 18f
            letterSpacing = 0.12f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            setPadding(0, 0, 0, (16 * d).toInt())
        })

        panel.addView(makeDrawerItem("＋  New Chat") {
            chatFeed.removeAllViews()
            heroStage.visibility = View.VISIBLE
            scrim.visibility = View.GONE
        })
        panel.addView(makeDrawerItem("📁  Files & Vault") { openUrl("http://127.0.0.1:7778"); scrim.visibility = View.GONE })
        panel.addView(makeDrawerItem("⚡  Skills (Wasm/Rust)") { toastInfo("Wasm Skills unlock in Level 2 & 4"); scrim.visibility = View.GONE })
        panel.addView(makeDrawerItem("🕒  Schedules") { toastInfo("Autonomous Loop unlocks in Level 6"); scrim.visibility = View.GONE })
        panel.addView(makeDrawerItem("⚙️  Customize & Settings") { scrim.visibility = View.GONE; openSettingsModal() })

        panel.addView(TextView(this).apply {
            text = "ALL CHATS"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
        })

        panel.addView(makeStatusRow("●", "#00FF88", "READY", "OOM -1000 Immortal Core"))
        panel.addView(makeStatusRow("▲", "#F59E0B", "NEEDS ACTION", "Level 1 Smoke Verification"))
        panel.addView(makeStatusRow("◌", "#00E5FF", "PROCESSING", "0-FPS Screen-Off Guard"))

        scrim.addView(panel)
        return scrim
    }

    private fun buildOverflowMenu(d: Float): LinearLayout {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = glassDrawable("#0E1419", "#2A3844", 14 * d)
            layoutParams = FrameLayout.LayoutParams((185 * d).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = (84 * d).toInt()
                rightMargin = (14 * d).toInt()
            }
        }
        val items = listOf(
            "📌 Pin Chat" to { toastInfo("Pinned to Yuna Vault") },
            "✏️ Rename" to { toastInfo("Rename ready in Level 2") },
            "🗑️ Clear Chat" to { chatFeed.removeAllViews(); heroStage.visibility = View.VISIBLE },
            "⚙️ Settings" to { openSettingsModal() }
        )
        items.forEach { (lbl, act) ->
            menu.addView(TextView(this).apply {
                text = lbl
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                setOnClickListener { menu.visibility = View.GONE; act() }
            })
        }
        return menu
    }

    private fun buildSettingsModal(d: Float): FrameLayout {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
            background = glassDrawable("#0D1217", "#2A3844", 20 * d)
            setOnClickListener { }
        }

        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hdr.addView(TextView(this).apply {
            text = "⚙️ YUNA SETTINGS"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        hdr.addView(TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            setOnClickListener { scrim.visibility = View.GONE }
        })
        card.addView(hdr)

        card.addView(settingsLabel("Navigation Bar Clearance"))
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (12 * d).toInt()
            }
        }
        nav3Btn = makeNavChip("3-Button", 0) { setNavMode("3BTN") }
        nav2Btn = makeNavChip("2-Button", (6 * d).toInt()) { setNavMode("2BTN") }
        navGestBtn = makeNavChip("Gesture", (6 * d).toInt()) { setNavMode("GESTURE") }
        navRow.addView(nav3Btn); navRow.addView(nav2Btn); navRow.addView(navGestBtn)
        card.addView(navRow)

        card.addView(settingsLabel("Immortal Core & Floating 🌪️ Bubble"))
        svcToggleBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding((12 * d).toInt(), (11 * d).toInt(), (12 * d).toInt(), (11 * d).toInt())
            setOnClickListener {
                if (PuyalCoreService.isRunning) {
                    startService(Intent(this@MainActivity, PuyalCoreService::class.java).apply { action = PuyalCoreService.ACTION_STOP_SERVICE })
                } else {
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, PuyalCoreService::class.java))
                }
                postDelayed({ refreshSettings(); probeRootAndOomAsync() }, 350)
            }
        }
        card.addView(svcToggleBtn)

        settingsTeleText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (8 * d).toInt(), 0, (12 * d).toInt())
        }
        card.addView(settingsTeleText)

        card.addView(settingsLabel("Permissions (Tap to Grant)"))
        permOverlayBadge = addPermRow(card, "Overlay Bubble") {
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permMicBadge = addPermRow(card, "Microphone & Camera") {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }
        permNotifBadge = addPermRow(card, "Notifications") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        permBatteryBadge = addPermRow(card, "Battery Unrestricted") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            }
        }

        card.addView(settingsLabel("Termux Local Consoles"))
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portRow.addView(makeNavChip(":7777 Terminal", 0) { openUrl("http://127.0.0.1:7777") })
        portRow.addView(makeNavChip(":7778 App Store", (6 * d).toInt()) { openUrl("http://127.0.0.1:7778") })
        card.addView(portRow)

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = (18 * d).toInt()
                rightMargin = (18 * d).toInt()
            }
            addView(card)
        }
        scrim.addView(scroll)
        return scrim
    }

    private fun openSettingsModal() {
        refreshSettings()
        probeRootAndOomAsync()
        settingsModal.visibility = View.VISIBLE
    }

    private fun makeBentoCard(icon: String, label: String, leftMarginPx: Int, onClick: () -> Unit): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt())
            background = glassDrawable("#0D1217", "#23303C", 16 * d)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = leftMarginPx
            }
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#CBD5E1"))
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(0, (8 * d).toInt(), 0, 0)
            })
            setOnClickListener { onClick() }
        }
    }

    private fun makeDrawerItem(label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding((10 * d).toInt(), (11 * d).toInt(), (10 * d).toInt(), (11 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun makeStatusRow(dot: String, hex: String, badge: String, title: String): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            addView(TextView(this@MainActivity).apply {
                text = "$dot $badge"
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor(hex))
                setPadding(0, 0, (8 * d).toInt(), 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            })
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
            setPadding(0, (8 * d).toInt(), 0, (6 * d).toInt())
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
            background = glassDrawable("#121920", "#2A3844", 10 * d)
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

    private fun makeInputTool(label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding((11 * d).toInt(), (7 * d).toInt(), (11 * d).toInt(), (7 * d).toInt())
            background = glassDrawable("#0E1419", "#2A3844", 99 * d)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (8 * d).toInt()
            }
            setOnClickListener { plusSheet.visibility = View.GONE; onClick() }
        }
    }

    private fun submitPrompt(q: String) {
        plusSheet.visibility = View.GONE
        appendMessage("user", q)
        triggerServiceState("ACTION_BURST")
        appendMessage("yuna", "Queued on ${modes[modeIdx]}: \"$q\" (Rust/Wasm Brain connects in Level 2 & 4).")
    }

    private fun appendMessage(role: String, text: String) {
        heroStage.visibility = View.GONE
        val d = resources.displayMetrics.density
        val msg = TextView(this).apply {
            this.text = if (role == "user") text else "🌪️  $text"
            textSize = 14f
            setTextColor(Color.parseColor("#F1F5F9"))
            setPadding((14 * d).toInt(), (11 * d).toInt(), (14 * d).toInt(), (11 * d).toInt())
            background = if (role == "user") {
                glassDrawable("#16202A", "#2A3844", 18 * d)
            } else {
                glassDrawable("#0D1217", "#1F2B35", 18 * d)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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

    private fun glassDrawable(bgHex: String, strokeHex: String, radiusPx: Float): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.parseColor(bgHex))
            setStroke((1 * d).toInt(), Color.parseColor(strokeHex))
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toastInfo("Browser error: ${e.message}") }
    }

    private fun toastInfo(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun stylePerm(badge: TextView, ok: Boolean) {
        val d = resources.displayMetrics.density
        badge.text = if (ok) "READY" else "GRANT"
        badge.setTextColor(Color.parseColor(if (ok) "#00FF88" else "#F59E0B"))
        badge.background = glassDrawable(if (ok) "#071E14" else "#1F1607", if (ok) "#00FF88" else "#F59E0B", 99 * d)
    }

    private fun styleNavChip(chip: TextView, active: Boolean) {
        val d = resources.displayMetrics.density
        chip.setTextColor(Color.parseColor(if (active) "#00E5FF" else "#94A3B8"))
        chip.background = glassDrawable(if (active) "#071B22" else "#121920", if (active) "#00E5FF" else "#2A3844", 10 * d)
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
        svcToggleBtn.text = if (svcOk) "🟢 YUNA FLOATING 🌪️ ACTIVE (STOP)" else "🚀 START YUNA FLOATING 🌪️ BUBBLE"
        svcToggleBtn.setTextColor(Color.parseColor(if (svcOk) "#00FF88" else "#04110A"))
        svcToggleBtn.background = glassDrawable(if (svcOk) "#071E14" else "#00FF88", "#00FF88", 12 * d)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val fpsLabel = if (svcOk) "0-FPS" else "STANDBY"
        telemetryPill.text = "ARM64 • OOM $oomScoreValue • $fpsLabel"
        settingsTeleText.text = "PID ${Process.myPid()} ($abi) • SU: ${if (isRootGranted) "OK" else "NO"} • State: ${PuyalCoreService.currentStateName}"
    }

    private fun probeRootAndOomAsync() {
        Thread {
            val pid = Process.myPid()
            val rootOk = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val done = p.waitFor(3, TimeUnit.SECONDS)
                if (!done) { p.destroyForcibly(); false } else p.exitValue() == 0
            } catch (_: Exception) { false }
            val oom = try {
                File("/proc/$pid/oom_score_adj").readText().trim()
            } catch (_: Exception) { if (rootOk) "-1000" else "0" }
            isRootGranted = rootOk
            oomScoreValue = oom
            runOnUiThread { refreshSettings() }
        }.start()
    }
}
