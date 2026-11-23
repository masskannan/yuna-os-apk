package com.yuna.os.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.MediaStore
import android.util.Base64
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import com.yuna.os.R
import com.yuna.os.agent.YunaAgentOrchestrator

class YunaGhostOverlayService : Service() {

    companion object {
        const val ACTION_SET_STATE = "com.yuna.os.GHOST_SET_STATE"
        const val ACTION_SHOW_CAPTION = "com.yuna.os.GHOST_SHOW_CAPTION"
        const val ACTION_SHOW_RESULT = "com.yuna.os.GHOST_SHOW_RESULT"
        const val ACTION_MUTE_TOGGLE = "com.yuna.os.GHOST_MUTE_TOGGLE"
        const val ACTION_VOICE_MUTE_STATE = "com.yuna.os.VOICE_MUTE_STATE"
        const val ACTION_END_SESSION = "com.yuna.os.GHOST_END_SESSION"
        const val ACTION_REQUEST_CONFIRM = "com.yuna.os.GHOST_REQUEST_CONFIRM"
        const val ACTION_USER_CONFIRMED = "com.yuna.os.GHOST_USER_CONFIRMED"
        const val ACTION_SCREEN_SHARE_TOGGLE = "com.yuna.os.GHOST_SCREEN_TOGGLE"
        const val EXTRA_STATE = "state"
        const val EXTRA_TEXT = "text"
        const val EXTRA_IMAGE_B64 = "image_b64"
        const val EXTRA_CONFIRMED = "confirmed"
        private const val NOTIF_ID = 4202
        private const val CH_ID = "yuna_ghost_live_ch"
    }

    enum class GhostState { IDLE, LISTENING, THINKING, EXECUTING, NEEDS_CONFIRM }

    private lateinit var wm: WindowManager
    private lateinit var rootLayout: LinearLayout
    private lateinit var captionPill: TextView
    private lateinit var resultCard: LinearLayout
    private lateinit var resultImage: ImageView
    private lateinit var resultText: TextView
    private lateinit var confirmBar: LinearLayout
    private lateinit var confirmLabel: TextView
    private lateinit var expandedDock: LinearLayout
    private lateinit var ghostOrb: ImageView
    private lateinit var muteBtn: TextView
    private lateinit var screenBtn: TextView
    private lateinit var lp: WindowManager.LayoutParams

    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState = GhostState.IDLE
    private var isExpanded = true
    private var isMuted = false
    private var isScreenSharing = false
    private var lastResultBitmap: Bitmap? = null
    private var activeAnimator: ObjectAnimator? = null

    private val autoCollapseRunnable = Runnable {
        if (isExpanded && currentState != GhostState.NEEDS_CONFIRM) collapseToCorner()
    }
    private val hideCaptionRunnable = Runnable { captionPill.visibility = View.GONE }

