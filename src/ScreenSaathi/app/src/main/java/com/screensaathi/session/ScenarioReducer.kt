package com.screensaathi.session

/** Pure scenario transition function: no Android, network, clock, or mutable state. */
object ScenarioReducer {
    fun reduce(
        session: ScenarioSession,
        event: ScenarioEvent,
        nowMs: Long,
    ): ScenarioTransition {
        if (event == ScenarioEvent.StopRequested || event == ScenarioEvent.GoalCancelled) {
            return stop(nowMs)
        }

        return when (event) {
            ScenarioEvent.StartRequested -> start(session, nowMs)
            is ScenarioEvent.GoalRecognized -> goalRecognized(session, event, nowMs)
            ScenarioEvent.RecognitionConfirmed -> recognitionConfirmed(session, nowMs)
            ScenarioEvent.RepeatRecognition -> repeatRecognition(session, nowMs)
            ScenarioEvent.TargetAppRequired -> targetAppRequired(session, nowMs)
            is ScenarioEvent.TargetAppSelected -> targetAppSelected(session, event, nowMs)
            is ScenarioEvent.TargetAppVisible -> targetAppVisible(session, event, nowMs)
            is ScenarioEvent.PlannerAnswered -> plannerAnswered(session, event, nowMs)
            is ScenarioEvent.PlannerGuided -> plannerGuided(session, event, nowMs)
            ScenarioEvent.GoalConfirmed -> goalConfirmed(session, nowMs)
            ScenarioEvent.HighlightedTargetTapped -> targetTapped(session, nowMs)
            is ScenarioEvent.ScreenObserved -> screenObserved(session, event, nowMs)
            is ScenarioEvent.PhysicalTapSettled -> physicalTapSettled(session, event, nowMs)
            ScenarioEvent.PhysicalTapTimedOut -> physicalTapTimedOut(session, nowMs)
            is ScenarioEvent.CachedGuideInvalidated -> cachedGuideInvalidated(session, event, nowMs)
            ScenarioEvent.TargetNotFound -> targetNotFound(session, nowMs)
            ScenarioEvent.AuthenticationRequired -> authenticationRequired(session, nowMs)
            ScenarioEvent.NetworkUnavailable -> networkUnavailable(session, nowMs)
            ScenarioEvent.SafeHandoffRequired -> safeHandoff(session, nowMs)
            is ScenarioEvent.PlannerFailed -> plannerFailed(session, event, nowMs)
            ScenarioEvent.TtsFailed -> ttsFailed(session)
            ScenarioEvent.ContinueRequested -> continueRequested(session, nowMs)
            ScenarioEvent.RetryRequested -> retryRequested(session, nowMs)
            ScenarioEvent.GoalCompleted -> complete(session, nowMs)
            ScenarioEvent.RephraseRequested -> rephrase(session, nowMs)
            ScenarioEvent.RestartRequested -> restart(session, nowMs)
            ScenarioEvent.GoalCancelled,
            ScenarioEvent.StopRequested,
            -> error("Handled before the transition table")
        }
    }

