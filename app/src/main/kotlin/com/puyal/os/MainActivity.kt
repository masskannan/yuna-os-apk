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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.puyal.os.service.PuyalCoreService
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var telemetryText: TextView
    private lateinit var rootBadge: TextView
    private lateinit var serviceBadge: TextView

    private lateinit var permOverlayBadge: TextView
    private lateinit var permMicBadge: TextView
    private lateinit var permNotifBadge: TextView
    private lateinit var permBatteryBadge: TextView

    private var hasRootAccess = false
    private var oomScoreValue = "checking..."

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#050807")
        window.navigationBarColor = Color.parseColor("#050807")
        buildSovereignDashboard()
        probeRootAndOomAsync()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboard()
        probeRootAndOomAsync()
    }

    private fun buildSovereignDashboard() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#050807"))
            setPadding(pad, (18 * d).toInt(), pad, (28 * d).toInt())
        }

        // Top Sovereign Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = cardDrawable("#0A110E", "#1F00FF88", 14 * d)
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "🌪️ PUYAL_SOVEREIGN_OS"
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00FF88"))
        })
        titleCol.addView(TextView(this).apply {
            text = "LEVEL 1 • IMMORTAL SHELL • 0-FPS"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#6E9181"))
        })
        serviceBadge = TextView(this).apply {
            text = "OFFLINE"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            background = cardDrawable("#111B17", "#6E9181", 99 * d)
            setTextColor(Color.parseColor("#94A3B8"))
        }
        topBar.addView(titleCol)
        topBar.addView(serviceBadge)
        root.addView(topBar)

        // Kernel Telemetry Card
        val teleCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            background = cardDrawable("#0A110E", "#3800FF88", 14 * d)
            layoutParams = marginTopLp(12 * d)
        }
        val teleHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        teleHeader.addView(TextView(this).apply {
            text = "> KERNEL_TELEMETRY"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        rootBadge = TextView(this).apply {
            text = "SU: CHECKING"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#F59E0B"))
        }
        teleHeader.addView(rootBadge)
        teleCard.addView(teleHeader)

        telemetryText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#E2F8EE"))
            setPadding(0, (8 * d).toInt(), 0, 0)
            setLineSpacing(4f * d, 1.0f)
        }
        teleCard.addView(telemetryText)
        root.addView(teleCard)

        // Primary Service Controls
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = marginTopLp(12 * d)
        }
        ctrlRow.addView(makeChipBtn("🚀 START CORE", "#00FF88", "#04110A", 1f, 0) {
            ContextCompat.startForegroundService(this, Intent(this, PuyalCoreService::class.java))
            telemetryText.postDelayed({
                refreshDashboard()
                probeRootAndOomAsync()
            }, 350)
        })
        ctrlRow.addView(makeChipBtn("🛑 STOP", "#1A0B0E", "#F87171", 0.55f, (8 * d).toInt(), "#7F1D1D") {
            val stopIntent = Intent(this, PuyalCoreService::class.java).apply {
                action = PuyalCoreService.ACTION_STOP_SERVICE
            }
            startService(stopIntent)
            telemetryText.postDelayed({ refreshDashboard() }, 350)
        })
        root.addView(ctrlRow)

        // Bubble Animation Mode Tester (3D Tornado + Active Ring)
        root.addView(sectionLabel("BUBBLE_MODE_CONTROLLER (3D TORNADO + RING)"))
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = marginTopLp(6 * d)
        }
        modeRow.addView(makeChipBtn("IDLE", "#0A110E", "#94A3B8", 1f, 0, "#475569") {
            sendBubbleState("IDLE")
        })
        modeRow.addView(makeChipBtn("MIC", "#071A22", "#00E5FF", 1f, (6 * d).toInt(), "#00E5FF") {
            sendBubbleState("LISTENING")
        })
        modeRow.addView(makeChipBtn("EXEC", "#071E14", "#00FF88", 1f, (6 * d).toInt(), "#00FF88") {
            sendBubbleState("ACTION_BURST")
        })
        modeRow.addView(makeChipBtn("BG", "#1F1607", "#F59E0B", 1f, (6 * d).toInt(), "#F59E0B") {
            sendBubbleState("PROCESSING")
        })
        root.addView(modeRow)

        // Permission Matrix
        root.addView(sectionLabel("PERMISSION_FORTRESS (TAP TO GRANT)"))
        val permCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            background = cardDrawable("#0A110E", "#2800FF88", 14 * d)
            layoutParams = marginTopLp(6 * d)
        }
        permOverlayBadge = addPermRow(permCard, "Overlay Bubble (Draw Over Apps)") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        permMicBadge = addPermRow(permCard, "Mic & Camera (Voice/Vision Hook)") {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        }
        permNotifBadge = addPermRow(permCard, "Immortal Foreground Notification") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        permBatteryBadge = addPermRow(permCard, "Battery Unrestricted (Vivo Guard)", isLast = true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
        root.addView(permCard)

        // Localhost Console Shortcuts
        root.addView(sectionLabel("TERMUX_LOCAL_CONSOLE"))
        val linkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = marginTopLp(6 * d)
        }
        linkRow.addView(makeChipBtn("💬 OPEN :7777 UI", "#0E1915", "#00E5FF", 1f, 0, "#3800E5FF") {
            openUrl("http://127.0.0.1:7777")
        })
        linkRow.addView(makeChipBtn("📦 STORE :7778", "#0E1915", "#00FF88", 1f, (8 * d).toInt(), "#3800FF88") {
            openUrl("http://127.0.0.1:7778")
        })
        root.addView(linkRow)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#050807"))
            addView(root)
        })
    }

    private fun sectionLabel(title: String): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = "> $title"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#6E9181"))
            setPadding((2 * d).toInt(), (14 * d).toInt(), 0, 0)
        }
    }

    private fun addPermRow(parent: LinearLayout, label: String, isLast: Boolean = false, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#E2F8EE"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val badge = TextView(this).apply {
            text = "..."
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        row.addView(badge)
        parent.addView(row)
        if (!isLast) {
            parent.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#182822"))
            })
        }
        return badge
    }

    private fun makeChipBtn(
        label: String,
        bgHex: String,
        fgHex: String,
        weight: Float,
        leftMarginPx: Int,
        strokeHex: String? = null,
        onClick: () -> Unit
    ): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(fgHex))
            setPadding((8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            background = cardDrawable(bgHex, strokeHex ?: bgHex, 11 * d)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                leftMargin = leftMarginPx
            }
            setOnClickListener { onClick() }
        }
    }

    private fun cardDrawable(bgHex: String, strokeHex: String, radiusPx: Float): GradientDrawable {
        val d = resources.displayMetrics.density
        val strokeColor = if (strokeHex.length == 9 && strokeHex.startsWith("#")) {
            Color.parseColor(strokeHex)
        } else {
            Color.parseColor(strokeHex)
        }
        return GradientDrawable().apply {
            cornerRadius = radiusPx
            setColor(Color.parseColor(bgHex))
            setStroke((1.2f * d).toInt(), strokeColor)
        }
    }

    private fun marginTopLp(topPx: Float): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = topPx.toInt() }
    }

    private fun sendBubbleState(stateName: String) {
        if (!PuyalCoreService.isRunning) {
            Toast.makeText(this, "Start Core Service first!", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, PuyalCoreService::class.java).apply {
            action = PuyalCoreService.ACTION_SET_STATE
            putExtra(PuyalCoreService.EXTRA_STATE, stateName)
        })
        telemetryText.postDelayed({ refreshDashboard() }, 150)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Browser error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun styleBadge(badge: TextView, ok: Boolean, okText: String = "READY", badText: String = "GRANT") {
        val d = resources.displayMetrics.density
        if (ok) {
            badge.text = okText
            badge.setTextColor(Color.parseColor("#00FF88"))
            badge.background = cardDrawable("#071E14", "#00FF88", 99 * d)
        } else {
            badge.text = badText
            badge.setTextColor(Color.parseColor("#F59E0B"))
            badge.background = cardDrawable("#1F1607", "#F59E0B", 99 * d)
        }
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
        styleBadge(permBatteryBadge, batteryOk, "UNRESTRICTED", "FIX")

        val d = resources.displayMetrics.density
        if (svcOk) {
            serviceBadge.text = "IMMORTAL ON"
            serviceBadge.setTextColor(Color.parseColor("#00FF88"))
            serviceBadge.background = cardDrawable("#071E14", "#00FF88", 99 * d)
        } else {
            serviceBadge.text = "STOPPED"
            serviceBadge.setTextColor(Color.parseColor("#94A3B8"))
            serviceBadge.background = cardDrawable("#111B17", "#475569", 99 * d)
        }

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        telemetryText.text = buildString {
            appendLine("PID       : ${Process.myPid()} ($abi)")
            appendLine("OOM_SCORE : $oomScoreValue")
            appendLine("BUBBLE    : ${PuyalCoreService.currentStateName}")
            append("0-FPS_GRD : ${if (svcOk) "ARMED (Screen-Off Freeze)" else "STANDBY"}")
        }
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
                if (rootOk) "-1000 (Root Target)" else "0 (Non-Root)"
            }
            hasRootAccess = rootOk
            oomScoreValue = oom
            runOnUiThread {
                rootBadge.text = if (rootOk) "SU: GRANTED" else "SU: NO ROOT"
                rootBadge.setTextColor(Color.parseColor(if (rootOk) "#00FF88" else "#F59E0B"))
                refreshDashboard()
            }
        }.start()
    }
}
