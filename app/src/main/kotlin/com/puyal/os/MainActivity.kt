package com.puyal.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.puyal.os.service.PuyalCoreService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var rootStatusText: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshDashboardStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboardUi()
        checkRootAccessAsync()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardStatus()
    }

    private fun buildDashboardUi() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "🌪️ Puyal Sovereign OS"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (6 * density).toInt())
        }

        val subtitle = TextView(this).apply {
            text = "Level 1: Immortal Core & 0-FPS Floating UI"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, (18 * density).toInt())
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.parseColor("#132238"))
            }
        }

        rootStatusText = TextView(this).apply {
            text = "Root Check: Checking su binary..."
            textSize = 13f
            setTextColor(Color.parseColor("#FBBF24"))
            setPadding(0, (12 * density).toInt(), 0, (16 * density).toInt())
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(statusText)
        root.addView(rootStatusText)

        root.addView(makeActionButton("1. Grant Overlay Permission", "#0F4C81") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        root.addView(makeActionButton("2. Grant Mic & Notification Perms", "#0F4C81") {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            permLauncher.launch(perms.toTypedArray())
        })

        root.addView(makeActionButton("3. Disable Battery Optimization", "#0F4C81") {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        })

        root.addView(makeActionButton("🚀 START PUYAL IMMORTAL SERVICE", "#0D7A5F") {
            val svcIntent = Intent(this, PuyalCoreService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
            statusText.postDelayed({ refreshDashboardStatus() }, 300)
        })

        root.addView(makeActionButton("🛑 STOP SERVICE", "#991B1B") {
            stopService(Intent(this, PuyalCoreService::class.java))
            statusText.postDelayed({ refreshDashboardStatus() }, 300)
        })

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun makeActionButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (10 * density).toInt()
        }
        return Button(this).apply {
            layoutParams = lp
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor(hexColor))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun refreshDashboardStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val svcOk = PuyalCoreService.isRunning

        statusText.text = buildString {
            appendLine("• Overlay Permission : ${if (overlayOk) "✅ READY" else "❌ MISSING"}")
            appendLine("• Microphone Access  : ${if (micOk) "✅ READY" else "❌ MISSING"}")
            appendLine("• Battery Exemption  : ${if (batteryOk) "✅ UNRESTRICTED" else "⚠️ RESTRICTED"}")
            append("• Puyal Core Service : ${if (svcOk) "🟢 IMMORTAL RUNNING" else "⚪ STOPPED"}")
        }
    }

    private fun checkRootAccessAsync() {
        Thread {
            val hasRoot = try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                p.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                rootStatusText.text = if (hasRoot) {
                    "Root Access: 🟢 GRANTED (OOM -1000 Protection Active)"
                } else {
                    "Root Access: ⚠️ NOT FOUND (Foreground + Battery Exemption Mode)"
                }
            }
        }.start()
    }
}