    private fun start(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.IDLE) return unchanged(session)
        return ScenarioTransition(
            session = ScenarioSession.idle(nowMs).copy(phase = ScenarioPhase.RECOGNIZING_GOAL),
            effects = listOf(ScenarioEffect.BeginRecognition),
        )
    }

    private fun goalRecognized(
        session: ScenarioSession,
        event: ScenarioEvent.GoalRecognized,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.RECOGNIZING_GOAL) return unchanged(session)
        val goal = event.goal.trim()
        val updated = session.copy(
            goal = goal,
            targetPackage = event.targetPackage?.takeIf(String::isNotBlank),
            updatedAtMs = nowMs,
        )
        return if (event.ambiguous) {
            ScenarioTransition(
                updated,
                listOf(
                    ScenarioEffect.ShowDecision(
                        notice = ScenarioNotice.VERIFY_RECOGNITION,
                        choices = if (goal.isBlank()) {
                            listOf(ScenarioChoice.REPEAT_RECOGNITION, ScenarioChoice.STOP)
                        } else {
                            listOf(
                                ScenarioChoice.CONFIRM_RECOGNITION,
                                ScenarioChoice.REPEAT_RECOGNITION,
                            )
                        },
                        recognizedGoal = goal,
                    )
                ),
            )
        } else {
            ScenarioTransition(
                updated.copy(phase = ScenarioPhase.ANALYZING_SCREEN),
                listOf(ScenarioEffect.AnalyzeCurrentScreen),
            )
        }
    }

    private fun recognitionConfirmed(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.RECOGNIZING_GOAL || session.goal.isBlank()) {
            return unchanged(session)
        }
        return ScenarioTransition(
            session.copy(phase = ScenarioPhase.ANALYZING_SCREEN, updatedAtMs = nowMs),
            listOf(ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun repeatRecognition(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.RECOGNIZING_GOAL) return unchanged(session)
        return ScenarioTransition(
            session.copy(goal = "", targetPackage = null, updatedAtMs = nowMs),
            listOf(ScenarioEffect.BeginRecognition),
        )
    }

    private fun targetAppRequired(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        val packageName = session.targetPackage ?: return unchanged(session)
        if (session.phase != ScenarioPhase.ANALYZING_SCREEN) return unchanged(session)
        return ScenarioTransition(
            session.copy(phase = ScenarioPhase.WAITING_FOR_APP, updatedAtMs = nowMs),
            listOf(ScenarioEffect.ShowTargetAppChoice(packageName)),
        )
    }

    private fun targetAppSelected(
        session: ScenarioSession,
        event: ScenarioEvent.TargetAppSelected,
        nowMs: Long,
    ): ScenarioTransition {
        if (
            session.phase != ScenarioPhase.WAITING_FOR_APP ||
            event.packageName != session.targetPackage
        ) {
            return unchanged(session)
        }
        return ScenarioTransition(
            session.copy(updatedAtMs = nowMs),
            listOf(ScenarioEffect.LaunchTargetApp(event.packageName)),
        )
    }

    private fun targetAppVisible(
        session: ScenarioSession,
        event: ScenarioEvent.TargetAppVisible,
        nowMs: Long,
    ): ScenarioTransition {
        if (
            session.phase != ScenarioPhase.WAITING_FOR_APP ||
            event.packageName != session.targetPackage
        ) {
            return unchanged(session)
        }
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.ANALYZING_SCREEN,
                screenFingerprint = event.fingerprint,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun plannerAnswered(
        session: ScenarioSession,
        event: ScenarioEvent.PlannerAnswered,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.ANALYZING_SCREEN || event.instruction.isBlank()) {
            return unchanged(session)
        }
        val instruction = event.instruction.trim()
        return ScenarioTransition(
            session.copy(
                mode = ScenarioMode.ONE_SHOT,
                phase = ScenarioPhase.COMPLETED,
                lastSafeInstruction = instruction,
                currentGuidance = null,
                replanFailures = 0,
                updatedAtMs = nowMs,
            ),
            listOf(
                ScenarioEffect.ClearHighlight,
                ScenarioEffect.ShowAnswer(instruction),
                ScenarioEffect.SpeakText(instruction),
                ScenarioEffect.DeleteResumeCapsule,
                ScenarioEffect.FinishScenario,
            ),
        )
    }

    private fun plannerGuided(
        session: ScenarioSession,
        event: ScenarioEvent.PlannerGuided,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.ANALYZING_SCREEN) return unchanged(session)
        val isFirstGuide = session.stepNumber == 0
        val base = session.copy(
            mode = ScenarioMode.GUIDANCE,
            currentGuidance = event.guidance,
            scrollAttempts = 0,
            replanFailures = 0,
            updatedAtMs = nowMs,
        )
        return if (isFirstGuide) {
            ScenarioTransition(
                base.copy(phase = ScenarioPhase.CONFIRMING_GOAL),
                listOf(ScenarioEffect.ConfirmGoal(session.goal)),
            )
        } else {
            presentGuidance(base, event.guidance, session.stepNumber + 1, nowMs)
        }
    }

    private fun goalConfirmed(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.CONFIRMING_GOAL) return unchanged(session)
        val guidance = session.currentGuidance ?: return unchanged(session)
        return presentGuidance(session, guidance, stepNumber = 1, nowMs = nowMs)
    }

    private fun presentGuidance(
        session: ScenarioSession,
        guidance: ScenarioGuidance,
        stepNumber: Int,
        nowMs: Long,
    ): ScenarioTransition = ScenarioTransition(
        session = session.copy(
            mode = ScenarioMode.GUIDANCE,
            phase = ScenarioPhase.GUIDING_STEP,
            stepNumber = stepNumber,
            lastSafeInstruction = guidance.instruction,
            currentGuidance = guidance,
            updatedAtMs = nowMs,
        ),
        effects = listOf(
            ScenarioEffect.ShowGuidance(guidance),
            ScenarioEffect.SpeakText(guidance.instruction),
            ScenarioEffect.SaveResumeCapsule,
        ),
    )

    private fun targetTapped(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.GUIDING_STEP) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.WAITING_FOR_SCREEN_CHANGE,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.SaveResumeCapsule),
        )
    }

    private fun screenObserved(
        session: ScenarioSession,
        event: ScenarioEvent.ScreenObserved,
        nowMs: Long,
    ): ScenarioTransition {
        val fingerprint = event.fingerprint.takeIf(String::isNotBlank) ?: return unchanged(session)
        if (fingerprint == session.screenFingerprint) return unchanged(session)

        return when (session.phase) {
            ScenarioPhase.WAITING_FOR_SCREEN_CHANGE,
            ScenarioPhase.GUIDING_STEP,
            -> ScenarioTransition(
                session.copy(
                    phase = ScenarioPhase.ANALYZING_SCREEN,
                    screenFingerprint = fingerprint,
                    currentGuidance = null,
                    updatedAtMs = nowMs,
                ),
                listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.AnalyzeCurrentScreen),
            )

            ScenarioPhase.WAITING_FOR_MANUAL_SCROLL -> manualScrollChanged(session, fingerprint, nowMs)
            ScenarioPhase.CONFIRMING_GOAL -> ScenarioTransition(
                session.copy(
                    phase = ScenarioPhase.ANALYZING_SCREEN,
                    screenFingerprint = fingerprint,
                    currentGuidance = null,
                    updatedAtMs = nowMs,
                ),
                listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.AnalyzeCurrentScreen),
            )
            ScenarioPhase.ANALYZING_SCREEN -> ScenarioTransition(
                session.copy(
                    screenFingerprint = fingerprint,
                    replanFailures = 0,
                    updatedAtMs = nowMs,
                ),
            )
            else -> unchanged(session)
        }
    }

    /**
     * A physical tap is stronger evidence than an ordinary duplicate window
     * event. Editable fields can open an IME or move focus without changing the
     * stable semantic fingerprint, so one settled post-tap observation must be
     * allowed to re-run analysis instead of leaving the session stuck forever.
     */
    private fun physicalTapSettled(
        session: ScenarioSession,
        event: ScenarioEvent.PhysicalTapSettled,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.WAITING_FOR_SCREEN_CHANGE) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.ANALYZING_SCREEN,
                screenFingerprint = event.fingerprint.takeIf(String::isNotBlank)
                    ?: session.screenFingerprint,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun physicalTapTimedOut(
        session: ScenarioSession,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.WAITING_FOR_SCREEN_CHANGE) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.RequestManualScroll),
        )
    }

    private fun cachedGuideInvalidated(
        session: ScenarioSession,
        event: ScenarioEvent.CachedGuideInvalidated,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.CONFIRMING_GOAL) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.ANALYZING_SCREEN,
                screenFingerprint = event.fingerprint.takeIf(String::isNotBlank)
                    ?: session.screenFingerprint,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun manualScrollChanged(
        session: ScenarioSession,
        fingerprint: String,
        nowMs: Long,
    ): ScenarioTransition {
        val attempts = session.scrollAttempts + 1
        val updated = session.copy(
            screenFingerprint = fingerprint,
            scrollAttempts = attempts,
            currentGuidance = null,
            updatedAtMs = nowMs,
        )
        return ScenarioTransition(
            updated.copy(phase = ScenarioPhase.ANALYZING_SCREEN),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun targetNotFound(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.ANALYZING_SCREEN) return unchanged(session)
        if (session.scrollAttempts >= MAX_SCROLL_ATTEMPTS) {
            return ScenarioTransition(
                session.copy(
                    phase = ScenarioPhase.FAILED,
                    currentGuidance = null,
                    updatedAtMs = nowMs,
                ),
                listOf(
                    ScenarioEffect.ClearHighlight,
                    ScenarioEffect.ShowRecoverableFailure(
                        reason = ScenarioFailureReason.SCROLL_LIMIT,
                        choices = listOf(
                            ScenarioChoice.REPHRASE,
                            ScenarioChoice.RESTART,
                            ScenarioChoice.STOP,
                        ),
                    ),
                ),
            )
        }
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.RequestManualScroll),
        )
    }

    private fun authenticationRequired(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase in TERMINAL_PHASES) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.PAUSED_FOR_AUTH,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(
                ScenarioEffect.ClearHighlight,
                ScenarioEffect.ShowDecision(
                    ScenarioNotice.AUTHENTICATION,
                    listOf(ScenarioChoice.CONTINUE, ScenarioChoice.STOP),
                ),
            ),
        )
    }

    private fun networkUnavailable(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase in TERMINAL_PHASES) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.PAUSED_FOR_NETWORK,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(
                ScenarioEffect.ClearHighlight,
                ScenarioEffect.ShowDecision(
                    ScenarioNotice.NO_NETWORK,
                    listOf(ScenarioChoice.RETRY, ScenarioChoice.STOP),
                ),
            ),
        )
    }

    private fun safeHandoff(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase in TERMINAL_PHASES) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                mode = ScenarioMode.SAFE_HANDOFF,
                phase = ScenarioPhase.SAFE_HANDOFF,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(
                ScenarioEffect.ClearHighlight,
                ScenarioEffect.ShowSafeHandoff(),
                ScenarioEffect.DeleteResumeCapsule,
                ScenarioEffect.FinishScenario,
            ),
        )
    }

    private fun plannerFailed(
        session: ScenarioSession,
        event: ScenarioEvent.PlannerFailed,
        nowMs: Long,
    ): ScenarioTransition {
        if (session.phase != ScenarioPhase.ANALYZING_SCREEN) return unchanged(session)
        return when (event.kind) {
            ScenarioPlanningFailure.INVALID_RESPONSE -> {
                val failures = session.replanFailures + 1
                if (failures <= MAX_AUTOMATIC_RETRIES) {
                    ScenarioTransition(
                        session.copy(replanFailures = failures, updatedAtMs = nowMs),
                        listOf(ScenarioEffect.RetryPlanning(repairFormat = true)),
                    )
                } else {
                    fail(
                        session.copy(replanFailures = failures),
                        ScenarioFailureReason.INVALID_PLANNER_RESPONSE,
                        nowMs,
                    )
                }
            }
            ScenarioPlanningFailure.NETWORK -> networkUnavailable(session, nowMs)
            ScenarioPlanningFailure.REMOTE -> fail(
                session,
                ScenarioFailureReason.REMOTE_PLANNER_FAILURE,
                nowMs,
            )
            ScenarioPlanningFailure.CONFIGURATION -> fail(
                session,
                ScenarioFailureReason.CONFIGURATION,
                nowMs,
            )
        }
    }

    private fun fail(
        session: ScenarioSession,
        reason: ScenarioFailureReason,
        nowMs: Long,
    ): ScenarioTransition = ScenarioTransition(
        session.copy(
            phase = ScenarioPhase.FAILED,
            currentGuidance = null,
            updatedAtMs = nowMs,
        ),
        listOf(
            ScenarioEffect.ClearHighlight,
            ScenarioEffect.ShowRecoverableFailure(
                reason,
                listOf(ScenarioChoice.RETRY, ScenarioChoice.STOP),
            ),
        ),
    )

    private fun ttsFailed(session: ScenarioSession): ScenarioTransition {
        if (session.phase in TERMINAL_PHASES || session.phase == ScenarioPhase.IDLE) {
            return unchanged(session)
        }
        return ScenarioTransition(
            session,
            listOf(
                ScenarioEffect.ShowDecision(
                    ScenarioNotice.TTS_UNAVAILABLE,
                    listOf(ScenarioChoice.RETRY, ScenarioChoice.CONTINUE),
                )
            ),
        )
    }

    private fun continueRequested(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase == ScenarioPhase.GUIDING_STEP) {
            val guidance = session.currentGuidance ?: return unchanged(session)
            return ScenarioTransition(
                session.copy(updatedAtMs = nowMs),
                listOf(ScenarioEffect.ShowGuidance(guidance)),
            )
        }
        if (session.phase != ScenarioPhase.PAUSED_FOR_AUTH) return unchanged(session)
        return ScenarioTransition(
            session.copy(phase = ScenarioPhase.ANALYZING_SCREEN, updatedAtMs = nowMs),
            listOf(ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun retryRequested(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase == ScenarioPhase.GUIDING_STEP) {
            val instruction = session.lastSafeInstruction ?: return unchanged(session)
            val guidance = session.currentGuidance ?: return unchanged(session)
            return ScenarioTransition(
                session.copy(updatedAtMs = nowMs),
                listOf(
                    ScenarioEffect.ShowGuidance(guidance),
                    ScenarioEffect.SpeakText(instruction),
                ),
            )
        }
        if (session.phase !in setOf(ScenarioPhase.PAUSED_FOR_NETWORK, ScenarioPhase.FAILED)) {
            return unchanged(session)
        }
        val resetFailures = session.phase == ScenarioPhase.FAILED
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.ANALYZING_SCREEN,
                scrollAttempts = if (resetFailures) 0 else session.scrollAttempts,
                replanFailures = if (resetFailures) 0 else session.replanFailures,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun complete(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase in TERMINAL_PHASES) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                phase = ScenarioPhase.COMPLETED,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(
                ScenarioEffect.ClearHighlight,
                ScenarioEffect.DeleteResumeCapsule,
                ScenarioEffect.FinishScenario,
            ),
        )
    }

    private fun rephrase(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.FAILED) return unchanged(session)
        return ScenarioTransition(
            ScenarioSession.idle(nowMs).copy(phase = ScenarioPhase.RECOGNIZING_GOAL),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.BeginRecognition),
        )
    }

    private fun restart(session: ScenarioSession, nowMs: Long): ScenarioTransition {
        if (session.phase != ScenarioPhase.FAILED) return unchanged(session)
        return ScenarioTransition(
            session.copy(
                mode = ScenarioMode.ONE_SHOT,
                phase = ScenarioPhase.ANALYZING_SCREEN,
                stepNumber = 0,
                lastSafeInstruction = null,
                scrollAttempts = 0,
                replanFailures = 0,
                currentGuidance = null,
                updatedAtMs = nowMs,
            ),
            listOf(ScenarioEffect.ClearHighlight, ScenarioEffect.AnalyzeCurrentScreen),
        )
    }

    private fun stop(nowMs: Long): ScenarioTransition = ScenarioTransition(
        ScenarioSession.idle(nowMs),
        listOf(
            ScenarioEffect.CancelPendingWork,
            ScenarioEffect.StopSpeech,
            ScenarioEffect.ClearHighlight,
            ScenarioEffect.DeleteResumeCapsule,
            ScenarioEffect.ShowIdle,
        ),
    )

    private fun unchanged(session: ScenarioSession) = ScenarioTransition(session)

    private val TERMINAL_PHASES = setOf(
        ScenarioPhase.IDLE,
        ScenarioPhase.SAFE_HANDOFF,
        ScenarioPhase.COMPLETED,
        ScenarioPhase.FAILED,
    )
    private const val MAX_SCROLL_ATTEMPTS = 3
    private const val MAX_AUTOMATIC_RETRIES = 1
}
