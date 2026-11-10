package ai.oxygen.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Incoming-call style UI for OXYGEN assistant sessions (not a SIM/Telecom call). */
object ScheduledCallNotifications {
    const val CHANNEL_ID = "oxygen_scheduled_calls_v2"
    const val EXTRA_CALL_ID = "oxygen_scheduled_call_id"
    const val EXTRA_CALL_TITLE = "oxygen_scheduled_call_title"
    const val EXTRA_CALL_CONTEXT = "oxygen_scheduled_call_context"
    const val ACTION_DECLINE = "ai.oxygen.app.ACTION_DECLINE_SCHEDULED_CALL"

    fun showIncoming(context: Context, callId: String, title: String, taskContext: String) {
        if (callId.isBlank()) return
        createChannel(context)
        val fullScreen = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId); putExtra(EXTRA_CALL_TITLE, title)
            putExtra(EXTRA_CALL_CONTEXT, taskContext)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPending = PendingIntent.getActivity(context, callId.hashCode(), fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = Intent(context, ScheduledCallActionReceiver::class.java).apply {
            action = ACTION_DECLINE; putExtra(EXTRA_CALL_ID, callId)
        }
        val declinePending = PendingIntent.getBroadcast(context, callId.hashCode(), decline,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("OXYGEN is calling").setContentText(title)
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setAutoCancel(false)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePending)
            .setContentIntent(fullScreenPending).build()
        context.getSystemService(NotificationManager::class.java).notify(callId.hashCode(), notification)
    }

    fun cancel(context: Context, callId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(callId.hashCode())
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "OXYGEN incoming calls", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen OXYGEN scheduled assistant calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 650, 350, 650, 900)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            })
        }
    }
}

class IncomingCallActivity : Activity() {
    private var callId = ""
    private var callTitle = ""
    private var callContext = ""
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var alerting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readIntent(intent); render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent); setIntent(intent); readIntent(intent); render()
    }
    override fun onResume() { super.onResume(); startCallAlert() }
    override fun onPause() { stopCallAlert(); super.onPause() }
    override fun onDestroy() { stopCallAlert(); super.onDestroy() }

    private fun startCallAlert() {
        if (alerting) return
        alerting = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri); ringtone?.play()
        } catch (_: Exception) { }
        try {
            vibrator = getSystemService(Vibrator::class.java)
            val pattern = longArrayOf(0, 650, 350, 650, 900)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else { @Suppress("DEPRECATION") vibrator?.vibrate(pattern, 0) }
        } catch (_: Exception) { }
    }

    private fun stopCallAlert() {
        alerting = false
        try { ringtone?.stop() } catch (_: Exception) { }
        try { vibrator?.cancel() } catch (_: Exception) { }
        ringtone = null
    }

    private fun readIntent(intent: Intent?) {
        callId = intent?.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_ID).orEmpty()
        callTitle = intent?.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_TITLE)
            ?: "Your scheduled assistant session"
        callContext = intent?.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_CONTEXT).orEmpty()
    }

    private fun render() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 13, 27)) }
        val wallpaper = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.20f; contentDescription = "OXYGEN logo wallpaper"
        }
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(165, 4, 12, 25), Color.argb(225, 4, 12, 25)))
        }, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(34, 48, 34, 48)
        }
        content.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "OXYGEN logo"
        }, LinearLayout.LayoutParams(176, 176).apply { bottomMargin = 18 })
        content.addView(TextView(this).apply {
            text = "OXYGEN"; textSize = 32f; letterSpacing = 0.18f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "INCOMING AI CALL"; textSize = 13f; letterSpacing = 0.16f
            setTextColor(Color.rgb(91, 220, 255)); gravity = Gravity.CENTER; setPadding(0, 12, 0, 10)
        })
        content.addView(TextView(this).apply {
            text = callTitle; textSize = 20f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(0, 8, 0, 52)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(Button(this).apply {
            text = "Decline"; setTextColor(Color.WHITE); background = rounded(Color.rgb(190, 48, 63))
            setOnClickListener { declineCall() }
        }, LinearLayout.LayoutParams(0, 58, 1f).apply { marginEnd = 12 })
        actions.addView(Button(this).apply {
            text = "Accept"; setTextColor(Color.WHITE); background = rounded(Color.rgb(20, 155, 100))
            setOnClickListener { acceptCall() }
        }, LinearLayout.LayoutParams(0, 58, 1f).apply { marginStart = 12 })
        content.addView(actions)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 32f }

    private fun acceptCall() {
        stopCallAlert(); ScheduledCallNotifications.cancel(this, callId)
        startActivity(Intent(this, ActiveCallActivity::class.java).apply {
            putExtra(ScheduledCallNotifications.EXTRA_CALL_ID, callId)
            putExtra(ScheduledCallNotifications.EXTRA_CALL_CONTEXT, callContext)
            putExtra(ScheduledCallNotifications.EXTRA_CALL_TITLE, callTitle)
        })
        finish()
    }

    private fun declineCall() {
        stopCallAlert(); ScheduledCallNotifications.cancel(this, callId)
        getSharedPreferences("oxygen_scheduled_calls", MODE_PRIVATE).edit()
            .putString("status_$callId", "declined").apply()
        finish()
    }
}