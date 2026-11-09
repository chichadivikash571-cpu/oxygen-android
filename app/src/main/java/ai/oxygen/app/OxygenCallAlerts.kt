package ai.oxygen.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OxygenCallIntents {
    const val EXTRA_OPEN_PATH = "oxygen_open_path"
    const val EXTRA_TITLE = "oxygen_alert_title"
    const val EXTRA_BODY = "oxygen_alert_body"
    const val EXTRA_TYPE = "oxygen_alert_type"
    const val EXTRA_ALERT_ID = "oxygen_alert_id"
    const val EXTRA_NOTIFICATION_ID = "oxygen_notification_id"
    const val ACTION_ACCEPT = "ai.oxygen.app.ALERT_ACCEPT"
    const val ACTION_DECLINE = "ai.oxygen.app.ALERT_DECLINE"
}

/** Stores the device's FCM token and creates the high-importance call-alert channel. */
object OxygenPushManager {
    const val CHANNEL_ID = "oxygen_call_alerts_v1"
    private const val PREFS = "oxygen_push"
    private const val TOKEN_KEY = "fcm_token"
    private val vibrationPattern = longArrayOf(0, 550, 250, 550, 700)

    fun isConfigured(context: Context): Boolean =
        try { FirebaseApp.getApps(context).isNotEmpty() } catch (_: Exception) { false }

