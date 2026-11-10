package ai.oxygen.app

import android.webkit.JavascriptInterface
import com.onesignal.OneSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Small bridge for the OXYGEN WebView's native push opt-in and account identity.
 * MainActivity keeps only the OXYGEN production host inside this WebView.
 */
class PushBridge(private val activity: MainActivity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun isConfigured(): Boolean = BuildConfig.ONESIGNAL_APP_ID.isNotBlank()

    @JavascriptInterface
    fun requestPermission() {
        if (!isConfigured()) return
        scope.launch {
            try {
                OneSignal.Notifications.requestPermission(true)
            } catch (_: Exception) {
                // Permission denial or SDK errors do not crash the WebView.
            }
        }
    }

    @JavascriptInterface
    fun login(externalId: String) {
        if (!isConfigured() || externalId.isBlank()) return
        scope.launch {
            try {
                OneSignal.login(externalId)
            } catch (_: Exception) {
            }
        }
    }

    @JavascriptInterface
    fun logout() {
        if (!isConfigured()) return
        scope.launch {
            try {
                OneSignal.logout()
            } catch (_: Exception) {
            }
        }
    }
}
