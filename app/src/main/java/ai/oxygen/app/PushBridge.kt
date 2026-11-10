package ai.oxygen.app

import android.webkit.JavascriptInterface
import com.onesignal.OneSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Small, origin-restricted bridge for the OXYGEN WebView's native push opt-in.
 * The website is the only page kept inside this WebView.
 */
class PushBridge(private val activity: MainActivity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @JavascriptInterface
    fun isConfigured(): Boolean = BuildConfig.ONESIGNAL_APP_ID.isNotBlank()

    @JavascriptInterface
    fun requestPermission() {
        if (!isConfigured()) return
        activity.runOnUiThread {
            scope.launch {
                try {
                    OneSignal.Notifications.requestPermission(true)
                } catch (_: Exception) {
                    // Permission denial or SDK errors are handled by Android/OneSignal.
                }
            }
        }
    }
}
