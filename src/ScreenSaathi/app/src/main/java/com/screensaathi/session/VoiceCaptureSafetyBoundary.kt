package com.screensaathi.session

import java.util.concurrent.atomic.AtomicLong

internal enum class VoiceCapturePhase {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    PROCESSING,
}

internal data class VoiceCaptureToken(val generation: Long)

internal data class ActiveVoiceCapture(
    val token: VoiceCaptureToken,
    val turn: Int,
    val listening: Boolean,
)

internal sealed interface VoiceCaptureStart {
    data class Started(val capture: ActiveVoiceCapture) : VoiceCaptureStart
    data class Blocked(val assessment: ScreenSafetyAssessment) : VoiceCaptureStart
}

/**
 * Synchronous local gate in front of microphone capture.
 *
 * The callback form makes the ordering structural: on a protected screen the
 * capture callback cannot run. [abortIfUnsafe] is used again on every screen
 * transition so an already active capture is stopped and deleted before any
 * recognition or speech work can be scheduled.
 */
internal class VoiceCaptureSafetyBoundary(
    private val assessmentProvider: () -> ScreenSafetyAssessment?,
) {
    private val generation = AtomicLong(0)
    private val stateLock = Any()

    @Volatile
    private var activeGeneration = 0L

    @Volatile
    var phase: VoiceCapturePhase = VoiceCapturePhase.IDLE
        private set

    fun beginCapture(): VoiceCaptureToken = synchronized(stateLock) {
        val next = generation.incrementAndGet()
        activeGeneration = next
        phase = VoiceCapturePhase.STARTING
        VoiceCaptureToken(next)
    }

    fun markRecording(token: VoiceCaptureToken): Boolean =
        transition(token, setOf(VoiceCapturePhase.STARTING), VoiceCapturePhase.RECORDING)

    fun beginStopping(token: VoiceCaptureToken): Boolean =
        transition(
            token,
            setOf(VoiceCapturePhase.STARTING, VoiceCapturePhase.RECORDING),
            VoiceCapturePhase.STOPPING,
        )

    fun markProcessing(token: VoiceCaptureToken): Boolean =
        transition(token, setOf(VoiceCapturePhase.STOPPING), VoiceCapturePhase.PROCESSING)

    fun finish(token: VoiceCaptureToken): Boolean =
        transition(token, setOf(VoiceCapturePhase.PROCESSING), VoiceCapturePhase.IDLE)

    /**
     * Invalidate only when [token] still owns the physical capture state.
     *
     * Recognition can finish after the user has already started a newer
     * recording. An old callback must not tear down that newer capture.
     */
    fun invalidateCapture(token: VoiceCaptureToken): Boolean = synchronized(stateLock) {
        if (token.generation != activeGeneration) return@synchronized false
        activeGeneration = generation.incrementAndGet()
        phase = VoiceCapturePhase.IDLE
        true
    }

    fun invalidateCapture() = synchronized(stateLock) {
        activeGeneration = generation.incrementAndGet()
        phase = VoiceCapturePhase.IDLE
    }

    fun isCurrent(token: VoiceCaptureToken, vararg allowed: VoiceCapturePhase): Boolean =
        synchronized(stateLock) {
            token.generation == activeGeneration &&
                (allowed.isEmpty() || phase in allowed)
        }

    fun hasActiveCapture(): Boolean = phase != VoiceCapturePhase.IDLE

    private fun transition(
        token: VoiceCaptureToken,
        expected: Set<VoiceCapturePhase>,
        next: VoiceCapturePhase,
    ): Boolean = synchronized(stateLock) {
        if (token.generation != activeGeneration || phase !in expected) return@synchronized false
        phase = next
        true
    }

    fun unsafeAssessment(): ScreenSafetyAssessment? =
        assessmentProvider()?.takeUnless { it.disposition == ScreenDisposition.ALLOW }

    fun runIfAllowed(startCapture: () -> Unit): ScreenSafetyAssessment? {
        val unsafe = unsafeAssessment()
        if (unsafe == null) startCapture()
        return unsafe
    }

    fun abortIfUnsafe(
        stopCapture: () -> Unit,
        deleteCapture: () -> Unit,
    ): ScreenSafetyAssessment? {
        val unsafe = unsafeAssessment() ?: return null
        stopCapture()
        deleteCapture()
        return unsafe
    }
}

/**
 * Publishes the controller-facing capture state as one atomic value.
 *
 * The phase boundary protects the recorder/network pipeline; this owner keeps
 * the token, turn and mic-toggle flag from being observed or overwritten in
 * different generations. A protected-screen callback and a start continuation
 * therefore have one serial order instead of interleaving field assignments.
 */
internal class VoiceCaptureState(
    private val boundary: VoiceCaptureSafetyBoundary,
) {
    private val lock = Any()

    @Volatile
    private var active: ActiveVoiceCapture? = null

    fun begin(turn: Int): VoiceCaptureStart = synchronized(lock) {
        boundary.unsafeAssessment()?.let { return@synchronized VoiceCaptureStart.Blocked(it) }
        val capture = ActiveVoiceCapture(
            token = boundary.beginCapture(),
            turn = turn,
            listening = true,
        )
        active = capture
        VoiceCaptureStart.Started(capture)
    }

    fun current(): ActiveVoiceCapture? = active

    fun isListening(): Boolean = active?.listening == true

    fun isListening(turn: Int): Boolean = active?.let { it.listening && it.turn == turn } == true

    fun isCurrent(token: VoiceCaptureToken, vararg phases: VoiceCapturePhase): Boolean =
        synchronized(lock) {
            active?.token == token && boundary.isCurrent(token, *phases)
        }

    fun markRecording(token: VoiceCaptureToken): Boolean = synchronized(lock) {
        active?.token == token && boundary.markRecording(token)
    }

    fun beginStopping(): ActiveVoiceCapture? = synchronized(lock) {
        val capture = active ?: return@synchronized null
        if (!boundary.beginStopping(capture.token)) return@synchronized null
        capture.copy(listening = false).also { active = it }
    }

    fun markProcessing(token: VoiceCaptureToken): Boolean = synchronized(lock) {
        active?.token == token && boundary.markProcessing(token)
    }

    fun finishIfOwned(token: VoiceCaptureToken): ActiveVoiceCapture? = synchronized(lock) {
        val capture = active?.takeIf { it.token == token } ?: return@synchronized null
        if (!boundary.finish(token)) return@synchronized null
        active = null
        capture
    }

    fun invalidateIfOwned(token: VoiceCaptureToken): ActiveVoiceCapture? = synchronized(lock) {
        val capture = active?.takeIf { it.token == token } ?: return@synchronized null
        if (!boundary.invalidateCapture(token)) return@synchronized null
        active = null
        capture
    }

    fun invalidateAll(): ActiveVoiceCapture? = synchronized(lock) {
        val capture = active
        boundary.invalidateCapture()
        active = null
        capture
    }
}