    fun cachedToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN_KEY, "").orEmpty()

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(TOKEN_KEY, token).apply()
    }

    fun refreshToken(context: Context, onReady: () -> Unit) {
        if (!isConfigured(context)) {
            onReady()
            return
        }
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (token.isNotBlank()) saveToken(context, token)
                    onReady()
                }
                .addOnFailureListener { onReady() }
        } catch (_: Exception) {
            onReady()
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OXYGEN incoming alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming OXYGEN approval requests, important information, and reminders"
            setSound(ringtoneUri, audioAttributes)
            enableVibration(true)
            vibrationPattern = vibrationPattern
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun showIncoming(
        context: Context,
        type: String,
        title: String,
        body: String,
        alertId: String,
        approvalId: String,
        taskId: String
    ) {
        ensureChannel(context)
        val notificationId = (alertId.hashCode() and 0x7fffffff).coerceAtLeast(1)
        val route = if (approvalId.isNotBlank()) "/scheduler.html" else "/assistant.html"

        val openIntent = Intent(context, OxygenIncomingAlertActivity::class.java).apply {
            putExtra(OxygenCallIntents.EXTRA_TYPE, type)
            putExtra(OxygenCallIntents.EXTRA_TITLE, title)
            putExtra(OxygenCallIntents.EXTRA_BODY, body)
            putExtra(OxygenCallIntents.EXTRA_ALERT_ID, alertId)
            putExtra(OxygenCallIntents.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(OxygenCallIntents.EXTRA_OPEN_PATH, route)
            putExtra("approval_id", approvalId)
            putExtra("task_id", taskId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullScreen = PendingIntent.getActivity(context, notificationId, openIntent, flags)

        val acceptIntent = Intent(context, OxygenCallActionReceiver::class.java).apply {
            action = OxygenCallIntents.ACTION_ACCEPT
            putExtra(OxygenCallIntents.EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(OxygenCallIntents.EXTRA_OPEN_PATH, route)
        }
        val declineIntent = Intent(context, OxygenCallActionReceiver::class.java).apply {
            action = OxygenCallIntents.ACTION_DECLINE
            putExtra(OxygenCallIntents.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val accept = PendingIntent.getBroadcast(context, notificationId * 2, acceptIntent, flags)
        val decline = PendingIntent.getBroadcast(context, notificationId * 2 + 1, declineIntent, flags)

        val kind = when (type.lowercase(Locale.ROOT)) {
            "approval" -> "APPROVAL REQUEST"
            "reminder" -> "SCHEDULED REMINDER"
            else -> "IMPORTANT INFORMATION"
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentTitle("OXYGEN • $kind")
            .setContentText(title.ifBlank { body })
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(android.R.drawable.sym_call_incoming, "Accept", accept)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", decline)
            .setPriority(Notification.PRIORITY_MAX)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }
}

/** FCM receiver: data-only, high-priority messages become native incoming OXYGEN alert screens. */
class OxygenPushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        OxygenPushManager.saveToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"] ?: "important"
        val title = data["title"]?.take(100) ?: "OXYGEN update"
        val body = data["body"]?.take(1000) ?: "You have an important update."
        val alertId = data["alert_id"] ?: message.messageId ?: System.currentTimeMillis().toString()
        OxygenPushManager.showIncoming(
            this,
            type,
            title,
            body,
            alertId,
            data["approval_id"].orEmpty(),
            data["task_id"].orEmpty()
        )
    }
}

/** Native swipe screen shown over the lock screen when Android permits full-screen alerts. */
class OxygenIncomingAlertActivity : Activity() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var notificationId = 0
    private var ringStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreen()
        OxygenPushManager.ensureChannel(this)
        notificationId = intent.getIntExtra(OxygenCallIntents.EXTRA_NOTIFICATION_ID, 0)
        render()
        startRinging()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        stopRinging()
        notificationId = intent?.getIntExtra(OxygenCallIntents.EXTRA_NOTIFICATION_ID, 0) ?: notificationId
        render()
        startRinging()
    }

    private fun configureLockScreen() {
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
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(3, 10, 24)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun render() {
        val root = FrameLayout(this)
        root.addView(OxygenCallWallpaperView(this), FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(52), dp(28), dp(30))
        }
        val brand = textView("OXYGEN", 18f, Color.rgb(128, 232, 255), true).apply {
            letterSpacing = 0.32f
        }
        content.addView(brand, LinearLayout.LayoutParams(-2, dp(38)))

        val incoming = textView("INCOMING OXYGEN ALERT", 12f, Color.rgb(190, 214, 235), true).apply {
            letterSpacing = 0.18f
        }
        val incomingParams = LinearLayout.LayoutParams(-2, dp(28))
        incomingParams.topMargin = dp(42)
        content.addView(incoming, incomingParams)

        val title = textView(
            intent.getStringExtra(OxygenCallIntents.EXTRA_TITLE).orEmpty().ifBlank { "OXYGEN needs your attention" },
            25f,
            Color.WHITE,
            true
        ).apply {
            gravity = Gravity.CENTER
        }
        val titleParams = LinearLayout.LayoutParams(-1, -2)
        titleParams.topMargin = dp(18)
        content.addView(title, titleParams)

        val kind = when (intent.getStringExtra(OxygenCallIntents.EXTRA_TYPE)?.lowercase(Locale.ROOT)) {
            "approval" -> "Approval request"
            "reminder" -> "Scheduled reminder"
            else -> "Important information"
        }
        val typeText = textView(kind.uppercase(Locale.ROOT), 11f, Color.rgb(92, 215, 255), true)
        val typeParams = LinearLayout.LayoutParams(-2, dp(26))
        typeParams.topMargin = dp(16)
        content.addView(typeText, typeParams)

        val body = textView(
            intent.getStringExtra(OxygenCallIntents.EXTRA_BODY).orEmpty(),
            15f,
            Color.rgb(220, 233, 246),
            false
        ).apply {
            gravity = Gravity.CENTER
        }
        val bodyParams = LinearLayout.LayoutParams(-1, -2)
        bodyParams.topMargin = dp(12)
        content.addView(body, bodyParams)

        val spacer = View(this)
        content.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))

        val hint = textView("SWIPE TO ANSWER OR DECLINE", 10f, Color.rgb(143, 178, 201), true)
        val hintParams = LinearLayout.LayoutParams(-2, dp(24))
        hintParams.bottomMargin = dp(12)
        content.addView(hint, hintParams)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val decline = OxygenSwipeActionView(this, "SWIPE TO DECLINE", false) { declineAlert() }
        val accept = OxygenSwipeActionView(this, "SWIPE TO ACCEPT", true) { acceptAlert() }
        val actionParams = LinearLayout.LayoutParams(0, dp(70), 1f).apply {
            marginEnd = dp(7)
        }
        actions.addView(decline, actionParams)
        actions.addView(accept, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(7) })
        content.addView(actions, LinearLayout.LayoutParams(-1, dp(70)))

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun textView(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startRinging() {
        if (ringStarted) return
        ringStarted = true
        try {
            val uri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: Settings.System.DEFAULT_NOTIFICATION_URI
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone?.isLooping = true
            ringtone?.play()
        } catch (_: Exception) {
        }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 550, 250, 550, 700), 0))
        } catch (_: Exception) {
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        ringStarted = false
    }

    private fun acceptAlert() {
        stopRinging()
        if (notificationId != 0) OxygenPushManager.cancel(this, notificationId)
        val path = intent.getStringExtra(OxygenCallIntents.EXTRA_OPEN_PATH)
            ?.takeIf { it == "/scheduler.html" || it == "/assistant.html" } ?: "/assistant.html"
        val open = Intent(this, MainActivity::class.java).apply {
            putExtra(OxygenCallIntents.EXTRA_OPEN_PATH, path)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(open)
        finish()
    }

    private fun declineAlert() {
        stopRinging()
        if (notificationId != 0) OxygenPushManager.cancel(this, notificationId)
        finish()
    }

    @Deprecated("Deprecated in Android, retained for a safe decline fallback")
    override fun onBackPressed() {
        declineAlert()
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}

/** Notification action fallback for devices where the full-screen activity is not allowed. */
class OxygenCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(OxygenCallIntents.EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) OxygenPushManager.cancel(context, id)
        if (intent.action == OxygenCallIntents.ACTION_ACCEPT) {
            val route = intent.getStringExtra(OxygenCallIntents.EXTRA_OPEN_PATH)
                ?.takeIf { it == "/scheduler.html" || it == "/assistant.html" } ?: "/assistant.html"
            val open = Intent(context, MainActivity::class.java).apply {
                putExtra(OxygenCallIntents.EXTRA_OPEN_PATH, route)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(open)
        }
    }
}

