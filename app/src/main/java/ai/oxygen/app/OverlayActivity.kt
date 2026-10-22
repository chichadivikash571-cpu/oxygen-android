package ai.oxygen.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The assistant screen: a transparent window that shows the OXYGEN globe over whatever
 * you were doing (for example the home screen) instead of opening the whole app.
 */
class OverlayActivity : Activity() {

    companion object {
        const val MIC_REQUEST = 4720
    }

    private lateinit var web: WebView
    private var voice: NativeVoice? = null
    private var pendingPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        web = WebView(this)
        web.setBackgroundColor(Color.TRANSPARENT)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handlePermission(request) }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != MainActivity.HOST
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && android.net.Uri.parse(url).host == MainActivity.HOST) {
                    // The earbuds/system assistant opens this overlay directly. Start the
                    // site's assistant flow after its JavaScript has loaded, so the user
                    // does not have to tap the globe to begin speaking.
                    view.postDelayed({
                        view.evaluateJavascript(
                            "window.oxygenAssist && window.oxygenAssist()",
                            null
                        )
                    }, 900)
                }
            }
        }

        val nativeVoice = NativeVoice(this, web)
        voice = nativeVoice
        web.addJavascriptInterface(nativeVoice, "OxygenNative")
        web.addJavascriptInterface(OverlayBridge(), "OxygenOverlay")
        web.addJavascriptInterface(DeviceBridge(this), "OxygenDevice")

        web.loadUrl("https://${MainActivity.HOST}/assistant.html")
    }

    inner class OverlayBridge {
        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun openApp() {
            runOnUiThread {
                startActivity(Intent(this@OverlayActivity, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun handlePermission(request: PermissionRequest) {
        val audioOnly = request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        if (request.origin.host != MainActivity.HOST || !audioOnly) {
            request.deny()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            pendingPermission = request
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val req = pendingPermission
            pendingPermission = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        web.evaluateJavascript("window.oxygenOverlayRestart && window.oxygenOverlayRestart()", null)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        voice?.destroy()
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        finish()
    }
}
