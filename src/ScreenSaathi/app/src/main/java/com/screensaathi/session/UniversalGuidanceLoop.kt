package com.screensaathi.session

import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerFailureKind
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.ScreenSanitizer
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.TargetResolver
import com.screensaathi.screen.ModalDismissResolver
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
    private var modalSettleChecks = 0
    private data class PlanningAttempt(
        val fingerprint: String,
        val kind: ScenarioPlanningAttempt,
    )
    // Only a local freshness hash and request kind; never cache raw UI content.
    private var inFlightAttempt: PlanningAttempt? = null

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
            modalSettleChecks = 0
            inFlightAttempt = null
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
            modalSettleChecks = 0
            inFlightAttempt = null
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

    /** Delayed local retry for a dialog whose Accessibility tree was still changing. */
    fun recheckCurrentScreen() = analyzeCurrentScreen(generation.get())

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
            modalSettleChecks = 0
            inFlightAttempt = null
            next
        }
        transition.effects.forEach { execute(it, token) }
    }

    private fun dispatch(
        event: ScenarioEvent,
        token: Long,
        suppressRecognition: Boolean = false,
        staleReplans: Int = 0,
        expectedAttempt: PlanningAttempt? = null,
        targetToCommit: ResolvedScenarioTarget? = null,
    ) {
        if (token != generation.get()) return
        val transition = synchronized(lock) {
            if (token != generation.get()) return
            if (expectedAttempt != null &&
                (inFlightAttempt !== expectedAttempt || state.phase != ScenarioPhase.ANALYZING_SCREEN)) return
            if (targetToCommit != null && state.phase != ScenarioPhase.ANALYZING_SCREEN) return
            val next = ScenarioReducer.reduce(state, event, clock())
            if (targetToCommit != null) pendingTarget = targetToCommit
            state = next.session
            next
        }
        transition.effects.forEach { effect ->
            if (
                token == generation.get() &&
                !(suppressRecognition && effect == ScenarioEffect.BeginRecognition)
            ) {
                execute(effect, token, staleReplans, expectedAttempt)
            }
        }
    }

    private fun execute(
        effect: ScenarioEffect,
        token: Long,
        staleReplans: Int = 0,
        expectedAttempt: PlanningAttempt? = null,
    ) {
        if (token != generation.get()) return
        if (expectedAttempt != null && synchronized(lock) { inFlightAttempt !== expectedAttempt }) return
        when (effect) {
            ScenarioEffect.AnalyzeCurrentScreen -> analyzeCurrentScreen(token, predecessor = expectedAttempt)
            is ScenarioEffect.RetryPlanning -> analyzeCurrentScreen(
                token, effect.attempt, staleReplans, predecessor = expectedAttempt,
            )
            ScenarioEffect.ClearHighlight -> {
                synchronized(lock) {
                    if (token != generation.get() ||
                        (expectedAttempt != null && inFlightAttempt !== expectedAttempt)) return
                    pendingTarget = null
                }
                emit(UniversalGuidanceEmission(effect))
            }
            is ScenarioEffect.ShowGuidance -> {
                val target = synchronized(lock) {
                    if (token != generation.get() ||
                        (expectedAttempt != null && inFlightAttempt !== expectedAttempt)) return
                    pendingTarget
                }
                emit(UniversalGuidanceEmission(effect, target))
            }
            else -> emit(UniversalGuidanceEmission(effect))
        }
    }

    private fun analyzeCurrentScreen(
        token: Long,
        attemptKind: ScenarioPlanningAttempt = ScenarioPlanningAttempt.PRIMARY,
        staleReplans: Int = 0,
        predecessor: PlanningAttempt? = null,
    ) {
        if (token != generation.get()) return
        val before = synchronized(lock) {
            if (predecessor != null && inFlightAttempt !== predecessor) return
            state
        }
        if (before.phase != ScenarioPhase.ANALYZING_SCREEN) return

        val raw = snapshotProvider()
        val fingerprint = raw.signature()
        val snapshotIdentity = raw.localIdentity()
        if (fingerprint.isNotBlank() && fingerprint != before.screenFingerprint) {
            dispatch(ScenarioEvent.ScreenObserved(fingerprint), token, expectedAttempt = predecessor)
        }

        val safety = assess(raw)
        when (safety.disposition) {
            ScreenDisposition.PAUSE_AUTH -> {
                dispatch(ScenarioEvent.AuthenticationRequired, token, expectedAttempt = predecessor)
                return
            }
            ScreenDisposition.SAFE_HANDOFF -> {
                dispatch(ScenarioEvent.SafeHandoffRequired, token, expectedAttempt = predecessor)
                return
            }
            ScreenDisposition.ALLOW -> Unit
        }

        if (raw.activeDialog) {
            if (!raw.settled) {
                waitForModalSettle(token, predecessor)
                return
            }
            val fresh = snapshotProvider()
            if (token != generation.get()) return
            when (assess(fresh).disposition) {
                ScreenDisposition.PAUSE_AUTH -> {
                    dispatch(ScenarioEvent.AuthenticationRequired, token, expectedAttempt = predecessor)
                    return
                }
                ScreenDisposition.SAFE_HANDOFF -> {
                    dispatch(ScenarioEvent.SafeHandoffRequired, token, expectedAttempt = predecessor)
                    return
                }
                ScreenDisposition.ALLOW -> Unit
            }
            if (!fresh.settled) {
                waitForModalSettle(token, predecessor)
                return
            }
            if (fresh.localIdentity() != snapshotIdentity) {
                if (staleReplans >= MAX_STALE_REPLANS) {
                    dispatch(ScenarioEvent.ManualDismissRequired, token, expectedAttempt = predecessor)
                } else {
                    analyzeCurrentScreen(token,
                        staleReplans = staleReplans + 1, predecessor = predecessor)
                }
                return
            }
            synchronized(lock) {
                if (token != generation.get() ||
                    (predecessor != null && inFlightAttempt !== predecessor)) return
                modalSettleChecks = 0
            }
        }

        when (val modal = ModalDismissResolver.resolve(raw)) {
            ModalDismissResolver.Result.NotModal -> Unit
            ModalDismissResolver.Result.ManualDismiss -> {
                dispatch(ScenarioEvent.ManualDismissRequired, token, expectedAttempt = predecessor)
                return
            }
            is ModalDismissResolver.Result.CloseTarget -> {
                val close = modal.element
                val target = ResolvedScenarioTarget(
                    left = close.bounds.left,
                    top = close.bounds.top,
                    right = close.bounds.right,
                    bottom = close.bounds.bottom,
                    fingerprint = fingerprint,
                    snapshotIdentity = snapshotIdentity,
                    resourceId = close.resourceId.takeIf(String::isNotBlank),
                    text = close.text.takeIf(String::isNotBlank),
                )
                dispatch(
                    ScenarioEvent.ModalCloseAvailable(
                        ScenarioGuidance(
                            instruction = "Закройте появившееся окно, нажав выделенную кнопку закрытия. Потом я продолжу помощь.",
                            target = ScenarioTarget(
                                resourceId = close.resourceId.takeIf(String::isNotBlank),
                                text = close.text.takeIf(String::isNotBlank) ?: "Закрыть",
                            ),
                        )
                    ),
                    token,
                    expectedAttempt = predecessor,
                    targetToCommit = target,
                )
                return
            }
        }

        val safeScreen = ScreenSanitizer.sanitize(raw)

        // An unreadable tree provides no evidence that it is safe to route
        // away from the current surface. Recover locally instead of offering
        // an app launch over a screen the gate could not inspect.
        if (!raw.settled || safeScreen.elements.isEmpty() || fingerprint.isBlank()) {
            dispatch(ScenarioEvent.TargetNotFound, token, expectedAttempt = predecessor)
            return
        }

        val current = session
        val targetPackage = current.targetPackage
        if (!targetPackage.isNullOrBlank() && raw.packageName != targetPackage) {
            dispatch(ScenarioEvent.TargetAppRequired, token, expectedAttempt = predecessor)
            return
        }

        val attempt = synchronized(lock) {
            if (token != generation.get() || state.phase != ScenarioPhase.ANALYZING_SCREEN) return
            if (predecessor != null && inFlightAttempt !== predecessor) return
            val existing = inFlightAttempt
            if (existing?.fingerprint == snapshotIdentity &&
                (attemptKind == ScenarioPlanningAttempt.PRIMARY ||
                    existing.kind == ScenarioPlanningAttempt.FORMAT_REPAIR)) return
            if (attemptKind == ScenarioPlanningAttempt.FORMAT_REPAIR &&
                (predecessor == null || existing !== predecessor ||
                    predecessor.kind != ScenarioPlanningAttempt.PRIMARY)) return
            // A repair belongs to the original tree. A new tree gets a primary call.
            val kind = if (existing?.fingerprint != snapshotIdentity) {
                ScenarioPlanningAttempt.PRIMARY
            } else attemptKind
            PlanningAttempt(snapshotIdentity, kind).also { inFlightAttempt = it }
        }
        try {
            val outcome = planner.planNext(
                screen = safeScreen,
                goal = current.goal,
                stepNumber = current.stepNumber + 1,
                lastSafeInstruction = current.lastSafeInstruction,
                repairFormat = attempt.kind == ScenarioPlanningAttempt.FORMAT_REPAIR,
            )
            if (token != generation.get()) return
            if (synchronized(lock) { inFlightAttempt !== attempt }) return

            // The provider response belongs to this exact raw tree, including
            // viewport-only changes which may leave semantic labels unchanged.
            val latest = snapshotProvider()
            val latestFingerprint = latest.signature()
            when (assess(latest).disposition) {
                ScreenDisposition.PAUSE_AUTH -> {
                    dispatch(ScenarioEvent.AuthenticationRequired, token, expectedAttempt = attempt)
                    return
                }
                ScreenDisposition.SAFE_HANDOFF -> {
                    dispatch(ScenarioEvent.SafeHandoffRequired, token, expectedAttempt = attempt)
                    return
                }
                ScreenDisposition.ALLOW -> Unit
            }
            val latestSafe = ScreenSanitizer.sanitize(latest)
            if (!latest.settled || latestSafe.elements.isEmpty() || latestFingerprint.isBlank()) {
                dispatch(ScenarioEvent.TargetNotFound, token, expectedAttempt = attempt)
                return
            }
            if (latest.localIdentity() != snapshotIdentity) {
                if (staleReplans >= MAX_STALE_REPLANS) {
                    dispatch(ScenarioEvent.TargetNotFound, token, expectedAttempt = attempt)
                } else {
                    if (latestFingerprint != fingerprint) {
                        dispatch(ScenarioEvent.ScreenObserved(latestFingerprint), token, expectedAttempt = attempt)
                    }
                    analyzeCurrentScreen(token, staleReplans = staleReplans + 1, predecessor = attempt)
                }
                return
            }

            when (outcome) {
                is PlannerOutcome.Failure -> dispatch(
                    ScenarioEvent.PlannerFailed(outcome.kind.toScenarioFailure()),
                    token,
                    staleReplans = staleReplans,
                    expectedAttempt = attempt,
                )
                is PlannerOutcome.Success -> acceptPlan(outcome.plan, raw, token, staleReplans, attempt)
            }
        } finally {
            synchronized(lock) {
                if (inFlightAttempt === attempt) inFlightAttempt = null
            }
        }
    }

    private fun waitForModalSettle(token: Long, expectedAttempt: PlanningAttempt? = null) {
        val retry = synchronized(lock) {
            if (token != generation.get() ||
                (expectedAttempt != null && inFlightAttempt !== expectedAttempt)) return
            if (modalSettleChecks < MAX_MODAL_SETTLE_CHECKS) {
                modalSettleChecks++
                true
            } else false
        }
        if (retry) execute(ScenarioEffect.RecheckModalAfterSettling, token, expectedAttempt = expectedAttempt)
        else dispatch(ScenarioEvent.ManualDismissRequired, token, expectedAttempt = expectedAttempt)
    }

    private fun acceptPlan(
        plan: PlannerResult,
        raw: ScreenSnapshot,
        token: Long,
        staleReplans: Int,
        attempt: PlanningAttempt,
    ) {
        if (!SafetyGuard.isReadOnlyGuidanceAction(plan.actionType)) {
            dispatch(ScenarioEvent.PlannerFailed(ScenarioPlanningFailure.INVALID_RESPONSE), token,
                staleReplans = staleReplans, expectedAttempt = attempt)
            return
        }
        if (plan.isDone || plan.actionType == "answer") {
            dispatch(ScenarioEvent.PlannerAnswered(plan.instruction), token, expectedAttempt = attempt)
            return
        }

        val semanticTarget = runCatching {
            ScenarioTarget(
                resourceId = plan.targetResourceId.takeIf(String::isNotBlank),
                text = plan.targetText.takeIf(String::isNotBlank),
            )
        }.getOrNull()
        if (semanticTarget == null) {
            dispatch(ScenarioEvent.PlannerFailed(ScenarioPlanningFailure.INVALID_RESPONSE), token,
                staleReplans = staleReplans, expectedAttempt = attempt)
            return
        }

        val result = resolve(plan, raw)
        if (result !is TargetResolver.Result.Found) {
            dispatch(ScenarioEvent.TargetUnavailable(result.toTargetIssue()), token, expectedAttempt = attempt)
            return
        }

        val bounds = result.candidate.bounds
        val target = ResolvedScenarioTarget(
            bounds.left, bounds.top, bounds.right, bounds.bottom,
            raw.signature(), raw.localIdentity(),
            result.candidate.element.resourceId.takeIf(String::isNotBlank),
            plan.targetText.takeIf(String::isNotBlank),
        )
        dispatch(
            ScenarioEvent.PlannerGuided(
                ScenarioGuidance(plan.instruction, semanticTarget)
            ),
            token,
            expectedAttempt = attempt,
            targetToCommit = target,
        )
    }

    private fun assess(snapshot: ScreenSnapshot): ScreenSafetyAssessment =
        ScreenSafetyGate.assess(
            snapshot = snapshot,
            appLabel = appLabelProvider(snapshot.packageName),
        )

    private fun resolve(plan: PlannerResult, raw: ScreenSnapshot): TargetResolver.Result {
        val idResult = plan.targetResourceId.takeIf(String::isNotBlank)?.let {
            TargetResolver.resolveResourceId(it, raw)
        }
        if (idResult != null && idResult !is TargetResolver.Result.NotFound) return idResult
        val text = plan.targetText.takeIf(String::isNotBlank)
            ?: return idResult ?: TargetResolver.Result.NotFound("")

        // This projection is LOCAL ONLY: it retains safe labels on offscreen
        // nodes to distinguish not-visible from absent. The real viewport and
        // visibility flags are restored for resolution, never sent to the planner.
        // Filled editable values are still excluded by the sanitizer.
        val labelsByIndex = ScreenSanitizer.sanitize(raw.copy(
            viewport = null,
            elements = raw.elements.map { it.copy(visibleToUser = true) },
        )).elements.associateBy { it.index }
        if (labelsByIndex.values.none { safe ->
                listOfNotNull(safe.label, safe.description)
                    .any { it.trim().equals(text.trim(), ignoreCase = true) }
            }) return TargetResolver.Result.NotFound(text)
        val grounded = raw.copy(elements = raw.elements.map { element ->
            val safe = labelsByIndex[element.index]
            val label = listOfNotNull(safe?.label, safe?.description)
                .firstOrNull { it.trim().equals(text.trim(), ignoreCase = true) }
                ?: safe?.label.orEmpty()
            element.copy(text = label, hintText = "", contentDescription = "")
        })
        return TargetResolver.resolveLabel(text, grounded)
    }

    private fun TargetResolver.Result.toTargetIssue(): ScenarioTargetIssue = when (this) {
        is TargetResolver.Result.NotVisible -> ScenarioTargetIssue.NOT_VISIBLE
        is TargetResolver.Result.Ambiguous -> ScenarioTargetIssue.AMBIGUOUS
        is TargetResolver.Result.NotActionable -> ScenarioTargetIssue.NOT_ACTIONABLE
        is TargetResolver.Result.NotFound -> ScenarioTargetIssue.NOT_FOUND
        is TargetResolver.Result.Found -> error("Found target has no recovery issue")
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
        add(activeDialog)
        add(viewportOrLegacy.left)
        add(viewportOrLegacy.top)
        add(viewportOrLegacy.right)
        add(viewportOrLegacy.bottom)
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
            add(element.inDialog)
            add(element.clickableAncestorIndex)
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val MAX_STALE_REPLANS = 1
        const val MAX_MODAL_SETTLE_CHECKS = 3
    }
}
