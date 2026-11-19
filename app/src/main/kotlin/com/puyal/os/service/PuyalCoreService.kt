package com.puyal.os.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.puyal.os.MainActivity
import kotlin.math.abs

class PuyalCoreService : Service() {
    private var bubbleViewRef: View? = null
    private var menuViewRef: LinearLayout? = null
    private var wm: WindowManager? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private val mainH = Handler(Looper.getMainLooper())
    private var screenReceiver: BroadcastReceiver? = null

    private val fgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            mainH.post {
                val isFg = i?.action == "com.puyal.os.ACTION_YUNA_FG"
                bubbleViewRef?.visibility = if (isFg) View.GONE else View.VISIBLE
                if (isFg) menuViewRef?.visibility = View.GONE
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafe()
        registerScreenReceiver()
        try {
            val f = IntentFilter().apply {
                addAction("com.puyal.os.ACTION_YUNA_FG")
                addAction("com.puyal.os.ACTION_YUNA_BG")
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(fgReceiver, f, RECEIVER_NOT_EXPORTED)
            else registerReceiver(fgReceiver, f)
        } catch (_: Exception) {}
        createSideOrbIfAllowed()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubbleViewRef == null) createSideOrbIfAllowed()
        return START_STICKY
    }

    private fun startForegroundSafe() {
        try {
            val chId = "yuna_core_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(NotificationChannel(chId, "YUNA Assistant", NotificationManager.IMPORTANCE_LOW))
                val notif = Notification.Builder(this, chId)
                    .setContentTitle("YUNA Active")
                    .setContentText("Side Orb & Voice Ready")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()
                startForeground(777, notif)
            }
        } catch (_: Exception) {}
    }

    private fun registerScreenReceiver() {
        try {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {}
            }
            val sf = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, sf, RECEIVER_NOT_EXPORTED)
            else registerReceiver(screenReceiver, sf)
        } catch (_: Exception) {}
    }

    private fun createSideOrbIfAllowed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        if (bubbleViewRef != null) return
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            val sizePx = (52 * density).toInt()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                sizePx, sizePx, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
            }
            orbParams = params

            val orb = TextView(this).apply {
                text = "Y"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#8B5CF6"))
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke((2 * density).toInt(), Color.parseColor("#BAE6FD"))
                }
                elevation = 12f
            }

            // XRecorder-style Radial Quick Menu
            val menuParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = params.x + sizePx + 12
                y = params.y
            }

            val menu = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setPadding(12, 8, 12, 8)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E60B1426"))
                    cornerRadius = 48f
                    setStroke(2, Color.parseColor("#00E5FF"))
                }
                fun addBtn(label: String, onTap: () -> Unit) {
                    val b = TextView(this@PuyalCoreService).apply {
                        text = label
                        textSize = 12f
                        setTextColor(Color.parseColor("#E0F2FE"))
                        setPadding(18, 10, 18, 10)
                        setOnClickListener {
                            visibility = View.GONE
                            onTap()
                        }
                    }
                    addView(b)
                }
                addBtn("Mic") { launchMain("com.puyal.os.ACTION_QUICK_VOICE") }
                addBtn("Open") { launchMain(Intent.ACTION_MAIN) }
                addBtn("Back") { PuyalAccessService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
                addBtn("✕") { bubbleViewRef?.visibility = View.GONE }
            }

            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            orb.setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; moved = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) moved = true
                        params.x = startX + dx; params.y = startY + dy
                        wm?.updateViewLayout(orb, params)
                        menuParams.x = params.x + sizePx + 12; menuParams.y = params.y
                        wm?.updateViewLayout(menu, menuParams)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            menu.visibility = if (menu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }
                        true
                    }
                    else -> false
                }
            }

            bubbleViewRef = orb
            menuViewRef = menu
            wm?.addView(orb, params)
            wm?.addView(menu, menuParams)
        } catch (_: Exception) {}
    }

    private fun launchMain(act: String) {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                action = act
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(fgReceiver) } catch (_: Exception) {}
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        try { bubbleViewRef?.let { wm?.removeView(it) } } catch (_: Exception) {}
        try { menuViewRef?.let { wm?.removeView(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
