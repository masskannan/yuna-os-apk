package com.puyal.os

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
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
    private var oomScoreValue = "-1000"
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

        buildYunaAuroraUi()
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

    private fun buildYunaAuroraUi() {
        val d = resources.displayMetrics.density
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05070C"))
            addView(AuroraBackdropView(this@MainActivity), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Top Bar: [☰]   YUNA [20MB • LOCAL]   [⚙️]
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (56 * d).toInt())
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
        }

        val menuBtn = TextView(this).apply {
            text = "☰"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            background = glassPill("#1A132036", "#3838BDF8", 12 * d)
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
            letterSpacing = 0.18f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, (10 * d).toInt(), 0)
        })
        centerWrap.addView(TextView(this).apply {
            text = "20MB • LOCAL"
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#BAE6FD"))
            setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            background = glassPill("#1A0D1829", "#4438BDF8", 99 * d)
        })

        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            textSize = 17f
            gravity = Gravity.CENTER
            background = glassPill("#1A132036", "#3838BDF8", 12 * d)
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
            setOnClickListener { openSettingsModal() }
        }

        topBar.addView(menuBtn)
        topBar.addView(centerWrap)
        topBar.addView(settingsBtn)
        mainCol.addView(topBar)

        // 2. Center Scroll Stage (0-FPS Liquid-Glass Orb + Wave Hero Card + 3-Pill Rail)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
        }
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (4 * d).toInt(), (18 * d).toInt(), (10 * d).toInt())
        }

        heroStage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
        }

        yunaOrbView = YunaOrbView(this).apply {
            layoutParams = LinearLayout.LayoutParams((182 * d).toInt(), (182 * d).toInt())
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
            textSize = 25f
            gravity = Gravity.CENTER
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, (2 * d).toInt(), 0, (2 * d).toInt())
        })

        orbStatusLabel = TextView(this).apply {
            text = "0-FPS IDLE • TAP ORB FOR MIC"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, (14 * d).toInt())
        }
        heroStage.addView(orbStatusLabel)

        // Frosted Wave Hero Card (matching the showcase wave-accent glass card)
        val visionFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (88 * d).toInt())
            setOnClickListener { submitPrompt("Screen Vision & Scan") }
        }
        visionFrame.addView(WaveGlassCardView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val visionContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), (14 * d).toInt(), (20 * d).toInt(), (14 * d).toInt())
        }
        visionContent.addView(TextView(this).apply {
            text = "Screen Vision & Scan"
            textSize = 16f
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
        })
        visionContent.addView(TextView(this).apply {
            text = "⛶  DOM + OCR local screen reader"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (5 * d).toInt(), 0, 0)
        })
        visionFrame.addView(visionContent)
        heroStage.addView(visionFrame)

        // 3-Pill Quick Rail with subtle glowing indicator bar underneath
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * d).toInt()
            }
        }
        rail.addView(makeRailPill("WA Intel", "#38BDF8", 0) { submitPrompt("WhatsApp VIP Intel check") })
        rail.addView(makeRailPill("Root Shield", "#00FF88", (8 * d).toInt()) { openSettingsModal() })
        rail.addView(makeRailPill("Torch", "#818CF8", (8 * d).toInt()) { toggleTorchLocal() })
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

        // 4. Thumb-Arc Command Dock (＋ | Input | Waveform | Vortex Orb — cleanly above 3-Button Nav Bar)
        val inputPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (58 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = glassPill("#CC0E1525", "#3838BDF8", 29 * d)
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
            background = glassPill("#18FFFFFF", "#26FFFFFF", 99 * d)
            layoutParams = LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt())
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
            setPadding((10 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
        }

        val waveBtn = WaveformIconView(this).apply {
            layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
                rightMargin = (6 * d).toInt()
            }
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
                intArrayOf(Color.parseColor("#38BDF8"), Color.parseColor("#6366F1"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke((1.5f * d).toInt(), Color.parseColor("#7DD3FC"))
            }
            layoutParams = LinearLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt())
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
        inputPill.addView(waveBtn)
        inputPill.addView(orbSendBtn)
        mainCol.addView(inputPill)

        rootFrame.addView(mainCol)

        // 5. Left Slide Drawer
        drawerOverlay = buildLeftDrawer(d)
        drawerOverlay.visibility = View.GONE
        rootFrame.addView(drawerOverlay)

        // 6. Settings Modal
        settingsModal = buildSettingsModal(d)
        settingsModal.visibility = View.GONE
        rootFrame.addView(settingsModal)

        setContentView(rootFrame)
    }

    private fun buildLeftDrawer(d: Float): FrameLayout {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(185, 0, 0, 0))
            setOnClickListener { visibility = View.GONE }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080C16"))
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
        panel.addView(makeStatusRow("▲", "#F59E0B", "NEEDS ACTION", "Level 1 Smoke Test"))
        panel.addView(makeStatusRow("◌", "#38BDF8", "PROCESSING", "0-FPS Idle Guard"))

        scrim.addView(panel)
        return scrim
    }

    private fun buildSettingsModal(d: Float): FrameLayout {
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(195, 0, 0, 0))
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * d).toInt(), (16 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
            background = glassPill("#0B101D", "#2E4360", 20 * d)
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

    private fun makeRailPill(label: String, dotHex: String, leftMarginPx: Int, onClick: () -> Unit): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), (11 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
            background = glassPill("#CC0E1525", "#2E4360", 99 * d)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = leftMarginPx
            }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E2E8F0"))
            })
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((14 * d).toInt(), (2.5f * d).toInt()).apply {
                    topMargin = (5 * d).toInt()
                }
                background = GradientDrawable().apply {
                    cornerRadius = 99 * d
                    setColor(Color.parseColor(dotHex))
                }
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
        appendMessage("yuna", "Queued locally: \"$q\" (Rust/Wasm Brain connects in Level 2 & 4).")
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
        yunaOrbView.setActive(stateName != "IDLE", stateName, oomScoreValue)
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
        yunaOrbView.setActive(st != "IDLE", st, oomScoreValue)
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

    // 1. Multi-layer Liquid Teal-Indigo Aurora Backdrop (0 KB assets, 0-FPS static)
    private class AuroraBackdropView(context: Context) : View(context) {
        private val p1 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val p2 = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(Color.parseColor("#05070C"))
            p1.shader = RadialGradient(
                w * 0.85f, h * 0.14f, w * 0.85f,
                intArrayOf(Color.argb(85, 14, 165, 233), Color.argb(30, 45, 212, 191), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, p1)
            p2.shader = RadialGradient(
                w * 0.15f, h * 0.46f, w * 0.90f,
                intArrayOf(Color.argb(72, 99, 102, 241), Color.argb(22, 14, 165, 233), Color.TRANSPARENT),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, p2)
        }
    }

    // 2. 0-FPS Static Liquid-Glass Orb + Orbital Arcs with Curved Telemetry Labels
    private class YunaOrbView(context: Context) : View(context) {
        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val arcTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            color = Color.argb(165, 203, 213, 225)
        }
        private val arcPath = Path()
        private val arcRect = RectF()
        private var stateName = "IDLE"
        private var oomLabel = "-1000"

        fun setActive(active: Boolean, mode: String, oom: String) {
            stateName = if (active) mode else "IDLE"
            oomLabel = oom
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.27f
            val d = resources.displayMetrics.density

            val coreColor = when (stateName) {
                "LISTENING" -> Color.parseColor("#00E5FF")
                "ACTION_BURST" -> Color.parseColor("#00FF88")
                "PROCESSING" -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#38BDF8")
            }

            // Soft atmospheric halo
            glowPaint.shader = RadialGradient(
                cx, cy, r * 1.65f,
                intArrayOf(Color.argb(if (stateName == "IDLE") 70 else 135, Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor)), Color.TRANSPARENT),
                floatArrayOf(0.3f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r * 1.65f, glowPaint)

            // Concentric orbital telemetry rings
            ringPaint.strokeWidth = 1.0f * d
            ringPaint.color = coreColor
            ringPaint.alpha = if (stateName == "IDLE") 75 else 175
            val r1 = r * 1.36f
            val r2 = r * 1.66f
            canvas.drawCircle(cx, cy, r1, ringPaint)
            ringPaint.alpha = if (stateName == "IDLE") 45 else 115
            canvas.drawCircle(cx, cy, r2, ringPaint)

            // Curved orbital telemetry text ("OOM -1000" & "0-FPS")
            arcTextPaint.textSize = 8.5f * d
            arcPath.reset()
            arcRect.set(cx - r2, cy - r2, cx + r2, cy + r2)
            arcPath.addArc(arcRect, -140f, 80f)
            canvas.drawTextOnPath("OOM $oomLabel", arcPath, 0f, -3f * d, arcTextPaint)

            arcPath.reset()
            arcRect.set(cx - r1, cy - r1, cx + r1, cy + r1)
            arcPath.addArc(arcRect, -55f, 75f)
            canvas.drawTextOnPath("0-FPS", arcPath, 0f, -3f * d, arcTextPaint)

            // Multi-stop Liquid-Glass Sphere
            orbPaint.shader = RadialGradient(
                cx - r * 0.28f, cy - r * 0.28f, r * 1.25f,
                intArrayOf(
                    Color.parseColor("#BAE6FD"),
                    coreColor,
                    Color.parseColor("#6366F1"),
                    Color.parseColor("#080D1C")
                ),
                floatArrayOf(0f, 0.35f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, orbPaint)

            ringPaint.strokeWidth = 1.5f * d
            ringPaint.color = Color.parseColor("#BAE6FD")
            ringPaint.alpha = if (stateName == "IDLE") 130 else 235
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }

    // 3. Frosted Wave Hero Card (matching the cyan wave accent card)
    private class WaveGlassCardView(context: Context) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val clipPath = Path()
        private val wavePath = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            val rad = 20f * d

            rect.set(0f, 0f, w, h)
            clipPath.reset()
            clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)

            canvas.save()
            canvas.clipPath(clipPath)

            bgPaint.shader = LinearGradient(
                0f, 0f, w, h,
                Color.parseColor("#112034"), Color.parseColor("#14192E"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(rect, bgPaint)

            // Top-right & bottom-left liquid cyan wave accent
            wavePaint.color = Color.argb(65, 56, 189, 248)
            wavePath.reset()
            wavePath.moveTo(w * 0.58f, 0f)
            wavePath.cubicTo(w * 0.72f, h * 0.32f, w * 0.88f, h * 0.12f, w, h * 0.45f)
            wavePath.lineTo(w, 0f)
            wavePath.close()
            canvas.drawPath(wavePath, wavePaint)

            wavePaint.color = Color.argb(45, 99, 102, 241)
            wavePath.reset()
            wavePath.moveTo(0f, h * 0.55f)
            wavePath.cubicTo(w * 0.18f, h * 0.85f, w * 0.32f, h * 0.72f, w * 0.45f, h)
            wavePath.lineTo(0f, h)
            wavePath.close()
            canvas.drawPath(wavePath, wavePaint)

            canvas.restore()

            strokePaint.strokeWidth = 1.1f * d
            strokePaint.color = Color.argb(90, 125, 211, 252)
            canvas.drawRoundRect(rect, rad, rad, strokePaint)
        }
    }

    // 4. 5-Bar Cyan Voice Waveform Icon (for the Thumb-Arc Dock)
    private class WaveformIconView(context: Context) : View(context) {
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#38BDF8")
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            barPaint.strokeWidth = 2.4f * d
            val heights = floatArrayOf(0.24f, 0.48f, 0.72f, 0.44f, 0.26f)
            val startX = w * 0.2f
            val stepX = (w * 0.6f) / 4f
            for (i in heights.indices) {
                val x = startX + i * stepX
                val bh = h * heights[i] / 2f
                canvas.drawLine(x, h / 2f - bh, x, h / 2f + bh, barPaint)
            }
        }
    }
}
