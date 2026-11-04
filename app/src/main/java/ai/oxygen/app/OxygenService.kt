package ai.oxygen.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.graphics.drawable.Icon
import android.os.Bundle
import java.util.Locale

/**
 * User-started OXYGEN background listener.
 *
 * Uses Android's speech recognizer to look for the phrase "Hey OXYGEN".
 * This is a best-effort software listener, not a dedicated low-power DSP wake-word engine.
 * Android lockscreen authentication is never bypassed. A detected phrase posts a notification
 * that the user can tap to open the assistant.
 */
class OxygenService : Service() {

    companion object {
        const val CHANNEL_ID = "oxygen_wakeword_v1"
        const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "ai.oxygen.app.STOP"
        private const val WAKE_PHRASE = "hey oxygen"

        @Volatile
        var running = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OxygenService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OxygenService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeDetected = false
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OXYGEN wake-word listener",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Visible while OXYGEN listens for Hey OXYGEN"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = buildNotification("Listening for “Hey OXYGEN”")
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            running = true
            acquireCpuLock()
            startWakeWordRecognition()
        } catch (_: Exception) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun acquireCpuLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OXYGEN:WakeWord")
            wakeLock?.setReferenceCounted(false)
            if (wakeLock?.isHeld != true) wakeLock?.acquire(10 * 60 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWakeWordRecognition() {
        if (destroyed || wakeDetected || !running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission required — open OXYGEN")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable on this device")
            return
        }

        try {
            recognizer?.destroy()
            val r = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1400L else 900L)
                }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ").orEmpty()
                    if (isWakePhrase(text)) onWakePhrase()
                    else scheduleRestart(500L)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.joinToString(" ").orEmpty()
                    if (isWakePhrase(text)) onWakePhrase()
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.ENGLISH.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.ENGLISH.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            r.startListening(intent)
        } catch (_: Exception) {
            scheduleRestart(1800L)
        }
    }

    private fun isWakePhrase(raw: String): Boolean {
        val normalized = raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized.contains(WAKE_PHRASE) ||
            normalized.contains("hey oxigen") ||
            normalized == "oxygen" ||
            normalized.startsWith("oxygen ")
    }

    private fun onWakePhrase() {
        if (wakeDetected || destroyed) return
        wakeDetected = true
        updateNotification("Wake phrase detected — tap to open OXYGEN")
        try { recognizer?.cancel() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            wakeDetected = false
            updateNotification("Listening for “Hey OXYGEN”")
            startWakeWordRecognition()
        }, 8000L)
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed || wakeDetected || !running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ startWakeWordRecognition() }, delayMs)
    }

    private fun buildNotification(message: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OverlayActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OxygenService::class.java).setAction(ACTION_STOP), flags
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop listening",
            stopIntent
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OXYGEN wake word")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification(message: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(message))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        recognizer?.destroy()
        recognizer = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
