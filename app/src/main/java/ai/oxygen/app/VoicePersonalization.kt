package ai.oxygen.app

import android.content.Context
import org.json.JSONObject

/**
 * Device-local speech correction vocabulary.
 *
 * Stores text corrections only (never audio or voiceprints) in app-private SharedPreferences.
 * The same dictionary is exposed to the WebView and used by scheduled-call recognition.
 */
object VoicePersonalization {
    private const val PREFS = "oxygen_voice_personalization"
    private const val KEY = "corrections_v1"
    private const val MAX_ENTRIES = 100
    private const val MAX_PHRASE_LENGTH = 80

    @Synchronized
    fun getJson(context: Context): String = load(context).toString()

    @Synchronized
    fun save(context: Context, heard: String, intended: String): Boolean {
        val from = heard.trim().replace(Regex("\\s+"), " ")
        val to = intended.trim().replace(Regex("\\s+"), " ")
        if (from.isBlank() || to.isBlank() || from.equals(to, ignoreCase = true) ||
            from.length > MAX_PHRASE_LENGTH || to.length > MAX_PHRASE_LENGTH) return false

        val data = load(context)
        val keys = data.keys().asSequence().toList()
        keys.filter { it.equals(from, ignoreCase = true) }.forEach { data.remove(it) }
        if (data.length() >= MAX_ENTRIES) return false
        data.put(from, to)
        persist(context, data)
        return true
    }

    @Synchronized
    fun delete(context: Context, heard: String): Boolean {
        val data = load(context)
        val key = data.keys().asSequence().firstOrNull { it.equals(heard.trim(), ignoreCase = true) }
            ?: return false
        data.remove(key)
        persist(context, data)
        return true
    }

    @Synchronized
    fun apply(context: Context, transcript: String): String {
        if (transcript.isBlank()) return transcript
        val data = load(context)
        var corrected = transcript
        val keys = data.keys().asSequence().sortedByDescending { it.length }.toList()
        for (heard in keys) {
            val intended = data.optString(heard).takeIf { it.isNotBlank() } ?: continue
            val pattern = Regex(
                "(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(heard) + "(?![\\p{L}\\p{N}])"
            )
            corrected = pattern.replace(corrected) { match ->
                val replacement = if (match.value.firstOrNull()?.isUpperCase() == true) {
                    intended.replaceFirstChar { it.uppercase() }
                } else intended
                replacement
            }
        }
        return corrected
    }

    private fun load(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return try { if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw) }
        catch (_: Exception) { JSONObject() }
    }

    private fun persist(context: Context, data: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, data.toString()).apply()
    }
}
