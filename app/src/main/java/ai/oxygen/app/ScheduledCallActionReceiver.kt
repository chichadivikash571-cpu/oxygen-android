package ai.oxygen.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduledCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduledCallNotifications.ACTION_DECLINE) return
        val callId = intent.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_ID).orEmpty()
        if (callId.isBlank()) return
        ScheduledCallNotifications.cancel(context, callId)
        context.getSharedPreferences("oxygen_scheduled_calls", Context.MODE_PRIVATE)
            .edit().putString("status_$callId", "declined").apply()
    }
}
