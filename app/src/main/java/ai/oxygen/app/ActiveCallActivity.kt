package ai.oxygen.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Native OXYGEN assistant-call UI. This is an in-app voice session, not a carrier phone call. */
class ActiveCallActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var callId = ""
    private var titleText = "Scheduled reminder"
    private var contextText = ""
    private var seconds = 0
    private var muted = false
    private var speakerOn = true
    private var listening = false
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var transcript = ""
    private lateinit var timerView: TextView
    private lateinit var statusView: TextView
    private lateinit var transcriptView: TextView
    private lateinit var micButton: Button
    private lateinit var muteButton: Button
    private lateinit var speakerButton: Button
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val ticker = object : Runnable {
        override fun run() {
            seconds++
            timerView.text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        callId = intent.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_ID).orEmpty()
        titleText = intent.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_TITLE) ?: "Scheduled reminder"
        contextText = intent.getStringExtra(ScheduledCallNotifications.EXTRA_CALL_CONTEXT).orEmpty()
        render()
        handler.post(ticker)
        setupTts()
        setSpeaker(true)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 28)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(3, 12, 27), Color.rgb(8, 35, 62), Color.rgb(2, 9, 20)))
        }
        root.addView(TextView(this).apply {
            text = "◉"; textSize = 76f; gravity = Gravity.CENTER; setTextColor(Color.rgb(74, 218, 255))
        }, LinearLayout.LayoutParams(-1, 112))
        root.addView(TextView(this).apply {
            text = "O X Y G E N"; textSize = 27f; letterSpacing = .2f
            gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "ACTIVE AI CALL"; textSize = 12f; letterSpacing = .18f
            gravity = Gravity.CENTER; setTextColor(Color.rgb(81, 218, 255))
            setPadding(0, 10, 0, 4)
        })
        timerView = TextView(this).apply {
            text = "00:00"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 20)
        }
        root.addView(timerView)
        root.addView(TextView(this).apply {
            text = titleText; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setPadding(8, 4, 8, 12)
        })
        statusView = TextView(this).apply {
            text = "OXYGEN is preparing your reminder…"; textSize = 14f
            gravity = Gravity.CENTER; setTextColor(Color.rgb(125, 208, 235))
            setPadding(8, 8, 8, 8)
        }
        root.addView(statusView)
        transcriptView = TextView(this).apply {
            text = "Your voice replies will appear here."; textSize = 14f
            gravity = Gravity.CENTER; setTextColor(Color.LTGRAY); setPadding(12, 14, 12, 22)
        }
        root.addView(transcriptView, LinearLayout.LayoutParams(-1, 0, 1f))
        val row1 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        muteButton = control("Mute") { toggleMute() }
        micButton = control("Mic") { toggleMic() }
        speakerButton = control("Speaker ON") { setSpeaker(!speakerOn) }
        row1.addView(muteButton, weightParams()); row1.addView(micButton, weightParams()); row1.addView(speakerButton, weightParams())
        root.addView(row1)
        val end = control("END CALL") { endCall() }.apply {
            background = rounded(Color.rgb(190, 45, 62)); textSize = 16f
        }
        root.addView(end, LinearLayout.LayoutParams(-1, 58).apply { topMargin = 22 })
        setContentView(root)
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, 54, 1f).apply { setMargins(5, 5, 5, 5) }
    private fun rounded(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = 28f }
    private fun control(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(Color.WHITE)
        background = rounded(Color.rgb(22, 75, 105)); setOnClickListener { action() }
    }

    private fun setupTts() {
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                var languageResult = engine.setLanguage(Locale.UK)
                if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    languageResult = engine.setLanguage(Locale.US)
                }
                ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { runOnUiThread { statusView.text = "OXYGEN is speaking…" } }
                    override fun onDone(id: String?) { runOnUiThread { statusView.text = "Your turn — tap Mic to reply"; startListeningIfAllowed() } }
                    override fun onError(id: String?) { runOnUiThread { statusView.text = "Tap Mic to speak to OXYGEN" } }
                })
                if (ttsReady) announceReminder() else statusView.text = "Speech voice unavailable. Tap Mic to reply."
            } else statusView.text = "Speech engine unavailable. Tap Mic to reply."
        }
    }

    private fun announceReminder() {
        val reason = if (contextText.isBlank()) "The reminder details were not included in the call data."
            else "You asked me to remind you about: $contextText."
        val line = "Hello. This is OXYGEN. I am calling about your reminder: $titleText. $reason You can speak after I finish."
        tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "oxygen-call-greeting")
    }

    private fun startListeningIfAllowed() {
        if (!muted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6112)
            statusView.text = "Allow microphone permission to reply"
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Speech recognition is unavailable on this device"; return
        }
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { sr ->
                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listening = true; statusView.text = "Listening… speak now" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false; statusView.text = "Processing your voice…" }
                    override fun onError(error: Int) { listening = false; if (!muted) statusView.text = "Tap Mic to try speaking again" }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val said = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (said.isNotBlank()) {
                            transcript = (transcript + "\nYou: " + said).takeLast(1600)
                            transcriptView.text = transcript
                            statusView.text = "Voice reply captured. Tap Mic to add another reply."
                            tts?.speak("I heard you. Your voice reply is ready to send to our OXYGEN chat when you end this call.", TextToSpeech.QUEUE_FLUSH, null, "oxygen-call-ack")
                        } else statusView.text = "I didn't catch that. Tap Mic to try again."
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                sr.startListening(request)
            }
        } catch (_: Exception) { statusView.text = "Could not start microphone. Tap Mic to retry." }
    }

    private fun toggleMic() {
        if (listening) {
            recognizer?.cancel(); listening = false; statusView.text = "Microphone paused"
            micButton.text = "Mic"
        } else {
            if (muted) { muted = false; muteButton.text = "Mute" }
            startListeningIfAllowed()
            micButton.text = "Stop Mic"
        }
    }

    private fun toggleMute() {
        muted = !muted
        muteButton.text = if (muted) "Unmute" else "Mute"
        if (muted) { recognizer?.cancel(); listening = false; statusView.text = "Microphone muted" }
        else { statusView.text = "Microphone unmuted — tap Mic to speak" }
    }

    @Suppress("DEPRECATION")
    private fun setSpeaker(enabled: Boolean) {
        speakerOn = enabled
        try { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION; audioManager.isSpeakerphoneOn = enabled } catch (_: Exception) {}
        speakerButton.text = if (enabled) "Speaker ON" else "Speaker OFF"
    }

    private fun endCall() {
        recognizer?.cancel()
        tts?.stop()
        handler.removeCallbacks(ticker)
        val extraContext = buildString {
            append(contextText)
            if (transcript.isNotBlank()) append("\nVoice conversation captured during call:\n").append(transcript)
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SCHEDULED_CALL_ID, callId)
            putExtra(MainActivity.EXTRA_SCHEDULED_CALL_TITLE, titleText)
            putExtra(MainActivity.EXTRA_SCHEDULED_CALL_CONTEXT, extraContext)
        })
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 6112 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else if (requestCode == 6112) statusView.text = "Microphone permission is required for voice replies"
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        try { audioManager.isSpeakerphoneOn = false; audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        super.onDestroy()
    }
}
