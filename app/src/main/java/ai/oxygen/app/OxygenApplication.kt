package ai.oxygen.app

import android.app.Application
import android.content.Intent
import android.util.Log
import com.onesignal.OneSignal
import com.onesignal.notifications.INotificationClickEvent
import com.onesignal.notifications.INotificationClickListener

class OxygenApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val appId = BuildConfig.ONESIGNAL_APP_ID.trim()
        if (appId.isBlank()) {
            Log.w("OXYGEN", "Native push is not initialized: OneSignal App ID is not configured.")
            return
        }

        OneSignal.initWithContext(this, appId)
        OneSignal.Notifications.addClickListener(object : INotificationClickListener {
            override fun onClick(event: INotificationClickEvent) {
                val notification = event.notification
                val data = notification.additionalData
                if (data?.optString("oxygen_type") != "scheduled_reminder") return

                val reminderText = notification.body.orEmpty()
                val open = Intent(this@OxygenApplication, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(MainActivity.EXTRA_REMINDER_TEXT, reminderText)
                }
                startActivity(open)
            }
        })
        Log.i("OXYGEN", "OneSignal native push initialized.")
    }
}
