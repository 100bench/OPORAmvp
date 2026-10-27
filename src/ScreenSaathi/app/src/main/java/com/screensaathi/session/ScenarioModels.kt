package com.screensaathi.session

enum class ScenarioMode {
    ONE_SHOT,
    GUIDANCE,
    SAFE_HANDOFF,
}

enum class ScenarioPhase {
    IDLE,
    RECOGNIZING_GOAL,
    CONFIRMING_GOAL,
    WAITING_FOR_APP,
    ANALYZING_SCREEN,
    GUIDING_STEP,
    WAITING_FOR_SCREEN_CHANGE,
    WAITING_FOR_MANUAL_SCROLL,
    WAITING_FOR_MODAL_DISMISS,
    PAUSED_FOR_AUTH,
    PAUSED_FOR_NETWORK,
    SAFE_HANDOFF,
    COMPLETED,
    FAILED,
}

/** Provider-neutral semantic target. It cannot carry coordinates or actions. */
data class ScenarioTarget(
    val resourceId: String? = null,
    val text: String? = null,
) {
    init {
        require(!resourceId.isNullOrBlank() || !text.isNullOrBlank()) {
            "A scenario target needs a resource id or visible text"
        }
    }
}

data class ScenarioGuidance(
    val instruction: String,
    val target: ScenarioTarget,
) {
    init {
        require(instruction.isNotBlank()) { "Guidance instruction cannot be blank" }
    }
}

/**
 * Immutable universal-session state. [screenFingerprint] and
 * [currentGuidance], [networkAutoRetries] and [preserveReplanFailures] are
 * transient, device-local coordination fields and must not be written to the
 * later ResumeCapsule.
 */
