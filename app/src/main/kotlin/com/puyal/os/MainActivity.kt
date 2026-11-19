package com.puyal.os

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import com.puyal.os.bridge.YunaBridge
import com.puyal.os.service.PuyalCoreService

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCb?.onReceiveValue(uris.toTypedArray())
        fileCb = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val svcIntent = Intent(this, PuyalCoreService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent) else startService(svcIntent)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(YunaBridge(this@MainActivity), "YunaBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(vw: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                    fileCb?.onReceiveValue(null)
                    fileCb = cb
                    filePicker.launch(arrayOf("*/*"))
                    return true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
        setContentView(webView)
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent("com.puyal.os.ACTION_YUNA_FG").setPackage(packageName))
    }

    override fun onPause() {
        super.onPause()
        sendBroadcast(Intent("com.puyal.os.ACTION_YUNA_BG").setPackage(packageName))
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
