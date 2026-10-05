package ai.oxygen.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Keeps OXYGEN alive in the background. It shows a permanent notification (Android requires this
 * whenever an app may use the microphone in the background) with a Stop button.
 */
class OxygenService : Service() {

    companion object {
        const val CHANNEL_ID = "oxygen_background"
        const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "ai.oxygen.app.STOP"

        @Volatile
        var running = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OxygenService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OxygenService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "OXYGEN background", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows that OXYGEN is running in the background"
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
        } catch (e: Exception) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OxygenService::class.java).setAction(ACTION_STOP), flags
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop",
            stopIntent
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OXYGEN is running")
            .setContentText("Tap to open OXYGEN")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
