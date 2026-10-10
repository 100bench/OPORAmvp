package com.screensaathi.sarvam

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * OpenAI text-to-speech. POSTs JSON to /audio/speech and receives raw WAV
 * bytes ready for the existing AudioPlayer.
 *
 * Latency budget: < 900 ms to first audio. Keep instructions short.
 */
class SarvamTts {

    /**
     * Blocking. Call off the main thread. Returns WAV bytes ready for playback.
     *
     * Takes a [Spoken] rather than a loose text+code pair so the voice
     * instruction can preserve the language selected by the planner.
     */
    fun synthesize(spoken: Spoken, voice: String = DEFAULT_VOICE): ByteArray? {
        if (!Sarvam.isConfigured()) {
            Log.w(TAG, "No AI gateway configured — TTS unavailable")
            return null
        }
        if (spoken.text.isBlank()) return null
        val languageCode = Language.reconcile(spoken.text, spoken.language)
        if (languageCode != spoken.language) {
            Log.w(TAG, "Language '${spoken.language}' does not match the text; sending $languageCode")
        }

        val payload = JSONObject()
            .put("input", spoken.text)
            .put("voice", voice)
            .put("model", Sarvam.TTS_MODEL)
            .put(
                "instructions",
                "Говори естественно, спокойно и разборчиво на русском языке. " +
                    "Не используй иностранный акцент. Язык текста: ${Language.nativeName(languageCode)}.",
            )
            .put("response_format", "wav")
            .toString()

        val req = Request.Builder()
            .url(Sarvam.TTS_URL)
            .post(payload.toRequestBody(JSON))
            .build()

        return try {
            Sarvam.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    Log.w(TAG, "TTS ${resp.code}: $raw")
                    return null
                }
                resp.body?.bytes()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS failed (${e.javaClass.simpleName}): ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "SarvamTts"
        private const val DEFAULT_VOICE = "marin"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
