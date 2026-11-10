package ai.oxygen.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface

/** Receives the authenticated user's reminder list from the OXYGEN Schedule Manager page. */
class ReminderBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun sync(json: String): String {
        return try {
            LocalReminderScheduler.sync(activity, json)
            "ok"
        } catch (e: Exception) {
            "error"
        }
    }

    @JavascriptInterface
    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            activity.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    @JavascriptInterface
    fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (_: Exception) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                } catch (_: Exception) {}
            }
        }
    }
}
