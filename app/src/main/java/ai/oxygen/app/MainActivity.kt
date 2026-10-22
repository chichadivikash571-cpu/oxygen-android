package ai.oxygen.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    companion object {
        const val HOST = "oxygen.chichadivikash571.workers.dev"
        const val FILE_CHOOSER_REQUEST = 4715
        const val WEB_MIC_REQUEST = 4712
        const val BG_MIC_REQUEST = 4713
        const val BG_NOTIFY_REQUEST = 4714
    }

    private lateinit var web: WebView
    private var voice: NativeVoice? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingFileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pageReady = false
    private var assistPending = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        assistPending = isAssist(intent)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false

        // The OXYGEN page may ask for the microphone (audio only); everything else is refused.
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermission(request) }
            }

            // Required for HTML file inputs inside Android WebView.
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return try {
                    startActivityForResult(fileChooserParams.createIntent(), FILE_CHOOSER_REQUEST)
                    true
                } catch (_: Exception) {
                    pendingFileCallback?.onReceiveValue(null)
                    pendingFileCallback = null
                    false
                }
            }
        }

        web.webViewClient = object : WebViewClient() {
            // Only the OXYGEN site stays inside the app; any other link opens in the normal browser.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: Exception) {
                }
                return true
            }

            // Inside the app only: load the background and device-control controls from the website.
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && Uri.parse(url).host == HOST) {
                    view.evaluateJavascript(
                        "(function(){if(window.__oxBg)return;window.__oxBg=1;" +
                            "var s=document.createElement('script');s.src='/background.js';" +
                            "document.head.appendChild(s);" +
                            "var d=document.createElement('script');d.src='/device.js';" +
                            "document.head.appendChild(d);})()",
                        null
                    )
                    pageReady = true
                    if (assistPending) {
                        assistPending = false
                        view.postDelayed({ runAssist() }, 1800)
                    }
                }
            }
        }

        val nativeVoice = NativeVoice(this, web)
        voice = nativeVoice
        web.addJavascriptInterface(nativeVoice, "OxygenNative")
        web.addJavascriptInterface(BackgroundBridge(this), "OxygenBackground")
        web.addJavascriptInterface(DeviceBridge(this), "OxygenDevice")

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl("https://$HOST")
        }
    }

    private fun isAssist(i: Intent?): Boolean = i?.action == Intent.ACTION_ASSIST

    /** Asks the OXYGEN page to start listening (used when you open OXYGEN with the assistant gesture). */
    private fun runAssist() {
        web.evaluateJavascript("window.oxygenAssist && window.oxygenAssist()", null)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAssist(intent)) {
            if (pageReady) web.postDelayed({ runAssist() }, 400) else assistPending = true
        }
    }

    private fun handleWebPermission(request: PermissionRequest) {
        val audioOnly = request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        if (request.origin.host != HOST || !audioOnly) {
            request.deny()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingWebPermission = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), WEB_MIC_REQUEST)
        }
    }

    /** Asks for the microphone and notification permissions if needed, then starts the background service. */
    fun startBackgroundFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), BG_MIC_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), BG_NOTIFY_REQUEST)
            return
        }
        launchService()
    }

    private fun launchService() {
        try {
            OxygenService.start(this)
        } catch (_: Exception) {
        }
        notifyBackgroundChanged()
    }

    fun notifyBackgroundChanged() {
        runOnUiThread {
            web.evaluateJavascript("window.oxygenBackgroundChanged && window.oxygenBackgroundChanged()", null)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val callback = pendingFileCallback
            pendingFileCallback = null
            if (callback != null) {
                val results = if (resultCode == RESULT_OK) {
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                } else null
                callback.onReceiveValue(results)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            WEB_MIC_REQUEST -> {
                val req = pendingWebPermission
                pendingWebPermission = null
                if (req != null) {
                    if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
                }
            }
            BG_MIC_REQUEST -> if (granted) startBackgroundFlow() else notifyBackgroundChanged()
            BG_NOTIFY_REQUEST -> launchService()   // runs even if notifications were declined
            else -> voice?.onPermissionResult(requestCode, granted)
        }
    }

    override fun onResume() {
        super.onResume()
        notifyBackgroundChanged()
        web.evaluateJavascript("window.oxygenDeviceChanged && window.oxygenDeviceChanged()", null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        voice?.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
