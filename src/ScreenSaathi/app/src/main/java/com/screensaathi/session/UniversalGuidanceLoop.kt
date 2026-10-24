package com.screensaathi.session

import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerFailureKind
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.ScreenSanitizer
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.TargetResolver
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Device-local geometry resolved from the exact raw snapshot used for planning. */
data class ResolvedScenarioTarget(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val fingerprint: String,
    val snapshotIdentity: String,
    val resourceId: String?,
    val text: String?,
)

/** One reducer effect plus optional local-only geometry for ShowGuidance. */
data class UniversalGuidanceEmission(
    val effect: ScenarioEffect,
    val resolvedTarget: ResolvedScenarioTarget? = null,
)

internal fun shouldClearGuidanceHighlight(
    expectedFingerprint: String,
    observedFingerprint: String,
): Boolean = expectedFingerprint.isNotBlank() &&
    observedFingerprint.isNotBlank() &&
    expectedFingerprint != observedFingerprint

/**
 * App-agnostic orchestration for the MVP guidance loop.
 *
 * Android owns threads, rendering, speech and app launch. This class owns the
 * reducer event order and the privacy/safety boundary. It never performs a
 * device action and never sends a raw [ScreenSnapshot] to the planner.
 */
class UniversalGuidanceLoop(
    private val planner: GuidancePlanner,
    private val snapshotProvider: () -> ScreenSnapshot,
    private val clock: () -> Long = System::currentTimeMillis,
    private val emit: (UniversalGuidanceEmission) -> Unit,
    private val appLabelProvider: (String) -> String? = { null },
) {
    private val lock = Any()
    private val generation = AtomicLong(0)

    @Volatile
    private var state: ScenarioSession = ScenarioSession.idle(clock())

    private var pendingTarget: ResolvedScenarioTarget? = null

    val session: ScenarioSession
        get() = synchronized(lock) { state }

    fun startGoal(
        goal: String,
        targetPackage: String? = null,
        ambiguous: Boolean = false,
    ) {
        val token = generation.incrementAndGet()
        synchronized(lock) {
            state = ScenarioSession.idle(clock())
            pendingTarget = null
        }
        // The caller already supplied the recognized text. StartRequested is
        // still reduced to establish the phase, but its microphone effect is
        // suppressed; only an explicit Repeat action starts another capture.
        dispatch(ScenarioEvent.StartRequested, token, suppressRecognition = true)
        dispatch(ScenarioEvent.GoalRecognized(goal, targetPackage, ambiguous), token)
    }

    /** Restore only logical progress; live safety and target resolution rerun. */
    fun resume(capsule: ResumeCapsule) {
        val token = generation.incrementAndGet()
        synchronized(lock) {
            state = ScenarioSession.idle(clock())
            pendingTarget = null
        }
        dispatch(ScenarioEvent.ResumeRequested(capsule), token)
    }

    fun confirmRecognition() = dispatch(ScenarioEvent.RecognitionConfirmed, generation.get())

    fun repeatRecognition() = dispatch(ScenarioEvent.RepeatRecognition, generation.get())

    fun confirmGoal() {
        val token = generation.get()
        val expected = synchronized(lock) { pendingTarget?.snapshotIdentity }
        val current = snapshotProvider()
        when (assess(current).disposition) {
            ScreenDisposition.PAUSE_AUTH -> {
                dispatch(ScenarioEvent.AuthenticationRequired, token)
                return
            }
            ScreenDisposition.SAFE_HANDOFF -> {
                dispatch(ScenarioEvent.SafeHandoffRequired, token)
                return
            }
            ScreenDisposition.ALLOW -> Unit
        }
        val safe = ScreenSanitizer.sanitize(current)
        if (
            !current.settled ||
            safe.elements.isEmpty() ||
            expected.isNullOrBlank() ||
            current.localIdentity() != expected
        ) {
            dispatch(ScenarioEvent.CachedGuideInvalidated(current.signature()), token)
            return
        }
        dispatch(ScenarioEvent.GoalConfirmed, token)
    }

    fun cancelGoal() = dispatch(ScenarioEvent.GoalCancelled, generation.get())

    fun selectTargetApp(packageName: String) =
        dispatch(ScenarioEvent.TargetAppSelected(packageName), generation.get())

    fun targetTapped() = dispatch(ScenarioEvent.HighlightedTargetTapped, generation.get())

    fun physicalTapSettled(snapshot: ScreenSnapshot = snapshotProvider()) = dispatch(
        ScenarioEvent.PhysicalTapSettled(snapshot.signature()),
        generation.get(),
    )

    fun physicalTapTimedOut() = dispatch(ScenarioEvent.PhysicalTapTimedOut, generation.get())

    fun recognitionFailed() = dispatch(
        ScenarioEvent.GoalRecognized(goal = "", targetPackage = null, ambiguous = true),
        generation.get(),
    )

    fun observeScreen(snapshot: ScreenSnapshot = snapshotProvider()) {
        val token = generation.get()
        val current = session
        if (
            current.phase == ScenarioPhase.WAITING_FOR_APP &&
            current.targetPackage == snapshot.packageName &&
            snapshot.signature().isNotBlank()
        ) {
            dispatch(
                ScenarioEvent.TargetAppVisible(snapshot.packageName, snapshot.signature()),
                token,
            )
            return
        }
        dispatch(ScenarioEvent.ScreenObserved(snapshot.signature()), token)
    }

    fun continueAfterAuth() = dispatch(ScenarioEvent.ContinueRequested, generation.get())

    fun retry() {
        val token = generation.get()
        if (session.phase == ScenarioPhase.PAUSED_FOR_NETWORK) {
            val current = snapshotProvider()
            dispatch(
                ScenarioEvent.NetworkRecoveryRequested(
                    fingerprint = current.signature(),
                    automatic = false,
                ),
                token,
            )
        } else {
            dispatch(ScenarioEvent.RetryRequested, token)
        }
    }

    fun networkRestored(snapshot: ScreenSnapshot = snapshotProvider()) = dispatch(
        ScenarioEvent.NetworkRecoveryRequested(
            fingerprint = snapshot.signature(),
            automatic = true,
        ),
        generation.get(),
    )

    fun rephrase() = dispatch(ScenarioEvent.RephraseRequested, generation.get())

    fun restart() = dispatch(ScenarioEvent.RestartRequested, generation.get())

    fun ttsFailed() = dispatch(ScenarioEvent.TtsFailed, generation.get())

    fun stop() {
        val token = generation.incrementAndGet()
        val transition = synchronized(lock) {
            val next = ScenarioReducer.reduce(state, ScenarioEvent.StopRequested, clock())
            state = next.session
            pendingTarget = null
            next
        }
        transition.effects.forEach { execute(it, token) }
    }

    private fun dispatch(
        event: ScenarioEvent,
        token: Long,
        suppressRecognition: Boolean = false,
    ) {
        if (token != generation.get()) return
        val transition = synchronized(lock) {
            if (token != generation.get()) return
            val next = ScenarioReducer.reduce(state, event, clock())
            state = next.session
            next
        }
        transition.effects.forEach { effect ->
            if (
                token == generation.get() &&
                !(suppressRecognition && effect == ScenarioEffect.BeginRecognition)
            ) {
                execute(effect, token)
            }
        }
    }

    private fun execute(effect: ScenarioEffect, token: Long) {
        when (effect) {
            ScenarioEffect.AnalyzeCurrentScreen -> analyzeCurrentScreen(token, repairFormat = false)
            is ScenarioEffect.RetryPlanning -> analyzeCurrentScreen(token, effect.repairFormat)
            ScenarioEffect.ClearHighlight -> {
                synchronized(lock) { pendingTarget = null }
                emit(UniversalGuidanceEmission(effect))
            }
            is ScenarioEffect.ShowGuidance -> {
                val target = synchronized(lock) { pendingTarget }
                emit(UniversalGuidanceEmission(effect, target))
            }
            else -> emit(UniversalGuidanceEmission(effect))
        }
    }

    private fun analyzeCurrentScreen(
        token: Long,
        repairFormat: Boolean,
        staleReplans: Int = 0,
    ) {
        if (token != generation.get()) return
        val before = session
        if (before.phase != ScenarioPhase.ANALYZING_SCREEN) return

        val raw = snapshotProvider()
        val fingerprint = raw.signature()
        val snapshotIdentity = raw.localIdentity()
        if (fingerprint.isNotBlank() && fingerprint != before.screenFingerprint) {
            dispatch(ScenarioEvent.ScreenObserved(fingerprint), token)
        }

        val safety = assess(raw)
        when (safety.disposition) {
            ScreenDisposition.PAUSE_AUTH -> {
                dispatch(ScenarioEvent.AuthenticationRequired, token)
                return
            }
            ScreenDisposition.SAFE_HANDOFF -> {
                dispatch(ScenarioEvent.SafeHandoffRequired, token)
                return
            }
            ScreenDisposition.ALLOW -> Unit
        }

        val safeScreen = ScreenSanitizer.sanitize(raw)

        // An unreadable tree provides no evidence that it is safe to route
        // away from the current surface. Recover locally instead of offering
        // an app launch over a screen the gate could not inspect.
        if (!raw.settled || safeScreen.elements.isEmpty() || fingerprint.isBlank()) {
            dispatch(ScenarioEvent.TargetNotFound, token)
            return
        }

        val current = session
        val targetPackage = current.targetPackage
        if (!targetPackage.isNullOrBlank() && raw.packageName != targetPackage) {
            dispatch(ScenarioEvent.TargetAppRequired, token)
            return
        }

        val outcome = planner.planNext(
            screen = safeScreen,
            goal = current.goal,
            stepNumber = current.stepNumber + 1,
            lastSafeInstruction = current.lastSafeInstruction,
            repairFormat = repairFormat,
        )
        if (token != generation.get()) return

        // The provider response is valid only for the raw tree it was given.
        // Accessibility events can arrive while the blocking request is in
        // flight; never cache or render geometry from the old screen.
        val latest = snapshotProvider()
        val latestFingerprint = latest.signature()
        when (assess(latest).disposition) {
            ScreenDisposition.PAUSE_AUTH -> {
                dispatch(ScenarioEvent.AuthenticationRequired, token)
                return
            }
            ScreenDisposition.SAFE_HANDOFF -> {
                dispatch(ScenarioEvent.SafeHandoffRequired, token)
                return
            }
            ScreenDisposition.ALLOW -> Unit
        }
        val latestSafe = ScreenSanitizer.sanitize(latest)
        if (!latest.settled || latestSafe.elements.isEmpty() || latestFingerprint.isBlank()) {
            dispatch(ScenarioEvent.TargetNotFound, token)
            return
        }
        if (latest.localIdentity() != snapshotIdentity) {
            if (staleReplans >= MAX_STALE_REPLANS) {
                dispatch(ScenarioEvent.TargetNotFound, token)
            } else {
                if (latestFingerprint != fingerprint) {
                    dispatch(ScenarioEvent.ScreenObserved(latestFingerprint), token)
                }
                analyzeCurrentScreen(
                    token,
                    repairFormat = false,
                    staleReplans = staleReplans + 1,
                )
            }
            return
        }

        when (outcome) {
            is PlannerOutcome.Failure -> dispatch(
                ScenarioEvent.PlannerFailed(outcome.kind.toScenarioFailure()),
                token,
            )
            is PlannerOutcome.Success -> acceptPlan(outcome.plan, raw, token, repairFormat, staleReplans)
        }
    }

    private fun acceptPlan(
        plan: PlannerResult,
        raw: ScreenSnapshot,
        token: Long,
        repairFormat: Boolean,
        staleReplans: Int,
    ) {
        if (!SafetyGuard.isReadOnlyGuidanceAction(plan.actionType)) {
            dispatch(ScenarioEvent.PlannerFailed(ScenarioPlanningFailure.INVALID_RESPONSE), token)
            return
        }
        if (plan.isDone || plan.actionType == "answer") {
            dispatch(ScenarioEvent.PlannerAnswered(plan.instruction), token)
            return
        }

        val target = resolve(plan, raw)
        if (target == null) {
            if (repairFormat) {
                dispatch(ScenarioEvent.TargetNotFound, token)
            } else {
                // The model may invent a plausible ID for a real, unlabelled
                // control. Give it one chance to select an exact visible label
                // before telling the person to scroll a screen that is present.
                analyzeCurrentScreen(token, repairFormat = true, staleReplans = staleReplans)
            }
            return
        }
        val semanticTarget = runCatching {
            ScenarioTarget(
                resourceId = plan.targetResourceId.takeIf(String::isNotBlank),
                text = plan.targetText.takeIf(String::isNotBlank),
            )
        }.getOrNull()
        if (semanticTarget == null) {
            dispatch(ScenarioEvent.PlannerFailed(ScenarioPlanningFailure.INVALID_RESPONSE), token)
            return
        }

        synchronized(lock) { pendingTarget = target }
        dispatch(
            ScenarioEvent.PlannerGuided(
                ScenarioGuidance(plan.instruction, semanticTarget)
            ),
            token,
        )
    }

    private fun assess(snapshot: ScreenSnapshot): ScreenSafetyAssessment =
        ScreenSafetyGate.assess(
            snapshot = snapshot,
            appLabel = appLabelProvider(snapshot.packageName),
        )

    private fun resolve(plan: PlannerResult, raw: ScreenSnapshot): ResolvedScenarioTarget? {
        // Resolve only inside the same visible, non-password subset that could
        // have produced the provider-safe DTO. An invented id must never land
        // on a hidden raw node merely because it exists locally.
        val resolvable = raw.copy(
            elements = raw.elements.filter { it.visibleToUser && !it.password },
        )
        val safeElements = ScreenSanitizer.sanitize(resolvable).elements
        val actionableIds = safeElements.asSequence()
            .filter { it.enabled && (it.editable || it.clickable) }
            .mapNotNull { it.resourceId }
            .toSet()
        val bounds = plan.targetResourceId.takeIf { it in actionableIds }
            ?.let(resolvable::boundsForResourceId)
            ?: plan.targetText.takeIf(String::isNotBlank)?.let { text ->
                // Planner text is a label copied from the supplied tree, not
                // a natural-language search query. A prefix like "Госномер"
                // must not point at "Госномер не указан" on a VIN form.
                val exactVisibleLabel = safeElements.any { element ->
                    listOfNotNull(element.label, element.description)
                        .any { it.trim().equals(text.trim(), ignoreCase = true) }
                }
                if (!exactVisibleLabel) return@let null
                // Never let a filled EditText value participate in matching.
                // Only labels already present in the provider-safe DTO may
                // stand in for text while the raw geometry stays local.
                val labelsByIndex = safeElements.associateBy { it.index }
                val grounded = resolvable.copy(elements = resolvable.elements.map { element ->
                    val safe = labelsByIndex[element.index]
                    val label = listOfNotNull(safe?.label, safe?.description)
                        .firstOrNull { it.trim().equals(text.trim(), ignoreCase = true) }
                        ?: safe?.label.orEmpty()
                    element.copy(text = label, hintText = "", contentDescription = "")
                })
                (TargetResolver.resolve(text, grounded) as? TargetResolver.Result.Found)
                    ?.candidate
                    ?.bounds
            }
            ?: return null
        return ResolvedScenarioTarget(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            fingerprint = raw.signature(),
            snapshotIdentity = raw.localIdentity(),
            resourceId = plan.targetResourceId.takeIf(String::isNotBlank),
            text = plan.targetText.takeIf(String::isNotBlank),
        )
    }

    private fun PlannerFailureKind.toScenarioFailure(): ScenarioPlanningFailure = when (this) {
        PlannerFailureKind.INVALID_RESPONSE -> ScenarioPlanningFailure.INVALID_RESPONSE
        PlannerFailureKind.NETWORK -> ScenarioPlanningFailure.NETWORK
        PlannerFailureKind.REMOTE -> ScenarioPlanningFailure.REMOTE
        PlannerFailureKind.CONFIGURATION -> ScenarioPlanningFailure.CONFIGURATION
    }

    /** Strong, device-local freshness identity. Raw values are hashed and never retained or logged. */
    private fun ScreenSnapshot.localIdentity(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) {
            digest.update(value.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }

        add(packageName)
        add(settled)
        elements.forEach { element ->
            add(element.index)
            add(element.resourceId)
            add(element.text)
            add(element.className)
            add(element.bounds.left)
            add(element.bounds.top)
            add(element.bounds.right)
            add(element.bounds.bottom)
            add(element.editable)
            add(element.clickable)
            add(element.contentDescription)
            add(element.hintText)
            add(element.enabled)
            add(element.selected)
            add(element.checked)
            add(element.scrollable)
            add(element.password)
            add(element.inputType)
            add(element.visibleToUser)
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val MAX_STALE_REPLANS = 1
    }
}
