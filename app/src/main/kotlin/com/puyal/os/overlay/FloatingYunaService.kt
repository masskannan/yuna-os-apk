package com.puyal.os.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class FloatingYunaService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var rootView: FrameLayout
    private lateinit var miniPanel: LinearLayout
    private lateinit var orbBtn: TextView
    private lateinit var statusTv: TextView
    private lateinit var inputEt: EditText
    private var mediaPlayer: MediaPlayer? = null
    private var localTts: TextToSpeech? = null
    private val ioExec = Executors.newSingleThreadExecutor()
    private val mainH = Handler(Looper.getMainLooper())
    private val workerUrl = "https://yuna-ai-gateway.masskannan719.workers.dev"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startOverlayForeground()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        localTts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                try { localTts?.language = Locale("ta", "IN") } catch (_: Exception) {}
            }
        }
        buildOverlayUi()
    }

    private fun startOverlayForeground() {
        val chId = "yuna_overlay_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(chId, "YUNA Floating Overlay", NotificationManager.IMPORTANCE_LOW))
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, chId) else Notification.Builder(this)
        val notif = b.setContentTitle("YUNA Floating Assistant")
            .setContentText("YouTube & பிற செயலிகள் மேல் தயார்")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(9169, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(9169, notif)
        }
    }

    private fun buildOverlayUi() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        rootView = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    setInputFocusMode(false)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.action == MotionEvent.ACTION_OUTSIDE) setInputFocusMode(false)
                return super.onTouchEvent(event)
            }
        }

        val bgPill = GradientDrawable().apply {
            setColor(Color.parseColor("#E60F172A"))
            cornerRadius = 48f
            setStroke(2, Color.parseColor("#6638BDF8"))
        }

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        orbBtn = TextView(this).apply {
            text = "✦ YUNA"
            setTextColor(Color.parseColor("#E0F2FE"))
            textSize = 13f
            setPadding(28, 18, 28, 18)
            background = bgPill
            setOnTouchListener { _, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        startX = params.x; startY = params.y
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downX).toInt()
                        val dy = (ev.rawY - downY).toInt()
                        if (abs(dx) > slop || abs(dy) > slop) dragging = true
                        if (dragging) {
                            params.x = startX + dx
                            params.y = startY + dy
                            wm.updateViewLayout(rootView, params)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) toggleExpanded(true) else clampToSafeWindowInsets()
                        true
                    }
                    else -> false
                }
            }
        }

        miniPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F20B1220"))
                cornerRadius = 36f
                setStroke(2, Color.parseColor("#8838BDF8"))
            }
        }

        statusTv = TextView(this).apply {
            text = "YouTube / திரை மேல் கேளுங்கள்"
            setTextColor(Color.parseColor("#BAE6FD"))
            textSize = 12f
        }

        inputEt = EditText(this).apply {
            hint = "கேள்வி தட்டச்சு..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 13f
            minWidth = 480
            setOnClickListener { setInputFocusMode(true) }
        }

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sendBtn = Button(this).apply {
            text = "அனுப்பு"
            setOnClickListener {
                val q = inputEt.text?.toString()?.trim() ?: ""
                if (q.isNotEmpty()) {
                    inputEt.setText("")
                    setInputFocusMode(false)
                    sendPromptAndFetchTts(q)
                }
            }
        }
        val closeBtn = Button(this).apply {
            text = "✕"
            setOnClickListener {
                setInputFocusMode(false)
                toggleExpanded(false)
            }
        }
        btnRow.addView(sendBtn)
        btnRow.addView(closeBtn)

        miniPanel.addView(statusTv)
        miniPanel.addView(inputEt)
        miniPanel.addView(btnRow)

        rootView.addView(orbBtn)
        rootView.addView(miniPanel)
        wm.addView(rootView, params)
    }

    private fun toggleExpanded(expand: Boolean) {
        orbBtn.visibility = if (expand) View.GONE else View.VISIBLE
        miniPanel.visibility = if (expand) View.VISIBLE else View.GONE
        mainH.post { clampToSafeWindowInsets() }
    }

    private fun setInputFocusMode(enableTyping: Boolean) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (enableTyping) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            wm.updateViewLayout(rootView, params)
            inputEt.requestFocus()
            imm.showSoftInput(inputEt, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(inputEt.windowToken, 0)
            inputEt.clearFocus()
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
            wm.updateViewLayout(rootView, params)
        }
    }

    private fun sendPromptAndFetchTts(text: String) {
        mainH.post { statusTv.text = "யோசிக்கிறது..." }
        ioExec.execute {
            try {
                val conn = (URL(workerUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().put("prompt", text).put("mode", "overlay").toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                val reply = try { JSONObject(respStr).optString("reply", respStr) } catch (_: Exception) { respStr }
                mainH.post { statusTv.text = reply.take(140) }
                fetchAndPlayTts(reply.take(400))
            } catch (e: Exception) {
                mainH.post { statusTv.text = "இணையப் பிழை: ${e.message}" }
            }
        }
    }

    private fun fetchAndPlayTts(text: String) {
        try {
            val conn = (URL("$workerUrl/tts").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("text", text).put("voice", "ta-SG-VenbaNeural").toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode == 200) {
                val outFile = File(cacheDir, "yuna_overlay_tts.mp3")
                conn.inputStream.use { inp -> FileOutputStream(outFile).use { inp.copyTo(it) } }
                mainH.post { playTtsWithMediaPause(outFile) }
                return
            }
        } catch (_: Exception) {}
        mainH.post { localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yuna_ovl") }
    }

    private fun playTtsWithMediaPause(file: File) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        var focusReq: AudioFocusRequest? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            am.requestAudioFocus(focusReq)
        }
        if (am.isMusicActive) {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { mp ->
                mp.release()
                mediaPlayer = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusReq != null) am.abandonAudioFocusRequest(focusReq)
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                mediaPlayer = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusReq != null) am.abandonAudioFocusRequest(focusReq)
                true
            }
            prepare()
            start()
        }
    }

    private fun clampToSafeWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val ins = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val maxX = (metrics.bounds.width() - ins.right - rootView.width).coerceAtLeast(ins.left)
            val maxY = (metrics.bounds.height() - ins.bottom - rootView.height).coerceAtLeast(ins.top)
            params.x = params.x.coerceIn(ins.left, maxX)
            params.y = params.y.coerceIn(ins.top, maxY)
            wm.updateViewLayout(rootView, params)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        toggleExpanded(false)
        clampToSafeWindowInsets()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        try { localTts?.shutdown() } catch (_: Exception) {}
        if (::rootView.isInitialized && rootView.isAttachedToWindow) {
            try { wm.removeView(rootView) } catch (_: Exception) {}
        }
    }
}
