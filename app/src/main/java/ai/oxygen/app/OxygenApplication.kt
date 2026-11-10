package ai.oxygen.app

import android.app.Application
import android.util.Log
import com.onesignal.OneSignal

class OxygenApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val appId = BuildConfig.ONESIGNAL_APP_ID.trim()
        if (appId.isBlank()) {
            Log.w("OXYGEN", "Native push is not initialized: OneSignal App ID is not configured.")
            return
        }

        OneSignal.initWithContext(this, appId)
        Log.i("OXYGEN", "OneSignal native push initialized.")
    }
}
