package ai.oxygen.app

import android.webkit.JavascriptInterface

/** Keeps only the encrypted refresh token needed for scheduled background sync. */
class ReminderBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun sync(json: String): String {
        return try { LocalReminderScheduler.sync(activity, json); "ok" } catch (_: Exception) { "error" }
    }

    @JavascriptInterface
    fun setSession(refreshToken: String) {
        if (refreshToken.isBlank()) return
        ReminderSessionStore.save(activity.applicationContext, refreshToken)
    }

    @JavascriptInterface
    fun clearSession() {
        ReminderSessionStore.clear(activity.applicationContext)
    }

    @JavascriptInterface
    fun canScheduleExact(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            activity.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    @JavascriptInterface
    fun requestExactAlarmAccess() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        activity.runOnUiThread {
            try {
                activity.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        android.net.Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (_: Exception) {
                try { activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
                catch (_: Exception) {}
            }
        }
    }
}
