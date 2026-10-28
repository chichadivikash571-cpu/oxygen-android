package ai.oxygen.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * OXYGEN native voice: Android's own speech recognizer and text-to-speech,
 * offered to the web page through the "OxygenNative" bridge.
 *
 * Speaking keeps one prepared speech engine per language (Telugu, English, ...), so switching
 * between languages never makes Android reload a voice in the middle of a reply.
 */
class NativeVoice(private val activity: Activity, private val web: WebView) {

    companion object {
        const val MIC_REQUEST = 4711
    }

    private class Voice {
        var engine: TextToSpeech? = null
        @Volatile var ready = false
        @Volatile var failed = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val voices = ConcurrentHashMap<String, Voice>()
    private val queue = ArrayDeque<Pair<String, String>>()
    private var pitch = 1.0f
    private var rate = 1.0f
    private var speakToken = 0
    private var groupId = ""

    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            activity.runOnUiThread { speakNext(utteranceId) }
        }
        override fun onError(utteranceId: String?) {
            activity.runOnUiThread { speakNext(utteranceId) }
        }
    }

    init {
        activity.runOnUiThread {
            prepare("te-IN")
            prepare("en-GB")
        }
    }

    private fun emit(type: String, data: String) {
        val js = "window.oxygenNativeOnEvent && window.oxygenNativeOnEvent(" +
            JSONObject.quote(type) + "," + JSONObject.quote(data) + ")"
        activity.runOnUiThread { web.evaluateJavascript(js, null) }
    }

    /* ---------- speaking ---------- */

    private fun prepare(tag: String): Voice {
        voices[tag]?.let { return it }
        val v = Voice()
        voices[tag] = v
        v.engine = TextToSpeech(activity) { status ->
            val e = v.engine
            if (status != TextToSpeech.SUCCESS || e == null) {
                v.failed = true
                return@TextToSpeech
            }

            // Explicit speech audio attributes help the assistant route speech to the normal
            // media/accessibility output instead of depending on a device-specific TTS default.
            try {
                e.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (_: Exception) {
            }

            // Use the requested locale first, then a known English voice if the device has no
            // installed voice data for that locale. Previously this silently skipped every
            // segment when (for example) en-IN or te-IN voice data was missing.
            val requested = Locale.forLanguageTag(tag)
            var result = e.setLanguage(requested)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = e.setLanguage(Locale.UK)
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = e.setLanguage(Locale.US)
            }
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                v.failed = true
            } else {
                e.setOnUtteranceProgressListener(progress)
                v.ready = true
            }
        }
        return v
    }

    @JavascriptInterface
    fun hasLanguage(tag: String): Boolean {
        val any = voices.values.firstOrNull { it.ready && it.engine != null }?.engine ?: return false
        return any.isLanguageAvailable(Locale.forLanguageTag(tag)) >= TextToSpeech.LANG_AVAILABLE
    }

    @JavascriptInterface
    fun speak(json: String, id: String) {
        activity.runOnUiThread {
            try {
                val o = JSONObject(json)
                pitch = o.optDouble("pitch", 1.0).toFloat()
                rate = o.optDouble("rate", 1.0).toFloat()
                val segs = o.getJSONArray("segments")
                stopAll()
                groupId = id

                // Join neighbouring pieces in the same language into one utterance: fewer pauses.
                var curLang: String? = null
                val sb = StringBuilder()
                fun flush() {
                    val lang = curLang
                    if (lang != null && sb.isNotBlank()) queue.addLast(Pair(lang, sb.toString().trim()))
                    sb.setLength(0)
                }
                for (i in 0 until segs.length()) {
                    val s = segs.getJSONObject(i)
                    val lang = s.getString("lang")
                    val text = s.getString("text")
                    if (lang != curLang || sb.length + text.length > 600) {
                        flush()
                        curLang = lang
                    }
                    sb.append(text).append(' ')
                }
                flush()

                playNext()
            } catch (e: Exception) {
                emit("speechDone", id)
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        activity.runOnUiThread { stopAll() }
    }

    private fun stopAll() {
        speakToken++
        queue.clear()
        voices.values.forEach { it.engine?.stop() }
    }

    private fun speakNext(utteranceId: String?) {
        val token = utteranceId?.substringBefore(":")?.toIntOrNull() ?: return
        if (token != speakToken) return
        playNext()
    }

    private fun playNext(tries: Int = 0) {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            emit("speechDone", groupId)
            return
        }
        val v = voices[item.first] ?: prepare(item.first)
        if (v.failed) {
            playNext()
            return
        }
        val engine = v.engine
        if (engine == null || !v.ready) {
            // This voice is still starting up: wait a moment (about 10 seconds at most), then try again.
            if (tries > 40) {
                playNext()
                return
            }
            queue.addFirst(item)
            val token = speakToken
            handler.postDelayed({ if (token == speakToken) playNext(tries + 1) }, 250)
            return
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
        val result = engine.speak(item.second, TextToSpeech.QUEUE_FLUSH, null, "$speakToken:${queue.size}")
        if (result == TextToSpeech.ERROR) playNext()
    }

    /* ---------- listening ---------- */

    @JavascriptInterface
    fun listen(lang: String) {
        activity.runOnUiThread {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingLang = lang
                activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            } else {
                startRecognizer(lang)
            }
        }
    }

    @JavascriptInterface
    fun stopListening() {
        activity.runOnUiThread { recognizer?.stopListening() }
    }

    @JavascriptInterface
    fun cancelListening() {
        activity.runOnUiThread {
            recognizer?.cancel()
            emit("error", "aborted")
            emit("end", "")
        }
    }

    fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode != MIC_REQUEST) return
        val lang = pendingLang
        pendingLang = null
        if (granted && lang != null) {
            startRecognizer(lang)
        } else {
            emit("error", "not-allowed")
            emit("end", "")
        }
    }

    private fun startRecognizer(lang: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            emit("error", "service-not-allowed")
            emit("end", "")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(activity)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { emit("start", "") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                emit("error", errorName(error))
                emit("end", "")
            }
            override fun onResults(results: Bundle?) {
                emit("result", firstResult(results))
                emit("end", "")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = firstResult(partialResults)
                if (t.isNotEmpty()) emit("partial", t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(intent)
    }

    private fun firstResult(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
        SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "network"
        SpeechRecognizer.ERROR_CLIENT -> "aborted"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
        12, 13 -> "language-not-supported"
        else -> "error-$code"
    }

    fun destroy() {
        recognizer?.destroy()
        voices.values.forEach {
            it.engine?.stop()
            it.engine?.shutdown()
        }
        voices.clear()
    }
}
