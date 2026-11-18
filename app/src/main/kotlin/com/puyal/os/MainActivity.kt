package com.puyal.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
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
    private lateinit var yunaOrbView: YunaOrbView
    private lateinit var drawerOverlay: FrameLayout
    private lateinit var settingsModal: FrameLayout
    private lateinit var plusSheet: LinearLayout
    private lateinit var inputBox: EditText

    private lateinit var orbStatusLabel: TextView
    private lateinit var nav3Btn: TextView
    private lateinit var nav2Btn: TextView
    private lateinit var navGestBtn: TextView
    private lateinit var svcToggleBtn: TextView
    private lateinit var settingsTeleText: TextView
    private lateinit var permOverlayBadge: TextView
    private lateinit var permMicBadge: TextView
    private lateinit var permNotifBadge: TextView
    private lateinit var permBatteryBadge: TextView

    private var navMode = "3BTN"
    private var oomScoreValue = "checking"
    private var isRootGranted = false
    private var isTorchOn = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        buildYunaOriginalUi()
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

    private fun buildYunaOriginalUi() {
        val d = resources.displayMetrics.density
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05070C"))
        }

        mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TR_BL,
                intArrayOf(
                    Color.parseColor("#0C1E2E"),
                    Color.parseColor("#080B18"),
                    Color.parseColor("#05070C")
                )
            )
        }

        // 1. Minimal Top Bar: [☰]   YUNA [20MB • LOCAL]   [⚙️]
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt())
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
        }

        val menuBtn = TextView(this).apply {
            text = "☰"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#CBD5E1"))
            background = glassPill("#101726", "#26354D", 12 * d)
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
            setOnClickListener { drawerOverlay.visibility = View.VISIBLE }
        }

        val centerWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        centerWrap.addView(TextView(this).apply {
            text = "YUNA"
            textSize = 19f
            letterSpacing = 0.16f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, (10 * d).toInt(), 0)
        })
        centerWrap.addView(TextView(this).apply {
            text = "20MB • LOCAL"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = glassPill("#0D1320", "#26354D", 99 * d)
        })

        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 18f
            gravity = Gravity.CENTER
            background = glassPill("#101726", "#26354D", 12 * d)
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
            setOnClickListener { openSettingsModal() }
        }

        topBar.addView(menuBtn)
        topBar.addView(centerWrap)
        topBar.addView(settingsBtn)
        mainCol.addView(topBar)

        // 2. Center Scroll Stage (0-FPS Kinetic Orb + Asymmetric Tool Layout)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (8 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
        }

        heroStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        }

        // Center YUNA Orb (0-FPS Static in Idle to save 100% battery!)
        yunaOrbView = YunaOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams((164 * d).toInt(), (164 * d).toInt())
            setOnClickListener {
                if (PuyalCoreService.currentStateName == "LISTENING") {
                    triggerServiceState("IDLE")
                } else {
                    triggerServiceState("LISTENING")
                }
            }
        }
        heroStage.addView(yunaOrbView)

        heroStage.addView(TextView(this).apply {
            text = "Vanakkam Boss"
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, (6 * d).toInt(), 0, (4 * d).toInt())
        })

        orbStatusLabel = TextView(this).apply {
            text = "OOM -1000 • 0-FPS IDLE (TAP ORB FOR MIC)"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, (16 * d).toInt())
        }
        heroStage.addView(orbStatusLabel)

        // Asymmetric Hero Action Card: Screen Vision & Scan
        val visionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#102235"), Color.parseColor("#13172B"))
            ).apply {
                cornerRadius = 20 * d
                setStroke((1 * d).toInt(), Color.parseColor("#2E4A68"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { submitPrompt("Screen Vision & Scan") }
        }
        visionCard.addView(TextView(this).apply {
            text = "⛶  Screen Vision & Scan"
            textSize = 16f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        visionCard.addView(TextView(this).apply {
            text = "Read active app DOM, OCR & extract actions locally"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (4 * d).toInt(), 0, 0)
        })
        heroStage.addView(visionCard)

        // 3-Pill Quick Rail: [WA Intel] [Root Shield] [Tools]
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * d).toInt()
            }
        }
        rail.addView(makeRailPill("WA Intel", 0) { submitPrompt("WhatsApp VIP Intel check") })
        rail.addView(makeRailPill("Root Shield", (8 * d).toInt()) { openSettingsModal() })
        rail.addView(makeRailPill("Torch", (8 * d).toInt()) { toggleTorchLocal() })
        heroStage.addView(rail)

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
        plusSheet.addView(makeInputTool("👁️ Scan") { submitPrompt("Screen Vision & Scan") })
        mainCol.addView(plusSheet)

        // 4. Thumb-Arc Command Dock (28dp radius, cleanly above 3-Button / 2-Button / Gesture Nav Bar)
        val inputPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (58 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = glassPill("#0E1422", "#283B54", 28 * d)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
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
            hint = "Yuna kitta sollunga..."
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
                appendMessage("yuna", "Yuna Listening ON — Level 3 Sherpa-ONNX Tamil STT hook.")
            }
        }

        val orbSendBtn = TextView(this).apply {
            text = "🌪️"
            textSize = 19f
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0EA5E9"), Color.parseColor("#6366F1"))
            ).apply { shape = GradientDrawable.OVAL }
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
        inputPill.addView(orbSendBtn)
        mainCol.addView(inputPill)

        rootFrame.addView(mainCol)

        // 5. Left Slide Drawer (New Chat, Files, Skills, Schedules, Customize, ALL CHATS)
        drawerOverlay = buildLeftDrawer(d)
        drawerOverlay.visibility = View.GONE
        rootFrame.addView(drawerOverlay)

        // 6. Settings Modal (3-Btn / 2-Btn / Gesture + Immortal Core + Perms + Local Consoles)
        settingsModal = buildSettingsModal(d)
        settingsModal.visibility = View.GONE
        rootFrame.addView(settingsModal)

        setContentView(rootFrame)
    }

    private fun buildLeftDrawer(d: Float): FrameLayout {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setOnClickListener { visibility = View.GONE }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080B14"))
            setPadding((18 * d).toInt(), (46 * d).toInt(), (18 * d).toInt(), (24 * d).toInt())
            layoutParams = FrameLayout.LayoutParams((275 * d).toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
            setOnClickListener { }
        }

        panel.addView(TextView(this).apply {
            text = "YUNA • LOCAL OS"
            textSize = 17f
            letterSpacing = 0.14f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, (16 * d).toInt())
        })

        panel.addView(makeDrawerItem("＋  New Chat") {
            chatFeed.removeAllViews()
            heroStage.visibility = View.VISIBLE
            scrim.visibility = View.GONE
        })
        panel.addView(makeDrawerItem("📁  Files & Vault") { openUrl("http://127.0.0.1:7778"); scrim.visibility = View.GONE })
        panel.addView(makeDrawerItem("⚡  Wasm Skills") { toastInfo("Level 2 & 4 Wasm Brain"); scrim.visibility = View.GONE })
        panel.addView(makeDrawerItem("🕒  Schedules") { toastInfo("Level 6 Autonomous Loop"); scrim.visibility = View.GONE })
        panel.addView(makeDrawerItem("⚙️  Settings") { scrim.visibility = View.GONE; openSettingsModal() })

        panel.addView(TextView(this).apply {
            text = "ALL CHATS"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
        })
        panel.addView(makeStatusRow("●", "#00FF88", "READY", "OOM -1000 Immortal Core"))
        panel.addView(makeStatusRow("▲", "#F59E0B", "NEEDS ACTION", "Level 1 Phone Test"))
        panel.addView(makeStatusRow("◌", "#38BDF8", "PROCESSING", "0-FPS Idle Guard"))

        scrim.addView(panel)
        return scrim
    }

    private fun buildSettingsModal(d: Float): FrameLayout {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
            background = glassPill("#0B101B", "#283B54", 20 * d)
            setOnClickListener { }
        }

        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hdr.addView(TextView(this).apply {
            text = "⚙️ YUNA SETTINGS"
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
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

        card.addView(settingsLabel("Local Consoles"))
        val portRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        portRow.addView(makeNavChip(":7777 Web UI", 0) { openUrl("http://127.0.0.1:7777") })
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

    private fun makeRailPill(label: String, leftMarginPx: Int, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding((10 * d).toInt(), (11 * d).toInt(), (10 * d).toInt(), (11 * d).toInt())
            background = glassPill("#0E1422", "#26354D", 99 * d)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = leftMarginPx
            }
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
            background = glassPill("#111726", "#26354D", 10 * d)
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
            background = glassPill("#0E1422", "#283B54", 99 * d)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = (8 * d).toInt()
            }
            setOnClickListener { plusSheet.visibility = View.GONE; onClick() }
        }
    }

    private fun toggleTorchLocal() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            cm.setTorchMode(id, isTorchOn)
            toastInfo(if (isTorchOn) "Torch ON" else "Torch OFF")
        } catch (e: Exception) {
            toastInfo("Torch error: ${e.message}")
        }
    }

    private fun submitPrompt(q: String) {
        plusSheet.visibility = View.GONE
        appendMessage("user", q)
        triggerServiceState("ACTION_BURST")
        appendMessage("yuna", "Queued locally: \"$q\" (Rust/Wasm Brain links in Level 2 & 4).")
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
                glassPill("#162336", "#2D4666", 18 * d)
            } else {
                glassPill("#0D1320", "#223044", 18 * d)
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
        yunaOrbView.setActive(stateName != "IDLE", stateName)
        orbStatusLabel.postDelayed({ refreshSettings() }, 180)
    }

    private fun glassPill(bgHex: String, strokeHex: String, radiusPx: Float): GradientDrawable {
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
        badge.background = glassPill(if (ok) "#071E14" else "#1F1607", if (ok) "#00FF88" else "#F59E0B", 99 * d)
    }

    private fun styleNavChip(chip: TextView, active: Boolean) {
        val d = resources.displayMetrics.density
        chip.setTextColor(Color.parseColor(if (active) "#38BDF8" else "#94A3B8"))
        chip.background = glassPill(if (active) "#0D2436" else "#111726", if (active) "#38BDF8" else "#26354D", 10 * d)
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
        svcToggleBtn.background = glassPill(if (svcOk) "#071E14" else "#00FF88", "#00FF88", 12 * d)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val st = PuyalCoreService.currentStateName
        yunaOrbView.setActive(st != "IDLE", st)
        orbStatusLabel.text = "OOM $oomScoreValue • ${if (st == "IDLE") "0-FPS IDLE (TAP ORB)" else st}"
        settingsTeleText.text = "PID ${Process.myPid()} ($abi) • SU: ${if (isRootGranted) "OK" else "NO"} • State: $st"
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

    // Lightweight 0-FPS Idle-Safe Kinetic Orb View (draws static in IDLE, zero continuous CPU/GPU drain!)
    private class YunaOrbView(context: Context) : View(context) {
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private var stateName = "IDLE"

        fun setActive(active: Boolean, mode: String) {
            stateName = if (active) mode else "IDLE"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.31f
            val d = resources.displayMetrics.density

            val coreColor = when (stateName) {
                "LISTENING" -> Color.parseColor("#00E5FF")
                "ACTION_BURST" -> Color.parseColor("#00FF88")
                "PROCESSING" -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#38BDF8")
            }

            // Concentric orbital telemetry arcs
            ringPaint.strokeWidth = 1.1f * d
            ringPaint.color = coreColor
            ringPaint.alpha = if (stateName == "IDLE") 65 else 155
            canvas.drawCircle(cx, cy, r * 1.38f, ringPaint)
            ringPaint.alpha = if (stateName == "IDLE") 40 else 110
            canvas.drawCircle(cx, cy, r * 1.62f, ringPaint)

            // Liquid-Glass Sphere Gradient
            orbPaint.shader = RadialGradient(
                cx - r * 0.25f, cy - r * 0.25f, r * 1.2f,
                intArrayOf(
                    coreColor,
                    Color.parseColor("#6366F1"),
                    Color.parseColor("#0B1020")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, orbPaint)

            // Crisp rim highlight
            ringPaint.strokeWidth = 1.6f * d
            ringPaint.alpha = if (stateName == "IDLE") 140 else 230
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }
}
