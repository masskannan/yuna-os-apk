package com.puyal.os

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.puyal.os.bridge.YunaBridge
import com.puyal.os.service.PuyalCoreService
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var speechRec: SpeechRecognizer? = null
    private var lastRmsMs = 0L
    private var pageLoaded = false
    private var pendingQuickVoice = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread { request?.grant(request.resources) }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                if (pendingQuickVoice) {
                    pendingQuickVoice = false
                    webView.evaluateJavascript("if(typeof toggleVoice==='function'&&state!=='LISTENING')toggleVoice();", null)
                }
            }
        }

        val bridge = YunaBridge(
            ctx = this,
            onVoiceReq = { lang -> startHeadlessVoice(lang, false) },
            onStopVoiceReq = { stopHeadlessVoice() },
            onBiometricReq = { verifyBiometricOwner() },
            onTtsDone = { webView.evaluateJavascript("if(window.onYunaTtsDone)window.onYunaTtsDone();", null) }
        )
        webView.addJavascriptInterface(bridge, "YunaBridge")
        webView.loadUrl("file:///android_asset/index.html")

        requestRuntimePerms()
        startCoreServiceSafe()
        handleQuickVoiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickVoiceIntent(intent)
    }

    private fun handleQuickVoiceIntent(i: Intent?) {
        if (i?.action == "com.puyal.os.ACTION_QUICK_VOICE") {
            if (pageLoaded) {
                webView.evaluateJavascript("if(typeof toggleVoice==='function'&&state!=='LISTENING')toggleVoice();", null)
            } else {
                pendingQuickVoice = true
            }
        }
    }

    private fun verifyBiometricOwner() {
        try {
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (km.isKeyguardSecure) {
                @Suppress("DEPRECATION")
                val i = km.createConfirmDeviceCredentialIntent("YUNA Owner Check", "கண்ணன் உரிமையாளர் சரிபார்ப்பு")
                if (i != null) { startActivity(i); return }
            }
            webView.evaluateJavascript("if(typeof showToast==='function')showToast('Device Lock / Biometric ready');", null)
        } catch (_: Exception) {}
    }

    private fun requestRuntimePerms() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 777)
    }

    private fun stopHeadlessVoice() {
        try { speechRec?.stopListening() } catch (_: Exception) {}
    }

    private fun startHeadlessVoice(lang: String, preferOffline: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 778)
            webView.evaluateJavascript("if(window.onYunaVoiceEnded)window.onYunaVoiceEnded();", null)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            webView.evaluateJavascript("if(window.onYunaVoiceEnded)window.onYunaVoiceEnded();", null)
            return
        }
        runOnUiThread {
            try { speechRec?.cancel(); speechRec?.destroy() } catch (_: Exception) {}
            speechRec = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        val now = System.currentTimeMillis()
                        if (now - lastRmsMs >= 60L) {
                            lastRmsMs = now
                            webView.evaluateJavascript("if(window.onYunaMicLevel)window.onYunaMicLevel($rmsdB);", null)
                        }
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        webView.evaluateJavascript("if(window.onYunaVoiceEnded)window.onYunaVoiceEnded();", null)
                    }
                    override fun onError(error: Int) {
                        if (!preferOffline && (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT)) {
                            startHeadlessVoice(lang, true)
                            return
                        }
                        webView.evaluateJavascript("if(window.onYunaVoiceEnded)window.onYunaVoiceEnded();", null)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val txt = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        val safe = JSONObject.quote(txt)
                        webView.evaluateJavascript("if(window.onYunaVoicePartial)window.onYunaVoicePartial($safe);", null)
                    }
                    override fun onResults(results: Bundle?) {
                        val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: ""
                        if (txt.isEmpty()) {
                            webView.evaluateJavascript("if(window.onYunaVoiceEnded)window.onYunaVoiceEnded();", null)
                            return
                        }
                        val safe = JSONObject.quote(txt)
                        webView.evaluateJavascript("if(window.onYunaVoiceResult)window.onYunaVoiceResult($safe);", null)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val vi = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                startListening(vi)
            }
        }
    }

    private fun startCoreServiceSafe() {
        try {
            val svcIntent = Intent(this, PuyalCoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent) else startService(svcIntent)
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent("com.puyal.os.ACTION_YUNA_FG").setPackage(packageName))
    }

    override fun onPause() {
        super.onPause()
        stopHeadlessVoice()
        sendBroadcast(Intent("com.puyal.os.ACTION_YUNA_BG").setPackage(packageName))
    }

    override fun onDestroy() {
        try { speechRec?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