    private val ctrlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SET_STATE -> {
                    val s = intent.getStringExtra(EXTRA_STATE) ?: "IDLE"
                    applyGhostState(runCatching { GhostState.valueOf(s) }.getOrDefault(GhostState.IDLE))
                }
                ACTION_SHOW_CAPTION -> showCaption(intent.getStringExtra(EXTRA_TEXT) ?: "")
                ACTION_SHOW_RESULT -> showResultCard(
                    intent.getStringExtra(EXTRA_TEXT) ?: "",
                    intent.getStringExtra(EXTRA_IMAGE_B64) ?: ""
                )
                ACTION_REQUEST_CONFIRM -> showConfirmPrompt(intent.getStringExtra(EXTRA_TEXT) ?: "தொடரவா?")
                ACTION_MUTE_TOGGLE -> toggleMute()
                ACTION_END_SESSION -> { YunaAgentOrchestrator.cancel(); stopSelf() }
            }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotifChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        YunaAgentOrchestrator.ensureReceiver(this)
        buildOverlayViews()
        val f = IntentFilter().apply {
            addAction(ACTION_SET_STATE)
            addAction(ACTION_SHOW_CAPTION)
            addAction(ACTION_SHOW_RESULT)
            addAction(ACTION_REQUEST_CONFIRM)
            addAction(ACTION_MUTE_TOGGLE)
            addAction(ACTION_END_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ctrlReceiver, f, RECEIVER_NOT_EXPORTED)
        else registerReceiver(ctrlReceiver, f)
        scheduleAutoCollapse()
    }

    private fun buildOverlayViews() {
        val dm = resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        // 1. Floating Result Card (Image Preview + Share / Copy / Save / Close)
        resultCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6141822"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#4438BDF8"))
            }
        }
        resultImage = ImageView(this).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        resultText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 4
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        listOf("Copy", "Share", "Save", "Close").forEach { label ->
            actionRow.addView(TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    when (label) {
                        "Copy" -> {
                            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("YUNA", resultText.text))
                            showCaption("நகலெடுக்கப்பட்டது.")
                        }
                        "Share" -> {
                            val si = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, resultText.text.toString())
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(Intent.createChooser(si, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        "Save" -> saveLastBitmapToGallery()
                    }
                    resultCard.visibility = View.GONE
                }
            })
        }
        resultCard.addView(resultImage, LinearLayout.LayoutParams(dp(220), dp(140)).apply { bottomMargin = dp(6) })
        resultCard.addView(resultText)
        resultCard.addView(actionRow)
        rootLayout.addView(resultCard)

        // 2. Safety 1-Tap Confirm Bar
        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE7F1D1D"))
                cornerRadius = dp(18).toFloat()
            }
        }
        confirmLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
        }
        val yesBtn = TextView(this).apply {
            text = "✓ Confirm"
            setTextColor(Color.parseColor("#86EFAC"))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { sendConfirmResult(true) }
        }
        val noBtn = TextView(this).apply {
            text = "✕ Stop"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 12f
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { sendConfirmResult(false) }
        }
        confirmBar.addView(confirmLabel)
        confirmBar.addView(yesBtn)
        confirmBar.addView(noBtn)
        rootLayout.addView(confirmBar)

        // 3. Floating Live Caption Pill
        captionPill = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = dp(280)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D911151E"))
                cornerRadius = dp(16).toFloat()
            }
        }
        rootLayout.addView(captionPill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // 4. Expanded 5-Button Dock + Center Ghost Orb
        expandedDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F172A"))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#3338BDF8"))
            }
        }
        val plusBtn = makeDockBtn("+", dp) {
            showCaption("என்ன செய்யணும்னு சொல்லுங்க...")
            scheduleAutoCollapse()
        }
        screenBtn = makeDockBtn("📺", dp) {
            if (!isScreenSharing && YunaAgentOrchestrator.screenVision == null) {
                YunaOverlayPermActivity.requestScreenProjection(this)
            } else {
                isScreenSharing = !isScreenSharing
                screenBtn.alpha = if (isScreenSharing) 1.0f else 0.55f
                sendBroadcast(Intent(ACTION_SCREEN_SHARE_TOGGLE).putExtra("enabled", isScreenSharing))
                showCaption(if (isScreenSharing) "Screen Share ON" else "Screen Share OFF")
            }
            scheduleAutoCollapse()
        }.apply { alpha = 0.55f }

        ghostOrb = ImageView(this).apply {
            setImageResource(R.drawable.yuna_ghost_idle)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener {
                if (currentState == GhostState.EXECUTING || currentState == GhostState.THINKING) {
                    YunaAgentOrchestrator.cancel()
                    applyGhostState(GhostState.IDLE)
                    showCaption("நிறுத்தப்பட்டது.")
                } else if (!isExpanded) {
                    expandDock()
                } else {
                    collapseToCorner()
                }
            }
        }

        muteBtn = makeDockBtn("🎙", dp) { toggleMute() }
        val endBtn = makeDockBtn("✕", dp) {
            YunaAgentOrchestrator.cancel()
            stopSelf()
        }.apply { setTextColor(Color.parseColor("#F87171")) }

        expandedDock.addView(plusBtn)
        expandedDock.addView(screenBtn)
        expandedDock.addView(ghostOrb, LinearLayout.LayoutParams(dp(44), dp(44)))
        expandedDock.addView(muteBtn)
        expandedDock.addView(endBtn)
        rootLayout.addView(expandedDock)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(14)
            y = dp(76)
        }
        attachDragTouch(ghostOrb)
        wm.addView(rootLayout, lp)
    }

    private fun saveLastBitmapToGallery() {
        val bmp = lastResultBitmap ?: return
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "YUNA_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            showCaption("கேலரியில் சேமிக்கப்பட்டது.")
        }
    }

    private fun makeDockBtn(label: String, dp: (Int) -> Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }

    private fun attachDragTouch(v: View) {
        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = lp.x; iy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (tx - e.rawX).toInt(); val dy = (ty - e.rawY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true; lp.x = ix + dx; lp.y = iy + dy
                        wm.updateViewLayout(rootLayout, lp)
                    }
                    moved
                }
                else -> moved
            }
        }
    }

    private fun collapseToCorner() {
        isExpanded = false
        for (i in 0 until expandedDock.childCount) {
            val child = expandedDock.getChildAt(i)
            if (child !== ghostOrb) child.visibility = View.GONE
        }
        expandedDock.background = null
        ghostOrb.alpha = if (currentState == GhostState.IDLE) 0.45f else 0.95f
    }

    private fun expandDock() {
        isExpanded = true
        for (i in 0 until expandedDock.childCount) expandedDock.getChildAt(i).visibility = View.VISIBLE
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        expandedDock.background = GradientDrawable().apply {
            setColor(Color.parseColor("#E60F172A"))
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), Color.parseColor("#3338BDF8"))
        }
        ghostOrb.alpha = 1.0f
        scheduleAutoCollapse()
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, 3500L)
    }

    private fun applyGhostState(state: GhostState) {
        currentState = state
        activeAnimator?.cancel()
        activeAnimator = null
        ghostOrb.scaleX = 1f; ghostOrb.scaleY = 1f; ghostOrb.rotationY = 0f

        when (state) {
            GhostState.IDLE -> {
                ghostOrb.setImageResource(R.drawable.yuna_ghost_idle)
                if (!isExpanded) ghostOrb.alpha = 0.45f
            }
            GhostState.LISTENING -> {
                ghostOrb.alpha = 1f
                ghostOrb.setImageResource(R.drawable.yuna_ghost_listen)
                activeAnimator = ObjectAnimator.ofPropertyValuesHolder(
                    ghostOrb,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f, 1f)
                ).apply { duration = 900; repeatCount = ObjectAnimator.INFINITE; start() }
            }
            GhostState.THINKING -> {
                ghostOrb.alpha = 1f
                ghostOrb.setImageResource(R.drawable.yuna_ghost_peek)
                activeAnimator = ObjectAnimator.ofFloat(ghostOrb, View.ROTATION_Y, -18f, 18f).apply {
                    duration = 700; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator(); start()
                }
            }
            GhostState.EXECUTING -> {
                ghostOrb.alpha = 1f
                ghostOrb.setImageResource(R.drawable.yuna_ghost_think)
            }
            GhostState.NEEDS_CONFIRM -> {
                ghostOrb.alpha = 1f
                expandDock()
            }
        }
    }

    private fun showCaption(txt: String) {
        if (txt.isBlank()) return
        captionPill.text = txt
        captionPill.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideCaptionRunnable)
        mainHandler.postDelayed(hideCaptionRunnable, 4200L)
    }

    private fun showResultCard(txt: String, imgB64: String) {
        if (txt.isBlank() && imgB64.isBlank()) return
        if (imgB64.isNotBlank()) {
            runCatching {
                val bytes = Base64.decode(imgB64.substringAfter(","), Base64.DEFAULT)
                lastResultBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                resultImage.setImageBitmap(lastResultBitmap)
                resultImage.visibility = View.VISIBLE
            }
        } else {
            resultImage.visibility = View.GONE
        }
        resultText.text = txt
        resultCard.visibility = View.VISIBLE
    }

    private fun showConfirmPrompt(reason: String) {
        confirmLabel.text = reason
        confirmBar.visibility = View.VISIBLE
        applyGhostState(GhostState.NEEDS_CONFIRM)
    }

    private fun sendConfirmResult(ok: Boolean) {
        confirmBar.visibility = View.GONE
        sendBroadcast(Intent(ACTION_USER_CONFIRMED).putExtra(EXTRA_CONFIRMED, ok))
        applyGhostState(GhostState.IDLE)
    }

    private fun toggleMute() {
        isMuted = !isMuted
        muteBtn.text = if (isMuted) "🔇" else "🎙"
        sendBroadcast(Intent(ACTION_VOICE_MUTE_STATE).putExtra("muted", isMuted))
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
        scheduleAutoCollapse()
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "YUNA Live Ghost", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val endPi = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_END_SESSION).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mutePi = PendingIntent.getBroadcast(
            this, 2, Intent(ACTION_MUTE_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("YUNA Live Ghost")
            .setContentText(if (isMuted) "Mic Muted — Tap Mute to resume" else "Mic is ON — Listening in background")
            .setSmallIcon(R.drawable.yuna_ghost_idle)
            .setOngoing(true)
            .addAction(0, "End", endPi)
            .addAction(0, if (isMuted) "Unmute" else "Mute", mutePi)
            .build()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        activeAnimator?.cancel()
        YunaAgentOrchestrator.screenVision?.release()
        YunaAgentOrchestrator.screenVision = null
        runCatching { unregisterReceiver(ctrlReceiver) }
        runCatching { wm.removeView(rootLayout) }
        super.onDestroy()
    }
}
