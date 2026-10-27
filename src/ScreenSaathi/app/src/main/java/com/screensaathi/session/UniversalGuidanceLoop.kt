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
    val latency: ScenarioLatencyTrace? = null,
    private val delivery: (() -> Unit) -> Unit = { it() },
    val terminalPresentation: Boolean = false,
) {
    fun dispatch(
        postToUi: (() -> Unit) -> Unit,
        isActive: () -> Boolean = { true },
        isTurnCurrent: () -> Boolean = { true },
        consume: (UniversalGuidanceEmission) -> Unit,
    ) {
        // Stop deactivates the controller immediately, but its final card must
        // still reach main. Turn and generation/state ownership remain required.
        val deliver = {
            if (isTurnCurrent() && (isActive() || terminalPresentation)) {
                deliverIfCurrent { consume(this) }
            }
        }
        when (effect) {
            // These must complete before a caller deactivates/replaces the
            // session. In particular, never lose Stop's monitor/capsule cleanup.
            ScenarioEffect.CancelPendingWork, ScenarioEffect.StopSpeech,
            ScenarioEffect.ClearHighlight, ScenarioEffect.SaveResumeCapsule,
            ScenarioEffect.DeleteResumeCapsule, ScenarioEffect.StartNetworkMonitoring,
            ScenarioEffect.StopNetworkMonitoring -> deliver()
            else -> postToUi(deliver)
        }
    }

    /** Revalidate at UI consumption, after any time spent in the main queue. */
    fun deliverIfCurrent(action: () -> Unit) = delivery {
        action()
        when (effect) {
            is ScenarioEffect.ConfirmGoal -> latency?.confirmationRendered()
            is ScenarioEffect.ShowAnswer -> latency?.firstUsefulRender()
            is ScenarioEffect.ShowGuidance -> if (resolvedTarget != null) latency?.firstUsefulRender()
            else -> Unit
        }
    }

    /** Speech may wait on a separate worker after UI delivery has finished. */
    fun isCurrent(): Boolean {
        var current = false
        delivery { current = true }
        return current
    }
}

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
    private var latency = ScenarioLatencyTrace(clock, NoOpScenarioTelemetrySink)

    val session: ScenarioSession
        get() = synchronized(lock) { state }

    fun startGoal(
        goal: String,
        targetPackage: String? = null,
        ambiguous: Boolean = false,
        latency: ScenarioLatencyTrace? = null,
    ) {
        val token = synchronized(lock) {
            val nextToken = generation.incrementAndGet()
            state = ScenarioSession.idle(clock())
            pendingTarget = null
            modalSettleChecks = 0
            inFlightAttempt = null
            this.latency = latency ?: ScenarioLatencyTrace(clock, NoOpScenarioTelemetrySink)
            nextToken
        }
        // The caller already supplied the recognized text. StartRequested is
        // still reduced to establish the phase, but its microphone effect is
        // suppressed; only an explicit Repeat action starts another capture.
        dispatch(ScenarioEvent.StartRequested, token, suppressRecognition = true)
        dispatch(ScenarioEvent.GoalRecognized(goal, targetPackage, ambiguous), token)
    }

    /** Restore only logical progress; live safety and target resolution rerun. */
    fun resume(capsule: ResumeCapsule) {
        val token = synchronized(lock) {
            val nextToken = generation.incrementAndGet()
            state = ScenarioSession.idle(clock())
            pendingTarget = null
            modalSettleChecks = 0
            inFlightAttempt = null
            latency = ScenarioLatencyTrace(clock, NoOpScenarioTelemetrySink)
            nextToken
        }
        dispatch(ScenarioEvent.ResumeRequested(capsule), token)
    }

    fun confirmRecognition() = dispatch(ScenarioEvent.RecognitionConfirmed, generation.get())

    fun repeatRecognition() = dispatch(ScenarioEvent.RepeatRecognition, generation.get())

    fun confirmGoal() {
        synchronized(lock) { latency.guidanceConfirmed() }
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

    fun stop(deliverIdleAfterDeactivation: Boolean = false) {
        val token: Long
        val transition = synchronized(lock) {
            token = generation.incrementAndGet()
            val next = ScenarioReducer.reduce(state, ScenarioEvent.StopRequested, clock())
            state = next.session
            pendingTarget = null
            modalSettleChecks = 0
            inFlightAttempt = null
            next
        }
        transition.effects.forEach {
            execute(it, token, expectedState = transition.session,
                terminalPresentation = deliverIdleAfterDeactivation && it == ScenarioEffect.ShowIdle)
        }
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
                execute(effect, token, staleReplans, expectedAttempt, transition.session)
            }
        }
    }

    private fun execute(
        effect: ScenarioEffect,
        token: Long,
        staleReplans: Int = 0,
        expectedAttempt: PlanningAttempt? = null,
        expectedState: ScenarioSession? = null,
        terminalPresentation: Boolean = false,
    ) {
        if (token != generation.get()) return
        if (expectedAttempt != null && synchronized(lock) { inFlightAttempt !== expectedAttempt }) return
        when (effect) {
            ScenarioEffect.AnalyzeCurrentScreen -> analyzeCurrentScreen(token, predecessor = expectedAttempt)
            is ScenarioEffect.RetryPlanning -> analyzeCurrentScreen(
                token, effect.attempt, staleReplans, predecessor = expectedAttempt,
            )
            else -> {
                val emission = synchronized(lock) {
                    if (token != generation.get() ||
                        (expectedState != null && state !== expectedState) ||
                        (expectedAttempt != null && inFlightAttempt !== expectedAttempt)) return
                    if (effect == ScenarioEffect.ClearHighlight) pendingTarget = null
                    val owner = state
                    UniversalGuidanceEmission(effect,
                        if (effect is ScenarioEffect.ShowGuidance) pendingTarget else null,
                        latency,
                        delivery = { action -> synchronized(lock) {
                            if (token == generation.get() && state === owner) action()
                        } },
                        terminalPresentation = terminalPresentation,
                    )
                }
                emit(emission)
            }
        }
    }

    private fun analyzeCurrentScreen(
        token: Long,
        attemptKind: ScenarioPlanningAttempt = ScenarioPlanningAttempt.PRIMARY,
        staleReplans: Int = 0,
        predecessor: PlanningAttempt? = null,
    ) {
        if (token != generation.get()) return
        val (before, trace) = synchronized(lock) {
            if (token != generation.get()) return
            if (predecessor != null && inFlightAttempt !== predecessor) return
            state to latency
        }
        if (before.phase != ScenarioPhase.ANALYZING_SCREEN) return
        trace.analysisStarted()
        val raw = trace.measure(ScenarioLatencyStage.SNAPSHOT) { snapshotProvider() }
        val fingerprint = raw.signature()
        val snapshotIdentity = raw.localIdentity()
        if (fingerprint.isNotBlank() && fingerprint != before.screenFingerprint) {
            dispatch(ScenarioEvent.ScreenObserved(fingerprint), token, expectedAttempt = predecessor)
        }

        val safety = trace.measure(ScenarioLatencyStage.SAFETY) { assess(raw) }
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

        val modal = if (raw.activeDialog) trace.measure(ScenarioLatencyStage.TARGET_RESOLUTION) {
            ModalDismissResolver.resolve(raw)
        } else ModalDismissResolver.Result.NotModal
        when (modal) {
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
            val outcome = trace.measure(
                ScenarioLatencyStage.PLANNER,
                outcome = {
                    if (it is PlannerOutcome.Success) ScenarioLatencyOutcome.SUCCESS
                    else ScenarioLatencyOutcome.FAILURE
                },
            ) {
                planner.planNext(
                    screen = safeScreen,
                    goal = current.goal,
                    stepNumber = current.stepNumber + 1,
                    lastSafeInstruction = current.lastSafeInstruction,
                    repairFormat = attempt.kind == ScenarioPlanningAttempt.FORMAT_REPAIR,
                )
            }
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
                is PlannerOutcome.Success -> acceptPlan(outcome.plan, raw, token, staleReplans, attempt, trace)
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
        trace: ScenarioLatencyTrace,
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

        val result = trace.measure(
            ScenarioLatencyStage.TARGET_RESOLUTION,
            outcome = {
                if (it is TargetResolver.Result.Found) ScenarioLatencyOutcome.SUCCESS
                else ScenarioLatencyOutcome.FAILURE
            },
        ) { resolve(plan, raw) }
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