/** Custom, accessible swipe track: accept slides right; decline slides left. */
private class OxygenSwipeActionView(
    context: Context,
    private val label: String,
    private val swipeRight: Boolean,
    private val onComplete: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var downX = 0f
    private var dragX = 0f
    private val radius = 25f * density
    private val maxDrag get() = max(0f, width - radius * 2f - 12f * density)

    init {
        contentDescription = label
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val bgColor = if (swipeRight) Color.rgb(9, 104, 100) else Color.rgb(125, 37, 61)
        paint.style = Paint.Style.FILL
        paint.color = bgColor
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2f, h / 2f, paint)

        paint.color = Color.WHITE
        paint.textSize = 10f * density
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val textY = h / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, width / 2f, textY, paint)

        val base = if (swipeRight) radius + 5f * density else width - radius - 5f * density
        val knobX = if (swipeRight) base + dragX else base + dragX
        paint.color = if (swipeRight) Color.rgb(43, 240, 205) else Color.rgb(255, 94, 130)
        canvas.drawCircle(knobX, h / 2f, radius, paint)
        paint.color = Color.rgb(4, 20, 31)
        paint.textSize = 24f * density
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        canvas.drawText(if (swipeRight) "›" else "‹", knobX, h / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.x - downX
                dragX = if (swipeRight) {
                    min(maxDrag, max(0f, delta))
                } else {
                    -min(maxDrag, max(0f, -delta))
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val completed = abs(dragX) >= maxDrag * 0.58f && maxDrag > 0f
                dragX = 0f
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (completed) onComplete()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragX = 0f
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}

/** Lightweight OXYGEN call wallpaper, drawn locally so it works offline and needs no image download. */
private class OxygenCallWallpaperView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = RadialGradient(
            w * 0.5f, h * 0.34f, max(w, h) * 0.76f,
            intArrayOf(Color.rgb(15, 78, 112), Color.rgb(6, 28, 58), Color.rgb(2, 7, 20)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * resources.displayMetrics.density
        for (i in 0..5) {
            paint.color = Color.argb(22 + i * 5, 68, 211, 255)
            canvas.drawCircle(w * 0.5f, h * 0.36f, (45f + i * 31f) * resources.displayMetrics.density, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(18, 74, 227, 255)
        canvas.drawCircle(w * 0.5f, h * 0.36f, 40f * resources.displayMetrics.density, paint)
        paint.color = Color.argb(170, 105, 231, 255)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 42f * resources.displayMetrics.density
        paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        canvas.drawText("O", w * 0.5f, h * 0.36f + 15f * resources.displayMetrics.density, paint)
    }
}
