package ai.oxygen.app

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface

/** Narrow bridge used by the signed-in OXYGEN page to register this device for push alerts. */
class OxygenPushBridge(private val activity: Activity) {
    @JavascriptInterface
    fun isConfigured(): Boolean = OxygenPushManager.isConfigured(activity)

    @JavascriptInterface
    fun getToken(): String = OxygenPushManager.cachedToken(activity)

    @JavascriptInterface
    fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } catch (_: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun areNotificationsEnabled(): Boolean {
        return try {
            activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        } catch (_: Exception) {
            false
        }
    }

    /** Opens Android's own permission page; OXYGEN never changes the user's alert preferences itself. */
    @JavascriptInterface
    fun openCallAlertSettings() {
        activity.runOnUiThread {
            val intent = if (Build.VERSION.SDK_INT >= 34 && !canUseFullScreenIntent()) {
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:${activity.packageName}")
                )
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                }
            }
            try {
                activity.startActivity(intent)
            } catch (_: Exception) {
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${activity.packageName}"))
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}
