package com.puyal.os

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.webkit.WebViewAssetLoader
import com.puyal.os.bridge.YunaBridge
import com.puyal.os.guard.YunaTierGuards
import com.puyal.os.service.PuyalCoreService
import org.json.JSONObject
import java.io.File

class MainActivity : FragmentActivity() {
    private lateinit var webView: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var speechRec: SpeechRecognizer? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCb?.onReceiveValue(uris.toTypedArray()); fileCb = null
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private fun startHeadlessVoice(lang: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        try {
            speechRec?.destroy()
            speechRec = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        webView.evaluateJavascript("window.stopMicHardware && window.stopMicHardware();", null)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val txt = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        val q = JSONObject.quote(txt)
                        webView.evaluateJavascript("window.onYunaVoicePartial && window.onYunaVoicePartial($q);", null)
                    }
                    override fun onResults(results: Bundle?) {
                        val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        val q = JSONObject.quote(txt)
                        webView.evaluateJavascript("window.onYunaVoiceResult && window.onYunaVoiceResult($q);", null)
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val vi = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                startListening(vi)
            }
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (YunaTierGuards.onHardwareKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash_log.txt").appendText("${System.currentTimeMillis()}: ${e.stackTraceToString()}\n") } catch (_: Exception) {}
            prevHandler?.uncaughtException(t, e)
        }

        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())

        val svcIntent = Intent(this, PuyalCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent) else startService(svcIntent)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(YunaBridge(this@MainActivity) { lang -> runOnUiThread { startHeadlessVoice(lang) } }, "YunaBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.grant(request.resources)
                }
                override fun onShowFileChooser(vw: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileCb?.onReceiveValue(null); fileCb = cb; filePicker.launch(arrayOf("*/*")); return true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
        setContentView(webView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("!!document.querySelector('#settingsPage.open,#drawerScrim.open,#plusScrim.open,#lightbox.open')") { v ->
                    if (v == "true") {
                        webView.evaluateJavascript("document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));", null)
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    override fun onResume() { super.onResume(); sendBroadcast(Intent("com.puyal.os.ACTION_YUNA_FG").setPackage(packageName)) }
    override fun onPause() { super.onPause(); sendBroadcast(Intent("com.puyal.os.ACTION_YUNA_BG").setPackage(packageName)) }
    override fun onDestroy() {
        try { speechRec?.destroy() } catch (_: Exception) {}
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
