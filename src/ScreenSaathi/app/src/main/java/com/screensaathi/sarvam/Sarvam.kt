package com.screensaathi.sarvam

import com.screensaathi.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Shared OpenAI config + tuned OkHttp clients. The legacy object name is kept
 * to avoid a broad package/class rename. The APK talks to a local development
 * gateway; that gateway owns the OpenAI credential.
 */
object Sarvam {
    val BASE: String = BuildConfig.OPENAI_BASE_URL.trimEnd('/')
    val STT_URL: String = "$BASE/audio/transcriptions"
    val TTS_URL: String = "$BASE/audio/speech"
    val CHAT_URL: String = "$BASE/chat/completions"

    const val STT_MODEL = "gpt-transcribe"
    const val TTS_MODEL = "gpt-4o-mini-tts"
    const val PLANNER_MODEL = "gpt-6-luna"

    fun isConfigured(): Boolean = BASE.isNotBlank()

    // Tight timeouts: this is a live voice loop, not a batch job. A stalled
    // call must fail fast so the deterministic fallback can take over.
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * The planner gets a hard ceiling on the whole call, not just on socket
     * idle time.
     *
     * The step engine can answer instantly and for free, so anything beyond a
     * few seconds is worse than falling back — share the connection pool, but
     * cap the whole model call rather than only socket idle time.
     */
    val plannerHttp: OkHttpClient by lazy {
        http.newBuilder()
            .callTimeout(PLANNER_CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private const val PLANNER_CALL_TIMEOUT_S = 10L
}
