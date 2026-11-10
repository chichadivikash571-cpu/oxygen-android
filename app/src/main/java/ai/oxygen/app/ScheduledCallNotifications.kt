package ai.oxygen.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Incoming-call UI primitives for OXYGEN scheduled assistant sessions.
 * The scheduler/backend integration is intentionally separate.
 */
object ScheduledCallNotifications {
    const val CHANNEL_ID = "oxygen_scheduled_calls"
    const val EXTRA_CALL_ID = "oxygen_scheduled_call_id"
    const val EXTRA_CALL_TITLE = "oxygen_scheduled_call_title"
    const val EXTRA_CALL_CONTEXT = "oxygen_scheduled_call_context"
    const val ACTION_DECLINE = "ai.oxygen.app.ACTION_DECLINE_SCHEDULED_CALL"

    fun showIncoming(context: Context, callId: String, title: String, taskContext: String) {
        if (callId.isBlank()) return
        createChannel(context)
        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_TITLE, title)
            putExtra(EXTRA_CALL_CONTEXT, taskContext)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(
            context, callId.hashCode(), fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = Intent(context, ScheduledCallActionReceiver::class.java).apply {
            action = ACTION_DECLINE
            putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePending = PendingIntent.getBroadcast(
            context, callId.hashCode(), decline,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("OXYGEN is calling")
            .setContentText(title)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePending)
            .setContentIntent(fullScreenPending)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(callId.hashCode(), notification)
    }

    fun cancel(context: Context, callId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(callId.hashCode())
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "OXYGEN scheduled calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming calls from your scheduled OXYGEN assistant sessions"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }
            )
        }
    }
}

class IncomingCallActivity : Activity() {
    private var callId: String = ""
    private var callTitle: String = ""
    private var callContext: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        render()
    }

    private fun readIntent(intent: Intent?) {
        callId = intent?.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_ID).orEmpty()
        callTitle = intent?.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_TITLE)
            ?: "Your scheduled assistant session"
        callContext = intent?.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_CONTEXT).orEmpty()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 48, 36, 48)
            setBackgroundColor(Color.rgb(8, 18, 32))
        }
        root.addView(TextView(this).apply {
            text = "OXYGEN"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Scheduled AI call"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = callTitle
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 40)
        })
        root.addView(Button(this).apply {
            text = "Accept"
            setOnClickListener { acceptCall() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })
        root.addView(Button(this).apply {
            text = "Decline"
            setOnClickListener { declineCall() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
    }

    private fun acceptCall() {
        ScheduledCallNotifications.cancel(this, callId)
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ScheduledCallNotifications.EXTRA_CALL_ID, callId)
            putExtra(ScheduledCallNotifications.EXTRA_CALL_CONTEXT, callContext)
            putExtra(ScheduledCallNotifications.EXTRA_CALL_TITLE, callTitle)
        }
        startActivity(open)
        finish()
    }

    private fun declineCall() {
        ScheduledCallNotifications.cancel(this, callId)
        getSharedPreferences("oxygen_scheduled_calls", MODE_PRIVATE)
            .edit().putString("status_$callId", "declined").apply()
        finish()
    }
}
