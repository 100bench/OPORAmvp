package com.screensaathi.sarvam

import android.util.Log
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/**
 * OpenAI speech-to-text. POSTs a WAV file to /audio/transcriptions and returns
 * its text plus detected language, or null so the caller can fall back cleanly.
 *
 * Latency budget: < 800 ms. Keep the recorded clip short.
 */
class SarvamStt {

    data class Result(val transcript: String, val languageCode: String?) {
        /** Prefer Russian and also infer it from Cyrillic if metadata is absent. */
        val language: String
            get() = Language.reconcile(transcript, languageCode ?: Language.APP_DEFAULT)
    }

    /** Blocking. Call off the main thread. */
    fun transcribe(wav: File): Result? {
        if (!Sarvam.isConfigured()) {
            Log.w(TAG, "No AI gateway configured — STT unavailable")
            return null
        }
        if (!wav.exists() || wav.length() == 0L) {
            Log.w(TAG, "Empty audio file")
            return null
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", Sarvam.STT_MODEL)
            // This build is Russian-first. The hint improves recognition and
            // prevents a short noisy utterance being classified as Hindi.
            .addFormDataPart("languages[]", "ru")
            .addFormDataPart("prompt", "Речь на русском языке о действиях на экране Android.")
            .addFormDataPart(
                "file", "audio.wav",
                wav.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .build()

        val req = Request.Builder()
            .url(Sarvam.STT_URL)
            .post(body)
            .build()

        return try {
            Sarvam.http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "STT ${resp.code}: $raw")
                    return null
                }
                val json = JSONObject(raw)
                val transcript = json.optString("text", "")
                if (transcript.isBlank()) return null
                // gpt-transcribe returns an ISO language code in `languages`.
                // Accept legacy fields too, which also makes this parser
                // tolerant of compatible gateways.
                val detected = (
                    json.optJSONArray("languages")?.optJSONObject(0)?.optString("code")
                        ?: json.optString("language", json.optString("language_code"))
                    )
                    .takeIf { it.isNotBlank() }
                    ?.let { Language.normalize(it) }
                Result(transcript, detected)
            }
        } catch (e: Exception) {
            Log.w(TAG, "STT failed (${e.javaClass.simpleName}): ${e.message}", e)
            null
        }
    }

    companion object { private const val TAG = "SarvamStt" }
}
