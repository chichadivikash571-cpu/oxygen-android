package ai.oxygen.app

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface

/**
 * Lets the OXYGEN page (running inside the app) start and stop background mode.
 * Only your OXYGEN site can reach this: the app opens every other link in the normal browser.
 */
class BackgroundBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun isRunning(): Boolean = OxygenService.running

    @JavascriptInterface
    fun start() {
        activity.runOnUiThread { activity.startBackgroundFlow() }
    }

    @JavascriptInterface
    fun stop() {
        activity.runOnUiThread {
            OxygenService.stop(activity)
            activity.notifyBackgroundChanged()
        }
    }

    @JavascriptInterface
    fun isBatteryUnrestricted(): Boolean {
        val pm = activity.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(activity.packageName)
    }

    @JavascriptInterface
    fun requestBatteryUnrestricted() {
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (e: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }
}