data class ScenarioSession(
    val goal: String,
    val targetPackage: String?,
    val mode: ScenarioMode,
    val phase: ScenarioPhase,
    val stepNumber: Int,
    val lastSafeInstruction: String?,
    val scrollAttempts: Int,
    val replanFailures: Int,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val screenFingerprint: String? = null,
    val currentGuidance: ScenarioGuidance? = null,
    val networkAutoRetries: Int = 0,
    val preserveReplanFailures: Boolean = false,
) {
    companion object {
        fun idle(nowMs: Long): ScenarioSession = ScenarioSession(
            goal = "",
            targetPackage = null,
            mode = ScenarioMode.ONE_SHOT,
            phase = ScenarioPhase.IDLE,
            stepNumber = 0,
            lastSafeInstruction = null,
            scrollAttempts = 0,
            replanFailures = 0,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
    }
}

enum class ScenarioPlanningFailure {
    INVALID_RESPONSE,
    NETWORK,
    REMOTE,
    CONFIGURATION,
}

enum class ScenarioPlanningAttempt { PRIMARY, FORMAT_REPAIR }

enum class ScenarioTargetIssue { NOT_VISIBLE, AMBIGUOUS, NOT_ACTIONABLE, NOT_FOUND }

enum class ScenarioFailureReason {
    SCROLL_LIMIT,
    INVALID_PLANNER_RESPONSE,
    REMOTE_PLANNER_FAILURE,
    CONFIGURATION,
}

enum class ScenarioNotice {
    VERIFY_RECOGNITION,
    AUTHENTICATION,
    NO_NETWORK,
    SAFE_HANDOFF,
    TTS_UNAVAILABLE,
}

enum class ScenarioChoice {
    CONFIRM_RECOGNITION,
    REPEAT_RECOGNITION,
    START,
    CANCEL,
    CONTINUE,
    RETRY,
    REPHRASE,
    RESTART,
    STOP,
}

sealed interface ScenarioEvent {
    object StartRequested : ScenarioEvent
    data class ResumeRequested(val capsule: ResumeCapsule) : ScenarioEvent

    data class GoalRecognized(
        val goal: String,
        val targetPackage: String?,
        val ambiguous: Boolean,
    ) : ScenarioEvent

    object RecognitionConfirmed : ScenarioEvent
    object RepeatRecognition : ScenarioEvent
    object TargetAppRequired : ScenarioEvent
    data class TargetAppSelected(val packageName: String) : ScenarioEvent
    data class TargetAppVisible(val packageName: String, val fingerprint: String) : ScenarioEvent
    data class PlannerAnswered(val instruction: String) : ScenarioEvent
    data class PlannerGuided(val guidance: ScenarioGuidance) : ScenarioEvent
    object GoalConfirmed : ScenarioEvent
    object GoalCancelled : ScenarioEvent
    object HighlightedTargetTapped : ScenarioEvent
    data class ScreenObserved(val fingerprint: String) : ScenarioEvent
    data class PhysicalTapSettled(val fingerprint: String) : ScenarioEvent
    object PhysicalTapTimedOut : ScenarioEvent
    data class CachedGuideInvalidated(val fingerprint: String) : ScenarioEvent
    object TargetNotFound : ScenarioEvent
    data class TargetUnavailable(val issue: ScenarioTargetIssue) : ScenarioEvent
    data class ModalCloseAvailable(val guidance: ScenarioGuidance) : ScenarioEvent
    object ManualDismissRequired : ScenarioEvent
    object AuthenticationRequired : ScenarioEvent
    object NetworkUnavailable : ScenarioEvent
    data class NetworkRecoveryRequested(
        val fingerprint: String,
        val automatic: Boolean = false,
    ) : ScenarioEvent
    object SafeHandoffRequired : ScenarioEvent
    data class PlannerFailed(val kind: ScenarioPlanningFailure) : ScenarioEvent
    object TtsFailed : ScenarioEvent
    object ContinueRequested : ScenarioEvent
    object RetryRequested : ScenarioEvent
    object GoalCompleted : ScenarioEvent
    object RephraseRequested : ScenarioEvent
    object RestartRequested : ScenarioEvent
    object StopRequested : ScenarioEvent
}

sealed interface ScenarioEffect {
    object BeginRecognition : ScenarioEffect
    object AnalyzeCurrentScreen : ScenarioEffect
    object ClearHighlight : ScenarioEffect
    object CancelPendingWork : ScenarioEffect
    object StartNetworkMonitoring : ScenarioEffect
    object StopNetworkMonitoring : ScenarioEffect
    object StopSpeech : ScenarioEffect
    object SaveResumeCapsule : ScenarioEffect
    object DeleteResumeCapsule : ScenarioEffect
    object ShowIdle : ScenarioEffect
    object FinishScenario : ScenarioEffect
    object RequestManualScroll : ScenarioEffect
    data class ShowTargetIssue(val issue: ScenarioTargetIssue) : ScenarioEffect
    object RequestManualModalDismiss : ScenarioEffect
    object RecheckModalAfterSettling : ScenarioEffect

    data class ShowDecision(
        val notice: ScenarioNotice,
        val choices: List<ScenarioChoice>,
        val recognizedGoal: String? = null,
    ) : ScenarioEffect

    data class ConfirmGoal(val goal: String) : ScenarioEffect
    data class ShowTargetAppChoice(val packageName: String) : ScenarioEffect
    data class LaunchTargetApp(val packageName: String) : ScenarioEffect
    data class ShowAnswer(val instruction: String) : ScenarioEffect
    data class ShowGuidance(val guidance: ScenarioGuidance) : ScenarioEffect
    data class SpeakText(val text: String) : ScenarioEffect
    data class ShowSafeHandoff(val notice: ScenarioNotice = ScenarioNotice.SAFE_HANDOFF) : ScenarioEffect
    data class RetryPlanning(val attempt: ScenarioPlanningAttempt) : ScenarioEffect
    data class ShowRecoverableFailure(
        val reason: ScenarioFailureReason,
        val choices: List<ScenarioChoice>,
    ) : ScenarioEffect
}

data class ScenarioTransition(
    val session: ScenarioSession,
    val effects: List<ScenarioEffect> = emptyList(),
)
