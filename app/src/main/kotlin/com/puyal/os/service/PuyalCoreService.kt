package com.puyal.os.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.puyal.os.MainActivity
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PuyalCoreService : Service() {
    private var bubbleViewRef: android.view.View? = null
    
    private fun launchQuickVoiceOverlay() {
        try {
            val i = android.content.Intent(this, com.puyal.os.MainActivity::class.java).apply {
                action = "com.puyal.os.ACTION_QUICK_VOICE"
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(i)
        } catch (_: Exception) {}
    }

    private val fgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                bubbleViewRef?.visibility = if (i?.action == "com.puyal.os.ACTION_YUNA_FG") android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "puyal_sovereign_core"
        const val NOTIF_ID = 7001
        const val ACTION_TOGGLE_VOICE = "com.puyal.os.ACTION_TOGGLE_VOICE"
        const val ACTION_STOP_SERVICE = "com.puyal.os.ACTION_STOP_SERVICE"
        const val ACTION_SET_STATE = "com.puyal.os.ACTION_SET_STATE"
        const val EXTRA_STATE = "extra_state"
        @Volatile
        var currentStateName: String = "IDLE"
        @Volatile
        var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private var bubbleContainer: FrameLayout? = null
    private var ringView: View? = null
    private var bubbleView: TextView? = null
    private var menuView: LinearLayout? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var bubbleAdded = false
    private lateinit var menuParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private enum class PuyalState { IDLE, LISTENING, ACTION_BURST, PROCESSING }

    private var isScreenOn = true
    private var currentState = PuyalState.IDLE
    private var isTorchOn = false
    private var isManualStop = false
    private var pulsePhase = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isScreenOn || !isRunning || bubbleView == null || bubbleContainer == null) {
                return
            }
            val density = resources.displayMetrics.density
            when (currentState) {
                PuyalState.LISTENING -> {
                    // 3D vertical Y-axis helical spin + ±6° precession sway + voice pulse
                    pulsePhase = (pulsePhase + 0.11f) % (Math.PI.toFloat() * 2f)
                    val s = kotlin.math.sin(pulsePhase)
                    val scale = 1.0f + 0.07f * s
                    bubbleView?.scaleX = scale
                    bubbleView?.scaleY = scale
                    bubbleView?.alpha = 1.0f
                    bubbleView?.rotationY = ((bubbleView?.rotationY ?: 0f) + 6.5f) % 360f
                    bubbleView?.rotation = 6f * s
                    bubbleView?.translationY = -2f * density * kotlin.math.abs(s)
                    val ringT = (pulsePhase / (Math.PI.toFloat() * 2f))
                    ringView?.apply {
                        visibility = View.VISIBLE
                        scaleX = 0.85f + 0.35f * ringT
                        scaleY = 0.85f + 0.35f * ringT
                        alpha = (1.0f - ringT).coerceIn(0f, 1f)
                    }
                    choreographer.postFrameCallback(this)
                }
                PuyalState.ACTION_BURST -> {
                    // High-speed helical burst + ±8° sway + updraft lift
                    pulsePhase = (pulsePhase + 0.20f) % (Math.PI.toFloat() * 2f)
                    val s = kotlin.math.sin(pulsePhase)
                    val kick = 1.0f + 0.11f * kotlin.math.abs(s)
                    bubbleView?.scaleX = kick
                    bubbleView?.scaleY = kick
                    bubbleView?.alpha = 1.0f
                    bubbleView?.rotationY = ((bubbleView?.rotationY ?: 0f) + 13f) % 360f
                    bubbleView?.rotation = 8f * s
                    bubbleView?.translationY = -3f * density * kotlin.math.abs(s)
                    val ringT = (pulsePhase / (Math.PI.toFloat() * 2f))
                    ringView?.apply {
                        visibility = View.VISIBLE
                        scaleX = 0.88f + 0.38f * ringT
                        scaleY = 0.88f + 0.38f * ringT
                        alpha = (1.0f - ringT).coerceIn(0f, 1f)
                    }
                    choreographer.postFrameCallback(this)
                }
                PuyalState.PROCESSING -> {
                    // Slow precession drift + gentle ±3° wobble + calm alpha
                    pulsePhase = (pulsePhase + 0.05f) % (Math.PI.toFloat() * 2f)
                    val s = kotlin.math.sin(pulsePhase)
                    bubbleView?.scaleX = 1.0f
                    bubbleView?.scaleY = 1.0f
                    bubbleView?.alpha = 0.82f + 0.18f * s
                    bubbleView?.rotationY = ((bubbleView?.rotationY ?: 0f) + 2.0f) % 360f
                    bubbleView?.rotation = 3f * s
                    bubbleView?.translationY = 0f
                    ringView?.apply {
                        visibility = View.VISIBLE
                        val softScale = 1.0f + 0.12f * kotlin.math.abs(s)
                        scaleX = softScale
                        scaleY = softScale
                        alpha = 0.35f + 0.35f * kotlin.math.abs(s)
                    }
                    choreographer.postFrameCallback(this)
                }
                PuyalState.IDLE -> {
                    bubbleView?.scaleX = 1.0f
                    bubbleView?.scaleY = 1.0f
                    bubbleView?.alpha = 1.0f
                    bubbleView?.rotationY = 0f
                    bubbleView?.rotation = 0f
                    bubbleView?.translationY = 0f
                    ringView?.visibility = View.GONE
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> freezeUiToZeroFps()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> resumeUiFromFreeze()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isManualStop = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        isScreenOn = pm.isInteractive

        createNotificationChannel()
        startImmortalForeground()
        applyRootOomProtection()
        registerScreenReceiver()
        val f = android.content.IntentFilter().apply { addAction("com.puyal.os.ACTION_YUNA_FG"); addAction("com.puyal.os.ACTION_YUNA_BG") }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(fgReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(fgReceiver, f)

        if (Settings.canDrawOverlays(this)) {
            setupFloatingBubble()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubbleView == null && Settings.canDrawOverlays(this)) {
            setupFloatingBubble()
        }
        when (intent?.action) {
            ACTION_TOGGLE_VOICE -> toggleVoiceMode()
            ACTION_SET_STATE -> {
                val st = intent.getStringExtra(EXTRA_STATE) ?: "IDLE"
                when (st) {
                    "LISTENING" -> setPuyalState(PuyalState.LISTENING, "YUNA Listening (Mic ON)")
                    "ACTION_BURST" -> triggerActionBurst()
                    "PROCESSING" -> setPuyalState(PuyalState.PROCESSING, "Background Process Running...")
                    else -> setPuyalState(PuyalState.IDLE, "YUNA Idle (0-FPS Standby)")
                }
            }
            ACTION_STOP_SERVICE -> {
                isManualStop = true
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isManualStop) {
            val restartIntent = Intent(applicationContext, PuyalCoreService::class.java)
            val pi = PendingIntent.getService(
                applicationContext,
                7002,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000L, pi)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(fgReceiver) } catch (_: Exception) {}
        isRunning = false
        choreographer.removeFrameCallback(frameCallback)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        removeOverlayViews()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "YUNA Sovereign Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent offline AI service with 0% idle battery freeze"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startImmortalForeground() {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YUNA OS")
            .setContentText("Active • 0-FPS Idle Guard Enabled")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun applyRootOomProtection() {
        if (!java.io.File("/system/bin/su").exists() && !java.io.File("/system/xbin/su").exists()) return
        Thread {
            val pid = Process.myPid()
            try {
                val cmd = "echo -850 > /proc/$pid/oom_score_adj"
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (finished && proc.exitValue() == 0) {
                    mainHandler.post {
                        updateNotification("Active • Root OOM -1000 Locked")
                    }
                } else if (!finished) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YUNA OS")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
        nm.notify(NOTIF_ID, notification)
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun setupFloatingBubble() {
        if (bubbleAdded) return
        bubbleAdded = true
        val density = resources.displayMetrics.density
        val sizePx = (52 * density).toInt()

        val containerSizePx = (74 * density).toInt()
        val ringSizePx = (64 * density).toInt()

        val ring = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke((2.2f * density).toInt(), Color.parseColor("#00E5FF"))
            }
        }

        val bubble = TextView(this).apply {
            text = "🌪️"
            textSize = 24f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            cameraDistance = 8000f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#04070B"))
                setStroke((2.2f * density).toInt(), Color.parseColor("#94A3B8"))
            }
            elevation = 8 * density
        }

        val container = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(ring, FrameLayout.LayoutParams(ringSizePx, ringSizePx, Gravity.CENTER))
            addView(bubble, FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER))
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            containerSizePx,
            containerSizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (10 * density).toInt()
            y = (250 * density).toInt()
        }

        attachBubbleTouchListener(container)
        ringView = ring
        bubbleView = bubble
        bubbleContainer = container
        bubbleViewRef = container; windowManager.addView(container, bubbleParams)
    }

    private fun attachBubbleTouchListener(bubble: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false
        var lastTapTime = 0L
        var singleTapRunnable: Runnable? = null
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.coerceAtLeast(24)

        val longPressRunnable = Runnable {
            if (!isDragging) {
                longPressTriggered = true
                bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showTouchMenu()
            }
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 450L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (isDragging || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        hideTouchMenu()
                        val dm = resources.displayMetrics
                        val bSize = bubble.width.takeIf { it > 0 } ?: (52 * dm.density).toInt()
                        bubbleParams.x = (initialX + dx).coerceIn(0, (dm.widthPixels - bSize).coerceAtLeast(0))
                        bubbleParams.y = (initialY + dy).coerceIn(0, (dm.heightPixels - bSize).coerceAtLeast(0))
                        windowManager.updateViewLayout(bubble, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !longPressTriggered) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapTime < 260L) {
                            singleTapRunnable?.let { mainHandler.removeCallbacks(it) }
                            singleTapRunnable = null
                            lastTapTime = 0L
                            triggerActionBurst()
                        } else {
                            lastTapTime = now
                            val r = Runnable {
                                singleTapRunnable = null
                                toggleVoiceMode()
                            }
                            singleTapRunnable = r
                            mainHandler.postDelayed(r, 260L)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun setPuyalState(state: PuyalState, toastMsg: String? = null) {
        currentState = state
        currentStateName = state.name
        val density = resources.displayMetrics.density
        val strokeColor = when (state) {
            PuyalState.LISTENING -> "#00E5FF"
            PuyalState.ACTION_BURST -> "#00FF88"
            PuyalState.PROCESSING -> "#F59E0B"
            PuyalState.IDLE -> "#94A3B8"
        }
        bubbleView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#04070B"))
            setStroke((2.2f * density).toInt(), Color.parseColor(strokeColor))
        }
        ringView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((2.2f * density).toInt(), Color.parseColor(strokeColor))
        }
        if (state == PuyalState.IDLE) ringView?.visibility = View.GONE
        choreographer.removeFrameCallback(frameCallback)
        if (state != PuyalState.IDLE && isScreenOn) {
            choreographer.postFrameCallback(frameCallback)
        } else {
            bubbleView?.scaleX = 1.0f
            bubbleView?.scaleY = 1.0f
            bubbleView?.alpha = 1.0f
            bubbleView?.rotationY = 0f
            bubbleView?.rotation = 0f
            bubbleView?.translationY = 0f
            ringView?.visibility = View.GONE
        }
        toastMsg?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun toggleVoiceMode() {
        if (currentState == PuyalState.LISTENING) {
            setPuyalState(PuyalState.IDLE, "YUNA Idle (0-FPS Standby)")
        } else {
            setPuyalState(PuyalState.LISTENING, "YUNA Listening (Mic ON)")
        }
    }

    private fun triggerActionBurst() {
        setPuyalState(PuyalState.ACTION_BURST, "Action Burst • Quick Scan")
        mainHandler.postDelayed({
            if (currentState == PuyalState.ACTION_BURST) {
                setPuyalState(PuyalState.IDLE)
            }
        }, 1800L)
    }

    private fun toggleBackgroundProcessMode() {
        if (currentState == PuyalState.PROCESSING) {
            setPuyalState(PuyalState.IDLE, "Background Task Done (0-FPS)")
        } else {
            setPuyalState(PuyalState.PROCESSING, "Background Process Running...")
        }
    }

    private fun showTouchMenu() {
        hideTouchMenu()
        val dm = resources.displayMetrics
        val density = dm.density
        val pad = (12 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Color.parseColor("#EE050807"))
                setStroke((1 * density).toInt(), Color.parseColor("#E2E8F0"))
            }
        }

        val items = listOf(
            "🌐 Open Hacker UI (:7777)" to { openHackerUi() },
            "🎙️ Toggle Voice Mode" to { toggleVoiceMode() },
            "⚙️ Toggle Background Task" to { toggleBackgroundProcessMode() },
            "🔦 Toggle Torch" to { toggleTorch() },
            "⚙️ Open YUNA" to {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            },
            "🛑 Stop YUNA" to {
                isManualStop = true
                stopSelf()
            },
            "✕ Close Menu" to { }
        )

        items.forEach { (label, action) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                setOnClickListener {
                    hideTouchMenu()
                    action()
                }
            }
            container.addView(btn)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val menuWidthPx = (210 * density).toInt()
        val openOnLeft = bubbleParams.x > (dm.widthPixels / 2)
        val menuX = if (openOnLeft) {
            (bubbleParams.x - menuWidthPx - (6 * density).toInt()).coerceAtLeast(8)
        } else {
            bubbleParams.x + (58 * density).toInt()
        }
        val menuY = bubbleParams.y.coerceIn(60, (dm.heightPixels - (260 * density).toInt()).coerceAtLeast(60))

        menuParams = WindowManager.LayoutParams(
            menuWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = menuX
            y = menuY
        }

        container.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) {
                hideTouchMenu()
                true
            } else {
                false
            }
        }

        menuView = container
        windowManager.addView(container, menuParams)
    }

    private fun hideTouchMenu() {
        menuView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            menuView = null
        }
    }

    private fun openHackerUi() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:7777")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Browser error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleTorch() {
        try {
            val camManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull() ?: return
            isTorchOn = !isTorchOn
            camManager.setTorchMode(cameraId, isTorchOn)
        } catch (e: Exception) {
            Toast.makeText(this, "Torch error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun freezeUiToZeroFps() {
        isScreenOn = false
        choreographer.removeFrameCallback(frameCallback)
        bubbleView?.apply {
            scaleX = 1.0f
            scaleY = 1.0f
        }
        ringView?.visibility = View.GONE
        bubbleContainer?.visibility = View.GONE
        hideTouchMenu()
    }

    private fun resumeUiFromFreeze() {
        isScreenOn = true
        bubbleContainer?.visibility = View.VISIBLE
        if (currentState != PuyalState.IDLE) {
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun removeOverlayViews() {
        hideTouchMenu()
        bubbleContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            bubbleContainer = null
            bubbleView = null
            ringView = null
        }
    }

}
