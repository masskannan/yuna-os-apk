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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.puyal.os.MainActivity
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class PuyalCoreService : Service() {

    companion object {
        const val CHANNEL_ID = "puyal_sovereign_core"
        const val NOTIF_ID = 7001
        const val ACTION_TOGGLE_VOICE = "com.puyal.os.ACTION_TOGGLE_VOICE"
        const val ACTION_STOP_SERVICE = "com.puyal.os.ACTION_STOP_SERVICE"
        @Volatile
        var isRunning: Boolean = false
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: PuyalVortexView? = null
    private var menuView: LinearLayout? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private var isScreenOn = true
    private var isVoiceActive = false
    private var isTorchOn = false
    private var isManualStop = false
    private var pulsePhase = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isScreenOn || !isRunning || bubbleView == null) {
                return
            }
            if (isVoiceActive) {
                pulsePhase = (pulsePhase + 0.08f) % (Math.PI.toFloat() * 2f)
                val scale = 1.0f + 0.08f * kotlin.math.sin(pulsePhase)
                bubbleView?.scaleX = scale
                bubbleView?.scaleY = scale
                bubbleView?.advanceVortex(2.4f)
                choreographer.postFrameCallback(this)
            } else {
                bubbleView?.scaleX = 1.0f
                bubbleView?.scaleY = 1.0f
                bubbleView?.resetVortex()
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
                "Puyal Sovereign Core",
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
            .setContentTitle("Puyal Sovereign OS")
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
        Thread {
            val pid = Process.myPid()
            try {
                val cmd = "echo -1000 > /proc/$pid/oom_score_adj"
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
            .setContentTitle("Puyal Sovereign OS")
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
        val density = resources.displayMetrics.density
        val sizePx = (52 * density).toInt()

        val bubble = PuyalVortexView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#06090D"))
                setStroke((2 * density).toInt(), Color.parseColor("#E2E8F0"))
            }
            elevation = 8 * density
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (260 * density).toInt()
        }

        attachBubbleTouchListener(bubble)
        bubbleView = bubble
        windowManager.addView(bubble, bubbleParams)
    }

    private fun attachBubbleTouchListener(bubble: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var longPressTriggered = false
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
                        toggleVoiceMode()
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

    private fun toggleVoiceMode() {
        isVoiceActive = !isVoiceActive
        val density = resources.displayMetrics.density
        bubbleView?.setVortexColor(Color.parseColor(if (isVoiceActive) "#00E5FF" else "#00FF88"))
        bubbleView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (isVoiceActive) "#065F46" else "#0E243D"))
            setStroke(
                (2.5f * density).toInt(),
                Color.parseColor(if (isVoiceActive) "#34D399" else "#10B981")
            )
        }
        if (isVoiceActive && isScreenOn) {
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
            Toast.makeText(this, "Puyal Listening (Level 3 STT Ready Hook)", Toast.LENGTH_SHORT).show()
        } else {
            choreographer.removeFrameCallback(frameCallback)
            bubbleView?.scaleX = 1.0f
            bubbleView?.scaleY = 1.0f
            bubbleView?.resetVortex()
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
            "🔦 Toggle Torch" to { toggleTorch() },
            "⚙️ Open Puyal Dashboard" to {
                val i = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            },
            "🛑 Stop Puyal Service" to {
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
            visibility = View.GONE
        }
        hideTouchMenu()
    }

    private fun resumeUiFromFreeze() {
        isScreenOn = true
        bubbleView?.visibility = View.VISIBLE
        if (isVoiceActive) {
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun removeOverlayViews() {
        hideTouchMenu()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
            bubbleView = null
        }
    }

    private class PuyalVortexView(context: Context) : View(context) {
        private var outerColor = Color.parseColor("#E2E8F0")
        private var innerColor = Color.parseColor("#FFFFFF")
        private var driftAngle = 0f
        private var flowPhase = 0f

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val windPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val eyeRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val eyeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#06090D")
        }
        private val spiralPath = Path()

        fun setVortexColor(color: Int) {
            outerColor = color
            innerColor = Color.parseColor("#FFFFFF")
            invalidate()
        }

        fun advanceVortex(deltaDeg: Float) {
            driftAngle = (driftAngle - deltaDeg * 0.35f) % 360f
            flowPhase = (flowPhase - deltaDeg * 1.4f)
            invalidate()
        }

        fun resetVortex() {
            driftAngle = 0f
            flowPhase = 0f
            invalidate()
        }

        private fun buildLogSpiral(cx: Float, cy: Float, rStart: Float, rEnd: Float, startDeg: Float, sweepDeg: Float) {
            spiralPath.reset()
            val steps = 22
            val ratio = (rEnd / rStart).toDouble()
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val r = (rStart * Math.pow(ratio, t)).toFloat()
                val rad = Math.toRadians((startDeg - sweepDeg * t))
                val x = cx + (r * Math.cos(rad)).toFloat()
                val y = cy + (r * Math.sin(rad)).toFloat()
                if (i == 0) spiralPath.moveTo(x, y) else spiralPath.lineTo(x, y)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val minDim = minOf(w, h)
            val density = resources.displayMetrics.density

            glowPaint.shader = RadialGradient(
                cx, cy, minDim * 0.45f,
                intArrayOf(Color.argb(85, 255, 255, 255), Color.argb(30, 203, 213, 225), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, minDim * 0.44f, glowPaint)

            canvas.save()
            canvas.rotate(driftAngle, cx, cy)

            // Soft cloud base bands (2 asymmetric main arms)
            mistPaint.color = outerColor
            mistPaint.alpha = 70
            mistPaint.strokeWidth = 3.2f * density
            for (startAngle in floatArrayOf(15f, 195f)) {
                buildLogSpiral(cx, cy, minDim * 0.42f, minDim * 0.10f, startAngle, 205f)
                canvas.drawPath(spiralPath, mistPaint)
            }

            // Inward-flowing wind streaks along the spiral into the eye
            windPaint.color = innerColor
            windPaint.alpha = 230
            windPaint.strokeWidth = 1.7f * density
            windPaint.pathEffect = DashPathEffect(floatArrayOf(14f * density, 8f * density, 6f * density, 10f * density), flowPhase * density)
            for (startAngle in floatArrayOf(15f, 195f)) {
                buildLogSpiral(cx, cy, minDim * 0.42f, minDim * 0.10f, startAngle, 205f)
                canvas.drawPath(spiralPath, windPaint)
            }

            // Outer feeder wisps
            windPaint.alpha = 165
            windPaint.strokeWidth = 1.2f * density
            windPaint.pathEffect = DashPathEffect(floatArrayOf(9f * density, 11f * density), flowPhase * 1.3f * density)
            for (startAngle in floatArrayOf(95f, 285f)) {
                buildLogSpiral(cx, cy, minDim * 0.40f, minDim * 0.16f, startAngle, 130f)
                canvas.drawPath(spiralPath, windPaint)
            }
            windPaint.pathEffect = null
            canvas.restore()

            eyeRimPaint.color = innerColor
            eyeRimPaint.strokeWidth = 1.2f * density
            canvas.drawCircle(cx, cy, minDim * 0.068f, eyeRimPaint)
            canvas.drawCircle(cx, cy, minDim * 0.042f, eyeCorePaint)
        }
    }
}
