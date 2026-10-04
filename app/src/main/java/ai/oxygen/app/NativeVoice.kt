package ai.oxygen.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/**
 * OXYGEN native voice: Android's own speech recognizer and text-to-speech,
 * offered to the web page through the "OxygenNative" bridge.
 */
class NativeVoice(private val activity: Activity, private val web: WebView) {

    companion object {
        const val MIC_REQUEST = 4711
    }

    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pitch = 1.0f
    private var rate = 1.0f
    private val queue = ArrayDeque<Pair<String, String>>()
    private var speakToken = 0
    private var groupId = ""

    init {
        activity.runOnUiThread {
            tts = TextToSpeech(activity) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        activity.runOnUiThread { speakNext(utteranceId) }
                    }
                    override fun onError(utteranceId: String?) {
                        activity.runOnUiThread { speakNext(utteranceId) }
                    }
                })
            }
        }
    }

    private fun emit(type: String, data: String) {
        val js = "window.oxygenNativeOnEvent && window.oxygenNativeOnEvent(" +
            JSONObject.quote(type) + "," + JSONObject.quote(data) + ")"
        activity.runOnUiThread { web.evaluateJavascript(js, null) }
    }

    /* ---------- speaking ---------- */

    @JavascriptInterface
    fun hasLanguage(tag: String): Boolean {
        val engine = tts ?: return false
        if (!ttsReady) return false
        return engine.isLanguageAvailable(Locale.forLanguageTag(tag)) >= TextToSpeech.LANG_AVAILABLE
    }

    @JavascriptInterface
    fun speak(json: String, id: String) {
        activity.runOnUiThread {
            try {
                val o = JSONObject(json)
                pitch = o.optDouble("pitch", 1.0).toFloat()
                rate = o.optDouble("rate", 1.0).toFloat()
                val segs = o.getJSONArray("segments")
                speakToken++
                queue.clear()
                groupId = id
                for (i in 0 until segs.length()) {
                    val s = segs.getJSONObject(i)
                    queue.addLast(Pair(s.getString("lang"), s.getString("text")))
                }
                playNext()
            } catch (e: Exception) {
                emit("speechDone", id)
            }
        }
    }

    @JavascriptInterface
    fun stopSpeaking() {
        activity.runOnUiThread {
            speakToken++
            queue.clear()
            tts?.stop()
        }
    }

    private fun speakNext(utteranceId: String?) {
        val token = utteranceId?.substringBefore(":")?.toIntOrNull() ?: return
        if (token != speakToken) return
        playNext()
    }

    private fun playNext() {
        val engine = tts
        if (engine == null || !ttsReady) {
            emit("speechDone", groupId)
            return
        }
        val item = queue.removeFirstOrNull()
        if (item == null) {
            emit("speechDone", groupId)
            return
        }
        engine.setLanguage(Locale.forLanguageTag(item.first))
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
        tts?.stop()
        tts?.shutdown()
    }
}
