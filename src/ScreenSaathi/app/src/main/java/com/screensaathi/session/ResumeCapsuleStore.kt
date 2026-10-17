package com.screensaathi.session

import java.nio.charset.StandardCharsets
import java.util.Base64

interface ResumeCapsuleStore {
    fun save(session: ScenarioSession)
    fun load(): ResumeCapsule?
    fun delete()

    companion object {
        const val MAX_AGE_MS = 30L * 60L * 1_000L
    }
}

interface ResumeCapsuleValues {
    fun read(): String?
    fun write(value: String)
    fun delete()
}

data class SealedResumeCapsule(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface ResumeCapsuleCipher {
    fun seal(plaintext: ByteArray): SealedResumeCapsule?
    fun open(sealed: SealedResumeCapsule): ByteArray?
}

/** JVM-testable lifecycle around key/value and authenticated-cipher ports. */
class DefaultResumeCapsuleStore(
    private val values: ResumeCapsuleValues,
    private val cipher: ResumeCapsuleCipher,
    private val wallClock: () -> Long,
    private val elapsedClock: () -> Long,
) : ResumeCapsuleStore {

    override fun save(session: ScenarioSession) {
        val stored = runCatching {
            val capsule = ResumeCapsule.fromSession(
                session = session,
                savedWallTimeMs = wallClock(),
                savedElapsedRealtimeMs = elapsedClock(),
            )
            val plaintext = ResumeCapsuleCodec.encode(capsule)
                .toByteArray(StandardCharsets.UTF_8)
            val sealed = cipher.seal(plaintext) ?: return@runCatching null
            encodeEnvelope(sealed)
        }.getOrNull()

        if (stored == null || runCatching { values.write(stored) }.isFailure) {
            delete()
        }
    }

    override fun load(): ResumeCapsule? {
        val read = runCatching { values.read() }
        if (read.isFailure) {
            delete()
            return null
        }
        val stored = read.getOrNull() ?: return null
        val capsule = runCatching {
            val sealed = decodeEnvelope(stored) ?: return@runCatching null
            val plaintext = cipher.open(sealed) ?: return@runCatching null
            ResumeCapsuleCodec.decode(String(plaintext, StandardCharsets.UTF_8))
        }.getOrNull()

        val nowElapsed = elapsedClock()
        val valid = capsule?.takeIf {
            nowElapsed >= it.savedElapsedRealtimeMs &&
                nowElapsed - it.savedElapsedRealtimeMs < ResumeCapsuleStore.MAX_AGE_MS
        }
        if (valid == null) delete()
        return valid
    }

    override fun delete() {
        runCatching { values.delete() }
    }

    private fun encodeEnvelope(sealed: SealedResumeCapsule): String {
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(sealed.iv) + "." +
            encoder.encodeToString(sealed.ciphertext)
    }

    private fun decodeEnvelope(stored: String): SealedResumeCapsule? = runCatching {
        val parts = stored.split('.')
        if (parts.size != 2 || parts.any(String::isBlank)) return null
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(parts[0])
        val ciphertext = decoder.decode(parts[1])
        if (iv.isEmpty() || ciphertext.isEmpty()) return null
        SealedResumeCapsule(iv, ciphertext)
    }.getOrNull()
}
