package com.screensaathi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioReducerTest {

    @Test
    fun `only invisible target enters scroll recovery and all issues clear guidance`() {
        ScenarioTargetIssue.entries.forEach { issue ->
            val session = ScenarioSession.idle(0).copy(phase = ScenarioPhase.ANALYZING_SCREEN)
            val result = reduce(session, ScenarioEvent.TargetUnavailable(issue), 10)
            assertTrue(result.effects.contains(ScenarioEffect.ClearHighlight))
            assertTrue(result.effects.contains(ScenarioEffect.ShowTargetIssue(issue)))
            assertEquals(issue == ScenarioTargetIssue.NOT_VISIBLE,
                result.session.phase == ScenarioPhase.WAITING_FOR_MANUAL_SCROLL)
            assertTrue(result.effects.none { it is ScenarioEffect.ShowGuidance })
            assertTrue(result.effects.none {
                it is ScenarioEffect.RetryPlanning || it == ScenarioEffect.AnalyzeCurrentScreen
            })
            assertEquals(0, result.session.replanFailures)
            assertNull(result.session.currentGuidance)
            if (issue != ScenarioTargetIssue.NOT_VISIBLE) {
                assertEquals(ScenarioPhase.ANALYZING_SCREEN,
                    reduce(result.session, ScenarioEvent.RetryRequested, 20).session.phase)
                assertEquals(ScenarioPhase.RECOGNIZING_GOAL,
                    reduce(result.session, ScenarioEvent.RephraseRequested, 20).session.phase)
            }
        }
    }

    @Test
    fun `idle starts recognition and ambiguous STT offers correct-or-repeat`() {
        val recognizing = reduce(ScenarioSession.idle(0), ScenarioEvent.StartRequested, 10)

        assertEquals(ScenarioPhase.RECOGNIZING_GOAL, recognizing.session.phase)
        assertEquals(10, recognizing.session.createdAtMs)
        assertTrue(recognizing.effects.contains(ScenarioEffect.BeginRecognition))

        val ambiguous = reduce(
            recognizing.session,
            ScenarioEvent.GoalRecognized(
                goal = "Заказать такси",
                targetPackage = "ru.yandex.taxi",
                ambiguous = true,
            ),
            20,
        )

        assertEquals(ScenarioPhase.RECOGNIZING_GOAL, ambiguous.session.phase)
        assertEquals("Заказать такси", ambiguous.session.goal)
        val prompt = ambiguous.effects.single() as ScenarioEffect.ShowDecision
        assertEquals(ScenarioNotice.VERIFY_RECOGNITION, prompt.notice)
        assertEquals(
            listOf(ScenarioChoice.CONFIRM_RECOGNITION, ScenarioChoice.REPEAT_RECOGNITION),
            prompt.choices,
        )

        val repeat = reduce(ambiguous.session, ScenarioEvent.RepeatRecognition, 30)
        assertEquals("", repeat.session.goal)
        assertTrue(repeat.effects.contains(ScenarioEffect.BeginRecognition))

        val confirmed = reduce(ambiguous.session, ScenarioEvent.RecognitionConfirmed, 31)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, confirmed.session.phase)
        assertTrue(confirmed.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))
    }

    @Test
    fun `empty ambiguous STT offers repeat or stop instead of confirming an empty goal`() {
        val recognizing = reduce(ScenarioSession.idle(0), ScenarioEvent.StartRequested, 1).session

        val result = reduce(
            recognizing,
            ScenarioEvent.GoalRecognized("", targetPackage = null, ambiguous = true),
            2,
        )

        val decision = result.effects.single() as ScenarioEffect.ShowDecision
        assertEquals(
            listOf(ScenarioChoice.REPEAT_RECOGNITION, ScenarioChoice.STOP),
            decision.choices,
        )
    }

    @Test
    fun `one shot answer completes without goal confirmation`() {
        val analyzing = recognized(goal = "Где история заказов?")

        val answered = reduce(
            analyzing,
            ScenarioEvent.PlannerAnswered("История заказов находится в разделе «Профиль»."),
            40,
        )

        assertEquals(ScenarioPhase.COMPLETED, answered.session.phase)
        assertEquals(ScenarioMode.ONE_SHOT, answered.session.mode)
        assertEquals(0, answered.session.stepNumber)
        assertTrue(answered.effects.any { it is ScenarioEffect.ShowAnswer })
        assertTrue(answered.effects.any { it is ScenarioEffect.SpeakText })
        assertFalse(answered.effects.any { it is ScenarioEffect.ConfirmGoal })
        assertTrue(answered.effects.contains(ScenarioEffect.DeleteResumeCapsule))
    }

    @Test
    fun `first guide is cached for goal confirmation then becomes step one`() {
        val analyzing = recognized().copy(screenFingerprint = "screen-1")
        val guidance = ScenarioGuidance(
            instruction = "Нажмите «Куда».",
            target = ScenarioTarget(resourceId = "destination", text = "Куда"),
        )

        val proposed = reduce(analyzing, ScenarioEvent.PlannerGuided(guidance), 50)

        assertEquals(ScenarioPhase.CONFIRMING_GOAL, proposed.session.phase)
        assertEquals(ScenarioMode.GUIDANCE, proposed.session.mode)
        assertEquals(guidance, proposed.session.currentGuidance)
        assertTrue(proposed.effects.single() is ScenarioEffect.ConfirmGoal)
        assertFalse(proposed.effects.any { it is ScenarioEffect.ShowGuidance })

        val confirmed = reduce(proposed.session, ScenarioEvent.GoalConfirmed, 60)

        assertEquals(ScenarioPhase.GUIDING_STEP, confirmed.session.phase)
        assertEquals(1, confirmed.session.stepNumber)
        assertEquals(guidance.instruction, confirmed.session.lastSafeInstruction)
        assertTrue(confirmed.effects.any { it is ScenarioEffect.ShowGuidance })
        assertTrue(confirmed.effects.any { it is ScenarioEffect.SpeakText })
        assertTrue(confirmed.effects.contains(ScenarioEffect.SaveResumeCapsule))
    }

    @Test
    fun `exact app launch exists only after the user selects the captured package`() {
        val analyzing = recognized(targetPackage = "ru.yandex.taxi")
        val waiting = reduce(analyzing, ScenarioEvent.TargetAppRequired, 70)

        assertEquals(ScenarioPhase.WAITING_FOR_APP, waiting.session.phase)
        assertTrue(waiting.effects.single() is ScenarioEffect.ShowTargetAppChoice)

        val invented = reduce(waiting.session, ScenarioEvent.TargetAppSelected("com.example.other"), 71)
        assertTrue(invented.effects.none { it is ScenarioEffect.LaunchTargetApp })

        val selected = reduce(waiting.session, ScenarioEvent.TargetAppSelected("ru.yandex.taxi"), 72)
        assertEquals(
            "ru.yandex.taxi",
            (selected.effects.single() as ScenarioEffect.LaunchTargetApp).packageName,
        )

        val visible = reduce(
            selected.session,
            ScenarioEvent.TargetAppVisible("ru.yandex.taxi", "screen-1"),
            73,
        )
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, visible.session.phase)
        assertEquals("screen-1", visible.session.screenFingerprint)
        assertTrue(visible.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))
    }

    @Test
    fun `physical tap waits and only a changed screen advances once`() {
        val guiding = guiding(screenFingerprint = "screen-1")

        val tapped = reduce(guiding, ScenarioEvent.HighlightedTargetTapped, 80)
        assertEquals(ScenarioPhase.WAITING_FOR_SCREEN_CHANGE, tapped.session.phase)
        assertNull(tapped.session.currentGuidance)
        assertTrue(tapped.effects.contains(ScenarioEffect.ClearHighlight))

        val unchanged = reduce(tapped.session, ScenarioEvent.ScreenObserved("screen-1"), 81)
        assertEquals(tapped.session, unchanged.session)
        assertTrue(unchanged.effects.isEmpty())

        val changed = reduce(tapped.session, ScenarioEvent.ScreenObserved("screen-2"), 82)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, changed.session.phase)
        assertEquals("screen-2", changed.session.screenFingerprint)
        assertNull(changed.session.currentGuidance)
        assertTrue(changed.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))

        val duplicate = reduce(changed.session, ScenarioEvent.ScreenObserved("screen-2"), 83)
        assertEquals(changed.session, duplicate.session)
        assertTrue(duplicate.effects.isEmpty())
    }

    @Test
    fun `settled physical tap may reanalyze even when ordinary duplicate fingerprint is ignored`() {
        val tapped = reduce(
            guiding(screenFingerprint = "screen-1"),
            ScenarioEvent.HighlightedTargetTapped,
            84,
        ).session

        val ordinaryDuplicate = reduce(tapped, ScenarioEvent.ScreenObserved("screen-1"), 85)
        assertEquals(ScenarioPhase.WAITING_FOR_SCREEN_CHANGE, ordinaryDuplicate.session.phase)

        val settledTap = reduce(
            ordinaryDuplicate.session,
            ScenarioEvent.PhysicalTapSettled("screen-1"),
            86,
        )
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, settledTap.session.phase)
        assertTrue(settledTap.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))
    }

    @Test
    fun `physical tap timeout requests local manual recovery`() {
        val tapped = reduce(
            guiding(screenFingerprint = "screen-1"),
            ScenarioEvent.HighlightedTargetTapped,
            87,
        ).session

        val timeout = reduce(tapped, ScenarioEvent.PhysicalTapTimedOut, 88)

        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, timeout.session.phase)
        assertTrue(timeout.effects.contains(ScenarioEffect.RequestManualScroll))
        assertTrue(timeout.effects.none { it is ScenarioEffect.AnalyzeCurrentScreen })
    }

    @Test
    fun `auth network final action and stop have explicit safe transitions`() {
        val analyzing = recognized().copy(screenFingerprint = "screen-1")

        val auth = reduce(analyzing, ScenarioEvent.AuthenticationRequired, 90)
        assertEquals(ScenarioPhase.PAUSED_FOR_AUTH, auth.session.phase)
        assertTrue(auth.effects.contains(ScenarioEffect.ClearHighlight))
        assertEquals(ScenarioNotice.AUTHENTICATION, decision(auth).notice)

        val authContinued = reduce(auth.session, ScenarioEvent.ContinueRequested, 91)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, authContinued.session.phase)

        val network = reduce(analyzing, ScenarioEvent.NetworkUnavailable, 92)
        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, network.session.phase)
        assertEquals(ScenarioNotice.NO_NETWORK, decision(network).notice)
        assertTrue(network.effects.contains(ScenarioEffect.StartNetworkMonitoring))

        val retried = reduce(
            network.session,
            ScenarioEvent.NetworkRecoveryRequested("screen-1"),
            93,
        )
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, retried.session.phase)
        assertTrue(retried.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))
        assertTrue(retried.effects.contains(ScenarioEffect.StopNetworkMonitoring))

        val handoff = reduce(analyzing, ScenarioEvent.SafeHandoffRequired, 94)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, handoff.session.phase)
        assertEquals(ScenarioMode.SAFE_HANDOFF, handoff.session.mode)
        assertTrue(handoff.effects.any { it is ScenarioEffect.ShowSafeHandoff })
        assertTrue(handoff.effects.contains(ScenarioEffect.DeleteResumeCapsule))

        val stopped = reduce(guiding(), ScenarioEvent.StopRequested, 95)
        assertEquals(ScenarioPhase.IDLE, stopped.session.phase)
        assertEquals("", stopped.session.goal)
        assertNull(stopped.session.currentGuidance)
        assertTrue(stopped.effects.containsAll(
            listOf(
                ScenarioEffect.CancelPendingWork,
                ScenarioEffect.StopSpeech,
                ScenarioEffect.ClearHighlight,
                ScenarioEffect.DeleteResumeCapsule,
                ScenarioEffect.ShowIdle,
            )
        ))
    }

    @Test
    fun `only changed manual scrolls count and the third is analyzed before recoverable failure`() {
        var transition = reduce(
            recognized().copy(screenFingerprint = "screen-1"),
            ScenarioEvent.TargetNotFound,
            100,
        )
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, transition.session.phase)
        assertEquals(0, transition.session.scrollAttempts)

        val unchanged = reduce(transition.session, ScenarioEvent.ScreenObserved("screen-1"), 101)
        assertEquals(0, unchanged.session.scrollAttempts)
        assertEquals(transition.session, unchanged.session)

        listOf("screen-2", "screen-3").forEachIndexed { index, fingerprint ->
            transition = reduce(transition.session, ScenarioEvent.ScreenObserved(fingerprint), 102L + index * 2)
            assertEquals(index + 1, transition.session.scrollAttempts)
            assertEquals(ScenarioPhase.ANALYZING_SCREEN, transition.session.phase)
            transition = reduce(transition.session, ScenarioEvent.TargetNotFound, 103L + index * 2)
            assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, transition.session.phase)
        }

        val thirdChanged = reduce(transition.session, ScenarioEvent.ScreenObserved("screen-4"), 106)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, thirdChanged.session.phase)
        assertEquals(3, thirdChanged.session.scrollAttempts)
        assertTrue(thirdChanged.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))

        val exhausted = reduce(thirdChanged.session, ScenarioEvent.TargetNotFound, 107)
        assertEquals(ScenarioPhase.FAILED, exhausted.session.phase)
        assertEquals(3, exhausted.session.scrollAttempts)
        val failure = exhausted.effects.filterIsInstance<ScenarioEffect.ShowRecoverableFailure>().single()
        assertEquals(ScenarioFailureReason.SCROLL_LIMIT, failure.reason)
        assertEquals(
            listOf(ScenarioChoice.REPHRASE, ScenarioChoice.RESTART, ScenarioChoice.STOP),
            failure.choices,
        )
    }

    @Test
    fun `modal close guidance keeps the goal and step while waiting for dismissal`() {
        val initial = recognized().copy(screenFingerprint = "dialog", stepNumber = 2, scrollAttempts = 1)
        val close = ScenarioGuidance("Закройте окно, нажав выделенный крестик.", ScenarioTarget(text = "Закрыть"))

        val waiting = reduce(initial, ScenarioEvent.ModalCloseAvailable(close), 108)

        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, waiting.session.phase)
        assertEquals(initial.goal, waiting.session.goal)
        assertEquals(2, waiting.session.stepNumber)
        assertEquals(1, waiting.session.scrollAttempts)
        assertTrue(waiting.effects.any { it is ScenarioEffect.ShowGuidance })
        assertTrue(waiting.effects.none { it is ScenarioEffect.RequestManualScroll })

        val changed = reduce(waiting.session, ScenarioEvent.ScreenObserved("dismissed"), 109)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, changed.session.phase)
        assertEquals(initial.goal, changed.session.goal)
        assertEquals(1, changed.session.scrollAttempts)
        assertTrue(changed.effects.contains(ScenarioEffect.AnalyzeCurrentScreen))
    }

    @Test
    fun `modal without a close control pauses without consuming a scroll attempt`() {
        val initial = recognized().copy(screenFingerprint = "dialog", scrollAttempts = 2)

        val waiting = reduce(initial, ScenarioEvent.ManualDismissRequired, 108)

        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, waiting.session.phase)
        assertEquals(2, waiting.session.scrollAttempts)
        assertTrue(waiting.effects.contains(ScenarioEffect.RequestManualModalDismiss))
        assertTrue(waiting.effects.none { it is ScenarioEffect.RequestManualScroll })
        assertEquals(waiting.session, reduce(waiting.session, ScenarioEvent.ScreenObserved("dialog"), 109).session)

        val changed = reduce(waiting.session, ScenarioEvent.ScreenObserved("dismissed"), 110)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, changed.session.phase)
        assertEquals(2, changed.session.scrollAttempts)
        assertEquals(initial.goal, changed.session.goal)
    }

    @Test
    fun `TTS failure can retry the last instruction or continue with visual guidance`() {
        val guiding = guiding()

        val failed = reduce(guiding, ScenarioEvent.TtsFailed, 108)
        assertEquals(ScenarioPhase.GUIDING_STEP, failed.session.phase)
        assertEquals(ScenarioNotice.TTS_UNAVAILABLE, decision(failed).notice)

        val retried = reduce(failed.session, ScenarioEvent.RetryRequested, 109)
        assertEquals(ScenarioPhase.GUIDING_STEP, retried.session.phase)
        assertEquals(
            guiding.lastSafeInstruction,
            retried.effects.filterIsInstance<ScenarioEffect.SpeakText>().single().text,
        )
        assertEquals(
            guiding.currentGuidance,
            retried.effects.filterIsInstance<ScenarioEffect.ShowGuidance>().single().guidance,
        )

        val continued = reduce(failed.session, ScenarioEvent.ContinueRequested, 110)
        assertEquals(ScenarioPhase.GUIDING_STEP, continued.session.phase)
        assertEquals(
            guiding.currentGuidance,
            continued.effects.filterIsInstance<ScenarioEffect.ShowGuidance>().single().guidance,
        )
    }

    @Test
    fun `invalid planner response retries exactly once`() {
        val analyzing = recognized()

        val first = reduce(
            analyzing,
            ScenarioEvent.PlannerFailed(ScenarioPlanningFailure.INVALID_RESPONSE),
            110,
        )
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, first.session.phase)
        assertEquals(1, first.session.replanFailures)
        assertEquals(ScenarioPlanningAttempt.FORMAT_REPAIR,
            first.effects.filterIsInstance<ScenarioEffect.RetryPlanning>().single().attempt)

        val second = reduce(
            first.session,
            ScenarioEvent.PlannerFailed(ScenarioPlanningFailure.INVALID_RESPONSE),
            111,
        )
        assertEquals(ScenarioPhase.FAILED, second.session.phase)
        assertEquals(2, second.session.replanFailures)
        assertTrue(second.effects.none { it is ScenarioEffect.RetryPlanning })
        assertEquals(
            ScenarioFailureReason.INVALID_PLANNER_RESPONSE,
            second.effects.filterIsInstance<ScenarioEffect.ShowRecoverableFailure>().single().reason,
        )

        val userRetry = reduce(second.session, ScenarioEvent.RetryRequested, 112)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, userRetry.session.phase)
        assertEquals(0, userRetry.session.replanFailures)
        assertEquals(0, userRetry.session.scrollAttempts)
    }

    @Test
    fun `terminal session ignores late callbacks but still accepts stop cleanup`() {
        val completed = reduce(
            recognized(),
            ScenarioEvent.PlannerAnswered("Готово"),
            115,
        ).session

        val lateSafety = reduce(completed, ScenarioEvent.SafeHandoffRequired, 116)
        val lateCompletion = reduce(completed, ScenarioEvent.GoalCompleted, 117)

        assertEquals(completed, lateSafety.session)
        assertTrue(lateSafety.effects.isEmpty())
        assertEquals(completed, lateCompletion.session)
        assertTrue(lateCompletion.effects.isEmpty())

        val stopped = reduce(completed, ScenarioEvent.StopRequested, 118)
        assertEquals(ScenarioPhase.IDLE, stopped.session.phase)
        assertTrue(stopped.effects.contains(ScenarioEffect.CancelPendingWork))
    }

    @Test
    fun `successful later guide resets retry and scroll counters`() {
        val session = recognized().copy(
            mode = ScenarioMode.GUIDANCE,
            stepNumber = 2,
            scrollAttempts = 2,
            replanFailures = 1,
        )
        val guidance = guidance("screen target")

        val next = reduce(session, ScenarioEvent.PlannerGuided(guidance), 120)

        assertEquals(ScenarioPhase.GUIDING_STEP, next.session.phase)
        assertEquals(3, next.session.stepNumber)
        assertEquals(0, next.session.scrollAttempts)
        assertEquals(0, next.session.replanFailures)
        assertTrue(next.effects.any { it is ScenarioEffect.ShowGuidance })
    }

    private fun recognized(
        goal: String = "Заказать такси",
        targetPackage: String? = "ru.yandex.taxi",
    ): ScenarioSession {
        val started = reduce(ScenarioSession.idle(0), ScenarioEvent.StartRequested, 1).session
        return reduce(
            started,
            ScenarioEvent.GoalRecognized(goal, targetPackage, ambiguous = false),
            2,
        ).session
    }

    private fun guiding(screenFingerprint: String = "screen-1"): ScenarioSession {
        val proposed = reduce(
            recognized().copy(screenFingerprint = screenFingerprint),
            ScenarioEvent.PlannerGuided(guidance("Нажмите «Куда».")),
            3,
        ).session
        return reduce(proposed, ScenarioEvent.GoalConfirmed, 4).session
    }

    private fun guidance(instruction: String) = ScenarioGuidance(
        instruction = instruction,
        target = ScenarioTarget(resourceId = "destination", text = "Куда"),
    )

    private fun decision(transition: ScenarioTransition): ScenarioEffect.ShowDecision =
        transition.effects.filterIsInstance<ScenarioEffect.ShowDecision>().single()

    private fun reduce(
        session: ScenarioSession,
        event: ScenarioEvent,
        nowMs: Long,
    ): ScenarioTransition = ScenarioReducer.reduce(session, event, nowMs)
}
