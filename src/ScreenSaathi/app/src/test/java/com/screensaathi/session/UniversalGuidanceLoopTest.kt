package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.AudioPlayer
import com.screensaathi.sarvam.AudioPlayback
import com.screensaathi.sarvam.PlannerFailureKind
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenViewport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalGuidanceLoopTest {
    @Test
    fun `unusable bounded modal close controls request manual dismissal with zero planner calls`() {
        listOf(
            testRect(10, 110, 30, 130),
            testRect(10, 90, 30, 130),
            testRect(10, 20, 10, 40),
            testRect(10, 20, 30, 20),
        ).forEach { bounds ->
            val close = element(0, "close", "Закрыть", 10).copy(bounds = bounds, inDialog = true)
            val raw = ScreenSnapshot("example.popup", true, listOf(close), activeDialog = true,
                viewport = ScreenViewport(0, 0, 100, 100))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val planner = RepeatingPlanner(answer("unused"))
            val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

            loop.startGoal("Найти товары")

            assertEquals(0, planner.calls)
            assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
            assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualModalDismiss })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.resolvedTarget != null })
        }
    }

    @Test
    fun `eligible targets emit only their visible bounds and preserve snapshot geometry`() {
        val cases = listOf(
            testRect(30, 80, 50, 140) to listOf(30, 80, 50, 120),
            testRect(10, 50, 50, 90) to listOf(20, 50, 50, 90),
            testRect(90, 50, 130, 90) to listOf(90, 50, 120, 90),
            testRect(30, 20, 50, 60) to listOf(30, 40, 50, 60), // exactly half visible
            testRect(30, 50, 50, 90) to listOf(30, 50, 50, 90),
        )
        cases.forEach { (bounds, expected) ->
            val original = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val raw = ScreenSnapshot("example.generic", true, listOf(
                element(0, "target", "Поиск", 30).copy(bounds = bounds)),
                viewport = ScreenViewport(20, 40, 120, 120))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val planner = RepeatingPlanner(guide("Нажмите поиск", "target"))
            val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

            loop.startGoal("Найти товары")
            loop.confirmGoal() // Must validate against the original, unclipped snapshot identity.

            assertEquals(1, planner.calls)
            assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
            val target = emissions.single { it.resolvedTarget != null }.resolvedTarget!!
            assertEquals(expected, listOf(target.left, target.top, target.right, target.bottom))
            assertEquals(raw.signature(), target.fingerprint)
            assertEquals("target", target.resourceId)
            assertTrue(target.snapshotIdentity.isNotBlank())
            assertEquals(original, raw.elements.single().bounds.let {
                listOf(it.left, it.top, it.right, it.bottom)
            })
        }
    }

    @Test
    fun `clipped modal ancestor emits visible bounds without changing ancestry or raw geometry`() {
        val button = element(10, "container_button", "", 30).copy(
            bounds = testRect(30, 80, 60, 140), inDialog = true)
        val label = element(11, "cross", "×", 40).copy(
            bounds = testRect(40, 90, 50, 110), clickable = false, inDialog = true,
            clickableAncestorIndex = 10)
        val raw = ScreenSnapshot("example.popup", true, listOf(button, label), activeDialog = true,
            viewport = ScreenViewport(20, 40, 120, 120))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val planner = RepeatingPlanner(answer("unused"))
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        val target = emissions.single { it.resolvedTarget != null }.resolvedTarget!!
        assertEquals(listOf(30, 80, 60, 120), listOf(target.left, target.top, target.right, target.bottom))
        assertEquals("container_button", target.resourceId)
        assertEquals(raw.signature(), target.fingerprint)
        assertEquals(140, button.bounds.bottom)
        assertEquals(10, label.clickableAncestorIndex)
    }

    @Test fun `shared id child labels keep clickable ancestors while matching exact label`() {
        val origin = element(10, "origin_container", "", 10).copy(
            bounds = testRect(10, 10, 180, 120))
        val destination = element(11, "destination_container", "", 220).copy(
            bounds = testRect(220, 10, 400, 120))
        val labels = listOf(
            element(0, "label", "Откуда", 20).copy(className = "TextView", clickable = false,
                clickableAncestorIndex = 10),
            element(1, "label", "Куда", 240).copy(className = "TextView", clickable = false,
                clickableAncestorIndex = 11),
        )
        val raw = ScreenSnapshot("example.generic", true, labels + listOf(origin, destination,
            element(12, "unrelated", "Куда", 500)))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val planner = RepeatingPlanner(guide("Нажмите поле", "label", "Куда"))
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Найти поле")
        loop.confirmGoal()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        val target = emissions.single { it.resolvedTarget != null }.resolvedTarget!!
        assertEquals("destination_container", target.resourceId)
        assertEquals(listOf(220, 10, 400, 120), listOf(target.left, target.top, target.right, target.bottom))
        assertEquals(1, emissions.count { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test fun `shared id exact label keeps other id fields for spatial normalization`() {
        val raw = ScreenSnapshot("example.generic", true, listOf(
            element(0, "label", "Откуда", 20).copy(className = "TextView", clickable = false,
                bounds = testRect(20, 20, 100, 40)),
            element(1, "label", "Куда", 240).copy(className = "TextView", clickable = false,
                bounds = testRect(240, 20, 320, 40)),
            element(10, "origin_field", "", 10, editable = true).copy(bounds = testRect(10, 60, 180, 120)),
            element(11, "destination_field", "", 220, editable = true).copy(bounds = testRect(220, 60, 400, 120)),
        ))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val planner = RepeatingPlanner(guide("Нажмите поле", "label", "Куда"))
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Найти поле")
        loop.confirmGoal()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        val target = emissions.single { it.resolvedTarget != null }.resolvedTarget!!
        assertEquals("destination_field", target.resourceId)
        assertEquals(listOf(220, 60, 400, 120), listOf(target.left, target.top, target.right, target.bottom))
    }

    @Test fun `shared id intersects exact safe label without trusting planner index or other ids`() {
        val first = element(0, "shared", "", 10, editable = true, hint = "Откуда")
        val second = element(1, "shared", "private value", 200, editable = true, hint = "Куда")
        val cases = listOf(
            listOf(first, second) to null,
            listOf(first.copy(hintText = "Куда"), second) to ScenarioTargetIssue.AMBIGUOUS,
            listOf(first, second.copy(hintText = "Другое"),
                second.copy(index = 2, resourceId = "unrelated")) to ScenarioTargetIssue.NOT_FOUND,
            listOf(first, second.copy(visibleToUser = false),
                first.copy(index = 2, bounds = testRect(400, 20, 500, 80))) to ScenarioTargetIssue.NOT_VISIBLE,
            listOf(first, second.copy(hintText = "Куда дальше")) to ScenarioTargetIssue.NOT_FOUND,
        )
        cases.forEach { (nodes, issue) ->
            val raw = ScreenSnapshot("example.generic", true, nodes)
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val planner = RepeatingPlanner(guide("Нажмите поле", "shared", "Куда"))
            val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)
            loop.startGoal("Найти поле")
            loop.confirmGoal()
            assertEquals(1, planner.calls)
            if (issue == null) {
                assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
                assertEquals(200, emissions.single { it.resolvedTarget != null }.resolvedTarget!!.left)
            } else {
                assertTrue(issue.name, emissions.any { it.effect == ScenarioEffect.ShowTargetIssue(issue) })
                assertTrue(emissions.none { it.resolvedTarget != null })
            }
        }
    }

    @Test
    fun `replacement stop does not overwrite a fresh recording after deactivation`() {
        val ui = mutableListOf<() -> Unit>()
        var active = true
        var idleRenders = 0
        val loop = UniversalGuidanceLoop(RepeatingPlanner(answer("Ответ")),
            { screen("app", "target", "Куда", 10) }, clock(), { emission ->
                emission.dispatch(ui::add, isActive = { active }) {
                    if (it.effect == ScenarioEffect.ShowIdle) idleRenders++
                }
            })
        loop.startGoal("Вопрос")
        loop.stop() // Same operation used when replacing the goal with recording.
        active = false
        ui.forEach { it() }
        assertEquals(0, idleRenders)
    }

    @Test
    fun `speech failure visible command retains useful instruction and highlight with retry controls`() {
        var visible: com.screensaathi.overlay.OverlayCommand? = null
        var useful: String? = null
        val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поле поиска", resourceId = "target")),
            { screen("app", "target", "Куда", 10) }, clock(), { emission ->
                emission.dispatch({ it() }) { delivered ->
                    when (val effect = delivered.effect) {
                        is ScenarioEffect.ShowGuidance -> {
                            val target = delivered.resolvedTarget!!
                            useful = "Шаг 1. ${effect.guidance.instruction}"
                            visible = com.screensaathi.overlay.OverlayCommand(
                                com.screensaathi.overlay.PillState.GUIDING, expanded = true,
                                instruction = useful,
                                highlight = com.screensaathi.overlay.HighlightBounds(
                                    target.left, target.top, target.right, target.bottom),
                            )
                        }
                        is ScenarioEffect.ShowDecision -> visible = scenarioDecisionCommand(
                            effect, "ru-IN", useful, visible?.highlight)
                        else -> Unit
                    }
                }
            })
        loop.startGoal("Найти поле")
        loop.confirmGoal()
        val before = visible!!
        repeat(2) { loop.ttsFailed() }
        val failed = visible!!
        assertTrue(failed.instruction!!.startsWith(before.instruction!!))
        assertTrue(failed.instruction!!.contains("Не удалось озвучить"))
        assertEquals(1, Regex("Шаг 1").findAll(failed.instruction!!).count())
        assertEquals(before.highlight, failed.highlight)
        assertEquals(com.screensaathi.overlay.DecisionButtons("Повторить", "Продолжить"), failed.decisionButtons)
    }

    @Test
    fun `screen invalidation cannot complete between playback lease validation and start`() {
        val current = AtomicReference(screen("app", "target", "Куда", 10))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поле", resourceId = "target")),
            current::get, clock(), emissions::add)
        loop.startGoal("Найти поле")
        loop.confirmGoal()
        val speech = emissions.single { it.effect is ScenarioEffect.SpeakText }
        val starting = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val invalidated = CountDownLatch(1)
        var prepared: () -> Unit = {}
        val clip = java.io.File.createTempFile("opora-audio-test", ".wav")
        val backend = object : AudioPlayback {
            override fun prepare(path: String, onPrepared: () -> Unit, onDone: () -> Unit) {
                prepared = onPrepared
            }
            override fun start() {
                starting.countDown()
                check(proceed.await(2, TimeUnit.SECONDS))
            }
            override fun release() = Unit
        }
        val player = AudioPlayer(clip) { backend }
        player.play(byteArrayOf(1), {}, {}, withCurrent = speech::deliverIfCurrent)
        val playback = Thread { prepared() }.apply { start() }
        var change: Thread? = null
        try {
            assertTrue(starting.await(1, TimeUnit.SECONDS))
            change = Thread {
                current.set(screen("app", "target", "Другое", 100))
                loop.observeScreen(current.get())
                invalidated.countDown()
            }.apply { start() }
            assertFalse("invalidation must serialize with actual start", invalidated.await(100, TimeUnit.MILLISECONDS))
        } finally {
            proceed.countDown()
            playback.join(2000)
            change?.join(2000)
            player.stop()
            clip.delete()
        }
        assertFalse(speech.isCurrent())
    }

    @Test
    fun `decision Stop renders idle after controller deactivation but cannot overwrite a replacement`() {
        listOf(false, true).forEach { replace ->
            val ui = mutableListOf<() -> Unit>()
            var active = true
            var visible: ScenarioEffect? = null
            val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поле", resourceId = "target")),
                { screen("app", "target", "Куда", 10) }, clock(), { emission ->
                    emission.dispatch(ui::add, isActive = { active }) { visible = it.effect }
                })
            loop.startGoal("Найти поле")
            ui.toList().forEach { it() }
            ui.clear()
            assertTrue(visible is ScenarioEffect.ConfirmGoal)
            loop.stop(deliverIdleAfterDeactivation = true)
            active = false // Same ordering as handleScenarioChoice(STOP/CANCEL).
            if (replace) {
                active = true
                loop.startGoal("Другая цель")
            }
            ui.toList().forEach { it() }
            if (replace) assertTrue(visible is ScenarioEffect.ConfirmGoal)
            else assertEquals(ScenarioEffect.ShowIdle, visible)
        }
    }

    @Test
    fun `same generation screen change invalidates queued guidance and speech delivery`() {
        val current = AtomicReference(screen("app", "target", "Куда", 10))
        val queued = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поле", resourceId = "target")),
            current::get, clock(), queued::add)
        loop.startGoal("Найти поле")
        loop.confirmGoal()
        val oldGuidance = queued.single { it.effect is ScenarioEffect.ShowGuidance }
        val oldSpeech = queued.single { it.effect is ScenarioEffect.SpeakText }
        assertTrue(oldSpeech.isCurrent())
        current.set(screen("app", "target", "Другое", 100))
        loop.observeScreen(current.get())
        var rendered = false
        oldGuidance.deliverIfCurrent { rendered = true }
        assertFalse(rendered)
        assertFalse(oldSpeech.isCurrent())
    }

    @Test
    fun `local modal guidance measures resolution without a planner stage`() {
        val events = mutableListOf<ScenarioTelemetryEvent>()
        val trace = ScenarioLatencyTrace(clock(), ScenarioTelemetrySink(events::add))
        val close = element(0, "close_button", "Закрыть", 900).copy(inDialog = true)
        val dialog = ScreenSnapshot("example.popup", true, listOf(close), activeDialog = true)
        val loop = UniversalGuidanceLoop(RepeatingPlanner(answer("unused")), { dialog }, clock(),
            { emission -> emission.deliverIfCurrent { } })
        loop.startGoal("Найти товары", latency = trace)
        assertEquals(listOf(ScenarioLatencyStage.SNAPSHOT, ScenarioLatencyStage.SAFETY,
            ScenarioLatencyStage.TARGET_RESOLUTION, ScenarioLatencyStage.FIRST_USEFUL_RENDER),
            events.map { it.stage })
    }

    @Test
    fun `stop cleanup remains synchronous while visual and speech effects use the UI queue`() {
        val uiQueue = mutableListOf<() -> Unit>()
        val delivered = mutableListOf<ScenarioEffect>()
        val loop = UniversalGuidanceLoop(RepeatingPlanner(answer("Ответ")),
            { screen("app", "target", "Куда", 10) }, clock(), { emission ->
                emission.dispatch(uiQueue::add) { delivered += it.effect }
            })
        loop.startGoal("Вопрос")
        assertTrue(delivered.none { it is ScenarioEffect.ShowAnswer || it is ScenarioEffect.SpeakText })
        loop.stop()
        assertTrue(delivered.contains(ScenarioEffect.CancelPendingWork))
        assertTrue(delivered.contains(ScenarioEffect.StopNetworkMonitoring))
        assertTrue(delivered.contains(ScenarioEffect.DeleteResumeCapsule))
        uiQueue.forEach { it() }
        assertTrue(delivered.none { it is ScenarioEffect.ShowAnswer || it is ScenarioEffect.SpeakText })
    }

    @Test
    fun `queued guidance cannot render after stop even when emission already left the loop`() {
        val queued = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поле", resourceId = "target")),
            { screen("app", "target", "Куда", 10) }, clock(), queued::add)
        loop.startGoal("Найти поле")
        loop.confirmGoal()
        assertTrue(queued.any { it.effect is ScenarioEffect.ShowGuidance })
        loop.stop()
        val delivered = mutableListOf<ScenarioEffect>()
        queued.forEach { emission -> emission.deliverIfCurrent { delivered += emission.effect } }
        assertTrue(delivered.none { it is ScenarioEffect.ShowGuidance || it is ScenarioEffect.SpeakText })
        assertTrue(delivered.contains(ScenarioEffect.ShowIdle))
    }

    @Test
    fun `planner stages count format repair and useful render precedes queued speech`() {
        var now = 10L
        val events = mutableListOf<ScenarioTelemetryEvent>()
        val trace = ScenarioLatencyTrace({ now++ }, ScenarioTelemetrySink(events::add))
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                calls++
                return if (calls == 1) PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema")
                    else answer("Ответ")
            }
        }
        val queued = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner,
            { screen("app", "target", "Куда", 10) }, { now++ }, queued::add)
        loop.startGoal("Вопрос", latency = trace)
        assertTrue(events.none { it.stage == ScenarioLatencyStage.FIRST_USEFUL_RENDER })
        queued.forEach { emission -> emission.deliverIfCurrent {
            when (emission.effect) {
                is ScenarioEffect.ShowAnswer -> assertTrue(events.none {
                    it.stage == ScenarioLatencyStage.FIRST_USEFUL_RENDER
                }) // The milestone must follow the actual render callback.
                is ScenarioEffect.SpeakText -> emission.latency?.ttsStarted()
                else -> Unit
            }
        } }
        assertEquals(listOf(1, 2), events.filter { it.stage == ScenarioLatencyStage.PLANNER }.map { it.attempt })
        assertEquals(listOf(ScenarioLatencyStage.FIRST_USEFUL_RENDER, ScenarioLatencyStage.TTS_START),
            events.takeLast(2).map { it.stage })
        assertTrue(events.all { it.durationMs!! >= 0 })
    }

    @Test
    fun `viewport shrink clears a rendered target and rejects a cached target without label changes`() {
        listOf(false, true).forEach { alreadyGuiding ->
            val raw = ScreenSnapshot("example.app", true, listOf(
                element(0, "target", "Куда", 10).copy(bounds = testRect(10, 100, 200, 180)),
                element(1, "other", "Другое", 300),
            ), viewport = com.screensaathi.screen.ScreenViewport(0, 0, 1080, 200))
            val current = AtomicReference(raw)
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поле", resourceId = "target")),
                { current.get() }, clock(), emissions::add)
            loop.startGoal("Найти поле")
            if (alreadyGuiding) loop.confirmGoal()
            emissions.clear()
            current.set(raw.copy(viewport = com.screensaathi.screen.ScreenViewport(0, 0, 1080, 100)))
            if (alreadyGuiding) loop.observeScreen(current.get()) else loop.confirmGoal()
            assertTrue(emissions.any { it.effect == ScenarioEffect.ClearHighlight })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.resolvedTarget != null })
            assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        }
    }

    @Test
    fun `viewport changing during planning discards old geometry with bounded replan`() {
        val raw = ScreenSnapshot("example.app", true, listOf(
            element(0, "target", "Куда", 10).copy(bounds = testRect(10, 100, 200, 180)),
            element(1, "other", "Другое", 300),
        ), viewport = com.screensaathi.screen.ScreenViewport(0, 0, 1080, 200))
        val current = AtomicReference(raw)
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                calls++
                current.set(raw.copy(viewport = com.screensaathi.screen.ScreenViewport(0, 0, 1080, 100)))
                return guide("Нажмите поле", resourceId = "target")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Найти поле")
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal || it.resolvedTarget != null })
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertEquals(2, calls) // one stale response and one current plan
    }

    @Test
    fun `disabled exact planner label cannot fall back to another loosely matched control`() {
        val raw = ScreenSnapshot("example.app", true, listOf(
            element(0, "", "Поиск", 10).copy(enabled = false),
            element(1, "", "Поиск настроек", 200),
        ))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(RepeatingPlanner(guide("Нажмите поиск", targetText = "Поиск")),
            { raw }, clock(), emissions::add)
        loop.startGoal("Найти поле")
        assertTrue(emissions.any { it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.NOT_ACTIONABLE) })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal || it.resolvedTarget != null })
    }

    @Test
    fun `not visible id and text targets use one call`() = assertLocalTargetIssue("NOT_VISIBLE")

    @Test
    fun `ambiguous id and text targets use one call`() = assertLocalTargetIssue("AMBIGUOUS")

    @Test
    fun `not actionable id and text targets use one call`() = assertLocalTargetIssue("NOT_ACTIONABLE")

    @Test
    fun `not found id and text targets use one call`() = assertLocalTargetIssue("NOT_FOUND")

    private fun assertLocalTargetIssue(expectedIssue: String) {
        val base = element(0, "destination", "Куда", 10)
        val cases = listOf(
            listOf(base.copy(bounds = testRect(10, 2157, 400, 2290))) to "NOT_VISIBLE",
            listOf(base.copy(enabled = false)) to "NOT_ACTIONABLE",
            listOf(base.copy(clickable = false)) to "NOT_ACTIONABLE",
            listOf(base, base.copy(index = 1, bounds = testRect(200, 20, 300, 80))) to "AMBIGUOUS",
            emptyList<ScreenElement>() to "NOT_FOUND",
        )
        cases.filter { it.second == expectedIssue }.forEach { (nodes, issue) ->
            listOf(guide("Нажмите поле.", resourceId = "destination"),
                guide("Нажмите поле.", targetText = "Куда")).forEach { plan ->
                val raw = ScreenSnapshot("example.app", true,
                    nodes + element(9, "other", "Другое", 500),
                    viewport = com.screensaathi.screen.ScreenViewport(0, 0, 1080, 2153))
                val emissions = mutableListOf<UniversalGuidanceEmission>()
                val planner = RepeatingPlanner(plan)
                val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)
                loop.startGoal("Найти поле")
                loop.observeScreen(raw)
                loop.recheckCurrentScreen()
                assertEquals("$issue must not retry a valid response", 1, planner.calls)
                assertEquals(listOf(false), planner.repairFlags)
                assertTrue("Missing typed $issue", emissions.any {
                    it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.valueOf(issue))
                })
                assertTrue(emissions.none { it.resolvedTarget != null || it.effect is ScenarioEffect.ShowGuidance })
                assertEquals(issue == "NOT_VISIBLE", loop.session.phase == ScenarioPhase.WAITING_FOR_MANUAL_SCROLL)
            }
        }
    }

    @Test
    fun `duplicate analysis while the same tree is in flight does not call the planner twice`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
                return guide("Нажмите поле.", resourceId = "destination")
            }
        }
        val emissions = java.util.Collections.synchronizedList(mutableListOf<UniversalGuidanceEmission>())
        val loop = UniversalGuidanceLoop(planner, { stableScreen() }, clock(), emissions::add)
        val worker = Thread { loop.startGoal("Найти поле") }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            loop.observeScreen()
            loop.recheckCurrentScreen()
        } finally {
            release.countDown()
            worker.join(2_000)
        }
        assertFalse(worker.isAlive)
        assertEquals(1, calls.get())
        assertEquals(1, emissions.count { it.effect is ScenarioEffect.ConfirmGoal })
    }

    @Test
    fun `stale malformed response cannot repair a newer in flight attempt`() =
        assertSupersededResponseIsIgnored(
            PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema"),
        )

    @Test
    fun `stale valid response cannot commit guidance over a newer in flight attempt`() =
        assertSupersededResponseIsIgnored(guide("Нажмите старое поле.", resourceId = "destination"))

    private fun assertSupersededResponseIsIgnored(staleOutcome: PlannerOutcome) {
        val original = stableScreen()
        val next = screen("next", "search", "Поиск", 10)
        val current = AtomicReference(original)
        val oldAtFreshnessRead = CountDownLatch(1)
        val releaseOldRead = CountDownLatch(1)
        val newerPlanning = CountDownLatch(1)
        val releaseNewerPlan = CountDownLatch(1)
        val oldReturned = java.util.concurrent.atomic.AtomicBoolean()
        val pausedOldRead = java.util.concurrent.atomic.AtomicBoolean()
        val oldThread = AtomicReference<Thread>()
        val failure = AtomicReference<Throwable>()
        val flags = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                flags += repairFormat
                return when (calls.incrementAndGet()) {
                    1 -> staleOutcome.also { oldReturned.set(true) }
                    2 -> {
                        newerPlanning.countDown()
                        check(releaseNewerPlan.await(5, TimeUnit.SECONDS))
                        guide("Нажмите поиск.", resourceId = "search")
                    }
                    else -> guide("Лишний запрос.", resourceId = "search")
                }
            }
        }
        val emissions = java.util.Collections.synchronizedList(mutableListOf<UniversalGuidanceEmission>())
        val loop = UniversalGuidanceLoop(planner, snapshotProvider = {
            val captured = current.get()
            if (Thread.currentThread() === oldThread.get() && oldReturned.get() &&
                pausedOldRead.compareAndSet(false, true)) {
                oldAtFreshnessRead.countDown()
                check(releaseOldRead.await(5, TimeUnit.SECONDS))
            }
            captured
        }, clock = { 1L }, emit = emissions::add)
        val old = Thread {
            try { loop.startGoal("Найти поле") } catch (error: Throwable) { failure.set(error) }
        }.also { oldThread.set(it) }
        val newer = Thread {
            try { loop.recheckCurrentScreen() } catch (error: Throwable) { failure.set(error) }
        }
        var phaseAfterOld: ScenarioPhase? = null
        var failuresAfterOld = -1
        var confirmationsAfterOld = -1
        old.start()
        try {
            assertTrue(oldAtFreshnessRead.await(5, TimeUnit.SECONDS))
            current.set(next)
            newer.start()
            assertTrue(newerPlanning.await(5, TimeUnit.SECONDS))
            releaseOldRead.countDown()
            old.join(5_000)
            assertFalse(old.isAlive)
            phaseAfterOld = loop.session.phase
            failuresAfterOld = loop.session.replanFailures
            confirmationsAfterOld = emissions.count { it.effect is ScenarioEffect.ConfirmGoal }
        } finally {
            releaseOldRead.countDown()
            releaseNewerPlan.countDown()
            old.join(5_000)
            newer.join(5_000)
        }
        failure.get()?.let { throw AssertionError("Concurrent worker failed", it) }
        assertFalse(newer.isAlive)
        assertEquals(ScenarioPhase.ANALYZING_SCREEN, phaseAfterOld)
        assertEquals(0, failuresAfterOld)
        assertEquals(0, confirmationsAfterOld)
        assertEquals(listOf(false, false), flags.toList())
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        loop.confirmGoal()
        val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals("search", shown.resolvedTarget?.resourceId)
        assertEquals(next.signature(), shown.resolvedTarget?.fingerprint)
    }

    @Test
    fun `format repair is capped at one and its local miss never requests a third call`() {
        listOf(
            PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema"),
            guide("Нажмите поле.", resourceId = "invented"),
        ).forEach { repairedOutcome ->
            val planner = QueuePlanner(
                PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema"),
                repairedOutcome,
            )
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { stableScreen() }, clock(), emissions::add)
            loop.startGoal("Найти поле")
            assertEquals(2, planner.calls)
            assertEquals(listOf(false, true), planner.repairFlags)
            assertEquals(ScenarioPhase.FAILED, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.resolvedTarget != null })
        }
    }

    @Test
    fun `format repair cannot reset the bounded changed tree replan budget`() {
        val screens = listOf(
            screen("one", "one", "Один", 10),
            screen("two", "two", "Два", 20),
            screen("three", "three", "Три", 30),
        )
        val current = AtomicReference(screens[0])
        val flags = mutableListOf<Boolean>()
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                flags += repairFormat
                return when (flags.size) {
                    1 -> {
                        current.set(screens[1])
                        guide("Нажмите элемент.", resourceId = "one")
                    }
                    2 -> PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema")
                    else -> {
                        current.set(screens[2])
                        guide("Нажмите элемент.", resourceId = "three")
                    }
                }
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Найти элемент")
        assertEquals(listOf(false, false, true), flags)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal || it.resolvedTarget != null })
    }

    @Test
    fun `tree changed during format repair gets a fresh primary and its own single repair`() {
        val original = stableScreen()
        val next = screen("next", "search", "Поиск", 10)
        val current = AtomicReference(original)
        val flags = mutableListOf<Boolean>()
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                flags += repairFormat
                if (flags.size == 2) current.set(next)
                return if (flags.size < 4) {
                    PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema")
                } else guide("Нажмите поиск.", resourceId = "search")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Найти поле")
        assertEquals(listOf(false, true, false, true), flags)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        loop.confirmGoal()
        val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals("search", shown.resolvedTarget?.resourceId)
        assertEquals(next.signature(), shown.resolvedTarget?.fingerprint)
    }

    @Test
    fun `visible guidance is invalidated only by a different readable fingerprint`() {
        assertFalse(shouldClearGuidanceHighlight("screen-1", "screen-1"))
        assertFalse(shouldClearGuidanceHighlight("screen-1", ""))
        assertFalse(shouldClearGuidanceHighlight("", "screen-2"))
        assertTrue(shouldClearGuidanceHighlight("screen-1", "screen-2"))
    }

    @Test
    fun `supplied goal skips initial recognition but repeat requests a new capture`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))

        fixture.loop.startGoal("Заказать такси", ambiguous = true)

        assertTrue(fixture.effects().none { it is ScenarioEffect.BeginRecognition })
        fixture.loop.repeatRecognition()
        assertTrue(fixture.effects().last() is ScenarioEffect.BeginRecognition)
    }

    @Test
    fun `one shot answer renders immediately without goal confirmation`() {
        val fixture = Fixture(answer("История заказов находится в разделе «Профиль»."))

        fixture.loop.startGoal("Где история заказов?")

        assertEquals(ScenarioPhase.COMPLETED, fixture.loop.session.phase)
        assertTrue(fixture.effects().any { it is ScenarioEffect.ShowAnswer })
        assertTrue(fixture.effects().any { it is ScenarioEffect.SpeakText })
        assertFalse(fixture.effects().any { it is ScenarioEffect.ConfirmGoal })
        assertNull(fixture.emissions.singleOrNull { it.resolvedTarget != null })
    }

    @Test
    fun `one shot answer offers further help when planner omits it`() {
        val fixture = Fixture(answer("На экране поле для ввода VIN."))

        fixture.loop.startGoal("Какие поля есть на экране?")

        val visible = fixture.effects().filterIsInstance<ScenarioEffect.ShowAnswer>().single().instruction
        val spoken = fixture.effects().filterIsInstance<ScenarioEffect.SpeakText>().single().text
        assertTrue(visible.contains("VIN"))
        assertTrue(visible.contains("Чем ещё помочь?"))
        assertEquals(visible, spoken)
    }

    @Test
    fun `one shot answer does not duplicate existing follow up`() {
        val original = "На экране поле VIN. Скажите, чем помочь дальше."
        val fixture = Fixture(answer(original))

        fixture.loop.startGoal("Какие поля есть на экране?")

        assertEquals(original,
            fixture.effects().filterIsInstance<ScenarioEffect.ShowAnswer>().single().instruction)
    }

    @Test
    fun `dialog close control is highlighted locally without a planner call`() {
        val close = element(0, "close_button", "Закрыть", 900).copy(inDialog = true)
        val dialog = ScreenSnapshot("example.popup", true, listOf(close), activeDialog = true)
        val planner = RepeatingPlanner(answer("unused"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { dialog }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(close.bounds.left, shown.resolvedTarget?.left)
        assertEquals(close.bounds.right, shown.resolvedTarget?.right)
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })
    }

    @Test
    fun `unsettled dialog waits for a fresh safe snapshot before highlighting`() {
        val close = element(0, "close_button", "Закрыть", 900).copy(inDialog = true)
        val current = AtomicReference(
            ScreenSnapshot("example.popup", false, listOf(close), activeDialog = true)
        )
        val planner = RepeatingPlanner(answer("unused"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(ScenarioPhase.ANALYZING_SCREEN, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RecheckModalAfterSettling })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })
        assertEquals(0, planner.calls)

        current.set(current.get().copy(settled = true))
        loop.recheckCurrentScreen()

        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.ShowGuidance })
        assertEquals(0, planner.calls)
    }

    @Test
    fun `dialog without close target waits then resumes same goal after dismissal`() {
        val dialog = ScreenSnapshot(
            "example.popup", true,
            listOf(element(0, "", "Рекламное окно", 100).copy(clickable = false, inDialog = true)),
            activeDialog = true,
        )
        val ordinary = screen("popup", "search", "Поиск", 100)
        val current = AtomicReference(dialog)
        val planner = RepeatingPlanner(guide("Нажмите «Поиск».", resourceId = "search"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        assertEquals("Найти товары", loop.session.goal)
        assertEquals(0, loop.session.scrollAttempts)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualModalDismiss })
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })

        current.set(ordinary)
        loop.observeScreen(ordinary)

        assertEquals(1, planner.calls)
        assertEquals("Найти товары", loop.session.goal)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        assertEquals(0, loop.session.scrollAttempts)
    }

    @Test
    fun `popup appearing mid guidance suspends old step and replans after close`() {
        val initial = screen("stable", "lavka", "Лавка", 100)
        val popup = ScreenSnapshot(
            "example.stable", true,
            listOf(element(0, "close_button", "Закрыть", 900).copy(inDialog = true)),
            activeDialog = true,
        )
        val next = screen("stable", "search", "Поиск", 100)
        val current = AtomicReference(initial)
        val planner = QueuePlanner(
            guide("Нажмите «Лавка».", resourceId = "lavka"),
            guide("Нажмите «Поиск».", resourceId = "search"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти товар в Лавке")
        loop.confirmGoal()
        assertEquals(1, loop.session.stepNumber)

        current.set(popup)
        loop.observeScreen(popup)
        assertEquals(ScenarioPhase.WAITING_FOR_MODAL_DISMISS, loop.session.phase)
        assertEquals(1, planner.calls)
        assertEquals(1, loop.session.stepNumber)
        assertEquals("Найти товар в Лавке", loop.session.goal)
        assertTrue(emissions.last { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget != null)

        current.set(next)
        loop.observeScreen(next)
        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertEquals(2, loop.session.stepNumber)
        assertEquals("Найти товар в Лавке", loop.session.goal)
    }

    @Test
    fun `sensitive field in a dialog pauses before local close guidance`() {
        val dialog = ScreenSnapshot(
            "example.popup", true,
            listOf(
                element(0, "close_button", "Закрыть", 900).copy(inDialog = true),
                element(1, "password", "Пароль", 100, password = true).copy(inDialog = true),
            ),
            activeDialog = true,
        )
        val planner = RepeatingPlanner(answer("unused"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { dialog }, clock(), emissions::add)

        loop.startGoal("Найти товары")

        assertEquals(ScenarioPhase.PAUSED_FOR_AUTH, loop.session.phase)
        assertEquals(0, planner.calls)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `first guide is cached until Start and resolves against its planning snapshot`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))

        fixture.loop.startGoal("Заказать такси")

        assertEquals(ScenarioPhase.CONFIRMING_GOAL, fixture.loop.session.phase)
        assertTrue(fixture.effects().any { it is ScenarioEffect.ConfirmGoal })
        assertFalse(fixture.effects().any { it is ScenarioEffect.ShowGuidance })

        fixture.loop.confirmGoal()

        val shown = fixture.emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(ScenarioPhase.GUIDING_STEP, fixture.loop.session.phase)
        assertEquals(screen("screen-1", "destination", "Куда", 10).signature(), shown.resolvedTarget?.fingerprint)
        assertEquals("destination", shown.resolvedTarget?.resourceId)
        assertEquals(1, fixture.planner.calls)
    }

    @Test
    fun `cached guide is rejected when same signature becomes unsettled or sensitive before Start`() {
        val safe = stableScreen()
        val mutations = listOf(
            safe.copy(settled = false) to ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
            safe.copy(
                elements = safe.elements + element(6, "password", "Пароль", 700, password = true),
            ) to ScenarioPhase.PAUSED_FOR_AUTH,
        )

        mutations.forEach { (mutated, expectedPhase) ->
            assertEquals(safe.signature(), mutated.signature())
            val current = AtomicReference(safe)
            val planner = RepeatingPlanner(guide("Нажмите «Куда».", resourceId = "destination"))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
            loop.startGoal("Заказать такси")
            current.set(mutated)

            loop.confirmGoal()

            assertEquals(expectedPhase, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
            assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
        }
    }

    @Test
    fun `screen change before Start discards cached guide and replans`() {
        val screen1 = screen("screen-1", "destination", "Куда", 10)
        val screen2 = screen("screen-2", "restaurant_search", "Ресторан", 200)
        val current = AtomicReference(screen1)
        val planner = QueuePlanner(
            guide("Нажмите «Куда».", resourceId = "destination"),
            guide("Нажмите «Ресторан».", resourceId = "restaurant_search"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Заказать бургер")
        current.set(screen2)
        loop.observeScreen(screen2)

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        loop.confirmGoal()
        val shown = emissions.last { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(screen2.signature(), shown.resolvedTarget?.fingerprint)
        assertEquals("restaurant_search", shown.resolvedTarget?.resourceId)
    }

    @Test
    fun `screen change while planner is in flight discards the stale response`() {
        val screen1 = screen("screen-1", "destination", "Куда", 10)
        val screen2 = screen("screen-2", "restaurant_search", "Ресторан", 200)
        val current = AtomicReference(screen1)
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                return if (calls == 1) {
                    current.set(screen2)
                    guide("Нажмите «Куда».", resourceId = "destination")
                } else {
                    guide("Нажмите «Ресторан».", resourceId = "restaurant_search")
                }
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Заказать бургер")

        assertEquals(2, calls)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        loop.confirmGoal()
        val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
        assertEquals(screen2.signature(), shown.resolvedTarget?.fingerprint)
        assertEquals("restaurant_search", shown.resolvedTarget?.resourceId)
    }

    @Test
    fun `invented id reports a local miss without requesting a different target`() {
        val label = element(0, "", "Госномер", 45).copy(
            clickable = false,
            bounds = testRect(45, 265, 413, 365),
        )
        val field = element(1, "", "", 45, editable = true).copy(
            bounds = testRect(45, 433, 742, 613),
        )
        val region = field.copy(index = 2, bounds = testRect(748, 433, 1035, 613))
        val raw = ScreenSnapshot("example.plate", true, listOf(label, field, region))
        val planner = QueuePlanner(
            guide("Нажмите поле госномера.", resourceId = "invented_plate_input"),
            guide("Нажмите поле под подписью «Госномер».", targetText = "Госномер"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Указать госномер")

        assertEquals(1, planner.calls)
        assertEquals(listOf(false), planner.repairFlags)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.any { it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.NOT_FOUND) })
        assertTrue(emissions.none { it.effect is ScenarioEffect.RequestManualScroll })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.resolvedTarget != null })
    }

    @Test
    fun `invented id ends in local recovery after one call`() {
        val raw = screen("one", "visible", "Видимая кнопка", 10)
        val planner = QueuePlanner(
            guide("Нажмите кнопку.", resourceId = "invented_one"),
            guide("Нажмите кнопку.", resourceId = "invented_two"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.any { it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.NOT_FOUND) })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `short mention cannot become a planner text target on VIN form`() {
        val raw = ScreenSnapshot("example.vin", true, listOf(
            element(0, "", "Госномер не указан", 45).copy(clickable = false),
            element(1, "", "", 45, editable = true),
        ))
        val planner = RepeatingPlanner(guide("Введите госномер.", targetText = "Госномер"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Указать госномер")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `filled edit value cannot ground a planner text target`() {
        val raw = ScreenSnapshot("example.plate", true, listOf(
            element(0, "", "А777АА777", 45, editable = true),
        ))
        val planner = RepeatingPlanner(guide("Нажмите поле.", targetText = "А777АА777"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `nonactionable container id cannot ground a planner target`() {
        val raw = ScreenSnapshot("example.form", true, listOf(
            element(0, "form_container", "", 45).copy(clickable = false),
        ))
        val planner = RepeatingPlanner(guide("Нажмите форму.", resourceId = "form_container"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `same signature becoming unsettled or sensitive in flight rejects the stale response`() {
        val safe = stableScreen()
        val mutations = listOf(
            safe.copy(settled = false) to ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
            safe.copy(
                elements = safe.elements + element(6, "password", "Пароль", 700, password = true),
            ) to ScenarioPhase.PAUSED_FOR_AUTH,
        )

        mutations.forEach { (mutated, expectedPhase) ->
            assertEquals(safe.signature(), mutated.signature())
            val current = AtomicReference(safe)
            var calls = 0
            val planner = object : GuidancePlanner {
                override fun planNext(
                    screen: PlannerScreen,
                    goal: String,
                    stepNumber: Int,
                    lastSafeInstruction: String?,
                    repairFormat: Boolean,
                ): PlannerOutcome {
                    calls++
                    current.set(mutated)
                    return guide("Нажмите «Куда».", resourceId = "destination")
                }
            }
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

            loop.startGoal("Заказать такси")

            assertEquals(1, calls)
            assertEquals(expectedPhase, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        }
    }

    @Test
    fun `dialog appearing during planning invalidates the old target`() {
        val original = stableScreen()
        val current = AtomicReference(original)
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                if (calls == 1) {
                    current.set(original.copy(activeDialog = true))
                    return guide("Нажмите «Куда».", resourceId = "destination")
                }
                return PlannerOutcome.Failure(PlannerFailureKind.NETWORK, "test network pause")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Заказать такси")

        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `repeated in flight screen churn is bounded and asks for manual scroll`() {
        val screens = listOf(
            screen("screen-1", "one", "Один", 10),
            screen("screen-2", "two", "Два", 20),
            screen("screen-3", "three", "Три", 30),
        )
        val current = AtomicReference(screens[0])
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                if (calls < screens.size) current.set(screens[calls])
                return guide("Нажмите элемент.", resourceId = screen.elements.first().resourceId.orEmpty())
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(2, calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualScroll })
    }

    @Test
    fun `only an exact user selected app card produces a launch effect`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))

        fixture.loop.startGoal("Заказать такси", targetPackage = "ru.yandex.taxi")
        assertEquals(ScenarioPhase.WAITING_FOR_APP, fixture.loop.session.phase)
        assertEquals(0, fixture.planner.calls)

        fixture.loop.selectTargetApp("com.example.invented")
        assertTrue(fixture.effects().none { it is ScenarioEffect.LaunchTargetApp })

        fixture.loop.selectTargetApp("ru.yandex.taxi")
        assertEquals(
            listOf("ru.yandex.taxi"),
            fixture.effects().filterIsInstance<ScenarioEffect.LaunchTargetApp>().map { it.packageName },
        )
    }

    @Test
    fun `physical tap clears before changed screen replans and duplicate events do not advance twice`() {
        val screen1 = screen("screen-1", "destination", "Куда", 10)
        val screen2 = screen("screen-2", "restaurant", "Вкусно — и точка", 200)
        val current = AtomicReference(screen1)
        val planner = QueuePlanner(
            guide("Нажмите «Куда».", resourceId = "destination"),
            guide("Нажмите «Вкусно — и точка».", resourceId = "restaurant"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Заказать бургер")
        loop.confirmGoal()
        emissions.clear()

        loop.targetTapped()
        assertEquals(ScenarioPhase.WAITING_FOR_SCREEN_CHANGE, loop.session.phase)
        assertTrue(emissions.first().effect is ScenarioEffect.ClearHighlight)
        assertEquals(1, planner.calls)

        current.set(screen2)
        loop.observeScreen(screen2)
        loop.observeScreen(screen2)

        assertEquals(2, planner.calls)
        assertEquals(2, loop.session.stepNumber)
        assertEquals(1, emissions.count { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `physical tap on a settled same fingerprint screen analyzes again instead of hanging`() {
        val current = screen("screen-1", "destination", "Куда", 10)
        val planner = QueuePlanner(
            guide("Нажмите «Куда».", resourceId = "destination"),
            guide("Введите адрес вручную.", resourceId = "destination"),
        )
        val loop = UniversalGuidanceLoop(planner, { current }, clock(), emit = {})
        loop.startGoal("Заказать такси")
        loop.confirmGoal()

        loop.targetTapped()
        loop.physicalTapSettled(current)

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertEquals(2, loop.session.stepNumber)
    }

    @Test
    fun `physical tap timeout recovers locally without planning an unsettled tree`() {
        val current = AtomicReference(screen("screen-1", "destination", "Куда", 10))
        val planner = RepeatingPlanner(guide("Нажмите «Куда».", resourceId = "destination"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)
        loop.startGoal("Заказать такси")
        loop.confirmGoal()
        loop.targetTapped()
        current.set(current.get().copy(settled = false))

        loop.physicalTapTimedOut()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.RequestManualScroll })
    }

    @Test
    fun `offscreen target asks for manual scroll and only changed fingerprints count`() {
        fun offscreen(suffix: String) = ScreenSnapshot("example.$suffix", true, listOf(
            element(0, "missing", "Искомое", 10).copy(bounds = testRect(10, 2157, 400, 2290)),
            element(1, "other", "Другое", 500),
        ), viewport = com.screensaathi.screen.ScreenViewport(0, 0, 1080, 2153))
        val current = AtomicReference(offscreen("screen-1"))
        val planner = RepeatingPlanner(guide("Нажмите «Искомое».", resourceId = "missing"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

        loop.startGoal("Найти искомое")
        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.any { it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.NOT_VISIBLE) })

        loop.observeScreen(current.get())
        assertEquals(0, loop.session.scrollAttempts)

        repeat(3) { index ->
            val next = offscreen("screen-${index + 2}")
            current.set(next)
            loop.observeScreen(next)
        }

        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertEquals(3, loop.session.scrollAttempts)
        val failure = emissions.map { it.effect }
            .filterIsInstance<ScenarioEffect.ShowRecoverableFailure>().last()
        assertEquals(ScenarioFailureReason.SCROLL_LIMIT, failure.reason)
        assertEquals(4, planner.calls)
    }

    @Test
    fun `sensitive and final screens short circuit before app routing sanitizer planner speech and highlight`() {
        listOf(
            screen("auth", "password", "Пароль", 10, password = true),
            screen("final", "pay", "Оплатить", 10),
        ).forEach { unsafe ->
            val current = AtomicReference(unsafe)
            val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = unsafe.elements[0].resourceId))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { current.get() }, clock(), emissions::add)

            loop.startGoal(
                "Открыть Яндекс Go",
                targetPackage = "ru.yandex.taxi",
            )

            assertEquals(0, planner.calls)
            assertTrue(emissions.any { it.effect is ScenarioEffect.ClearHighlight })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowTargetAppChoice })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
            assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
            assertTrue(loop.session.phase in setOf(ScenarioPhase.PAUSED_FOR_AUTH, ScenarioPhase.SAFE_HANDOFF))
        }
    }

    @Test
    fun `bank app label blocks before the initial planner call`() {
        val raw = screen("bank-alias", "continue", "Продолжить", 10)
        val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = "continue"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { raw },
            clock = clock(),
            emit = emissions::add,
            appLabelProvider = { "Альфа-Банк" },
        )

        loop.startGoal("Продолжить")

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `bank app label blocks a cached guide at confirmation`() {
        val raw = screen("bank-alias", "continue", "Продолжить", 10)
        val appLabel = AtomicReference<String?>(null)
        val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = "continue"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { raw },
            clock = clock(),
            emit = emissions::add,
            appLabelProvider = { appLabel.get() },
        )
        loop.startGoal("Продолжить")
        appLabel.set("Альфа-Банк")

        loop.confirmGoal()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `bank app label blocks a provider result before it is accepted`() {
        val raw = screen("bank-alias", "continue", "Продолжить", 10)
        val appLabel = AtomicReference<String?>(null)
        val planner = object : GuidancePlanner {
            var calls = 0
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                calls++
                appLabel.set("Альфа-Банк")
                return guide("Нажмите кнопку.", resourceId = "continue")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { raw },
            clock = clock(),
            emit = emissions::add,
            appLabelProvider = { appLabel.get() },
        )

        loop.startGoal("Продолжить")

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
        assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
    }

    @Test
    fun `empty or unsettled trees recover locally before app routing or planner`() {
        listOf(
            ScreenSnapshot("example.app", settled = true, elements = emptyList()),
            screen("loading", "destination", "Куда", 10).copy(settled = false),
            screen("hidden", "destination", "Куда", 10).copy(
                elements = listOf(
                    element(0, "destination", "Куда", 10, visibleToUser = false),
                ),
            ),
        ).forEach { unreadable ->
            val planner = RepeatingPlanner(guide("Нажмите кнопку.", resourceId = "destination"))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { unreadable }, clock(), emissions::add)

            loop.startGoal("Открыть Яндекс Go", targetPackage = "ru.yandex.taxi")

            assertEquals(0, planner.calls)
            assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowTargetAppChoice })
            assertTrue(emissions.none { it.effect is ScenarioEffect.SpeakText })
        }
    }

    @Test
    fun `failed repeated recognition restores repeat or stop decision`() {
        val fixture = Fixture(guide("Нажмите «Куда».", resourceId = "destination"))
        fixture.loop.startGoal("", ambiguous = true)
        fixture.loop.repeatRecognition()

        fixture.loop.recognitionFailed()

        val decision = fixture.effects().filterIsInstance<ScenarioEffect.ShowDecision>().last()
        assertEquals(
            listOf(ScenarioChoice.REPEAT_RECOGNITION, ScenarioChoice.STOP),
            decision.choices,
        )
        assertEquals(ScenarioPhase.RECOGNIZING_GOAL, fixture.loop.session.phase)
    }

    @Test
    fun `planner receives sanitized screen and target resolves only from the same raw snapshot`() {
        val raw = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(
                element(0, "phone", "+7 999 123-45-67", 10, editable = true, hint = "Телефон"),
                element(1, "continue", "Продолжить", 120),
            ),
        )
        val planner = InspectingPlanner(guide("Нажмите «Продолжить».", resourceId = "continue"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")
        loop.confirmGoal()

        assertFalse(planner.lastScreen!!.toPromptText().contains("+7 999 123-45-67"))
        assertTrue(planner.lastScreen!!.toPromptText().contains("Телефон"))
        val target = emissions.single { it.effect is ScenarioEffect.ShowGuidance }.resolvedTarget
        assertNotNull(target)
        assertEquals(raw.signature(), target?.fingerprint)
        assertEquals("continue", target?.resourceId)
    }

    @Test
    fun `planner cannot resolve an invented target onto a hidden raw node`() {
        val raw = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(
                element(0, "visible", "Видимая кнопка", 10),
                element(1, "hidden", "Скрытая кнопка", 120, visibleToUser = false),
            ),
        )
        val planner = RepeatingPlanner(guide("Нажмите скрытую кнопку.", resourceId = "hidden"))
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { raw }, clock(), emissions::add)

        loop.startGoal("Продолжить")

        assertEquals(ScenarioPhase.WAITING_FOR_MANUAL_SCROLL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.resolvedTarget?.resourceId == "hidden" })
    }

    @Test
    fun `Stop invalidates a late planner callback`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                return guide("Нажмите «Куда».", resourceId = "destination")
            }
        }
        val emissions = java.util.Collections.synchronizedList(mutableListOf<UniversalGuidanceEmission>())
        val loop = UniversalGuidanceLoop(planner, { screen("screen-1", "destination", "Куда", 10) }, clock(), emissions::add)
        val worker = Thread { loop.startGoal("Заказать такси") }

        worker.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        loop.stop()
        release.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(ScenarioPhase.IDLE, loop.session.phase)
        assertTrue(emissions.any { it.effect is ScenarioEffect.CancelPendingWork })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ConfirmGoal })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    @Test
    fun `invalid planner response retries once and network does not retry`() {
        val invalidThenValid = QueuePlanner(
            PlannerOutcome.Failure(PlannerFailureKind.INVALID_RESPONSE, "bad schema"),
            guide("Нажмите «Куда».", resourceId = "destination"),
        )
        val loop = UniversalGuidanceLoop(
            invalidThenValid,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emit = {},
        )
        loop.startGoal("Заказать такси")
        assertEquals(2, invalidThenValid.calls)
        assertEquals(listOf(false, true), invalidThenValid.repairFlags)

        val network = RepeatingPlanner(PlannerOutcome.Failure(PlannerFailureKind.NETWORK, "offline"))
        val networkLoop = UniversalGuidanceLoop(
            network,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emit = {},
        )
        networkLoop.startGoal("Заказать такси")
        assertEquals(1, network.calls)
        assertEquals(ScenarioPhase.PAUSED_FOR_NETWORK, networkLoop.session.phase)
    }

    @Test
    fun `guide without a semantic target is malformed and may repair its format once`() {
        val planner = QueuePlanner(
            guide("Нажмите поле."),
            guide("Нажмите поле.", resourceId = "destination"),
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { stableScreen() }, clock(), emissions::add)
        loop.startGoal("Найти поле")
        assertEquals(listOf(false, true), planner.repairFlags)
        assertEquals(ScenarioPhase.CONFIRMING_GOAL, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowTargetIssue })
    }

    @Test
    fun `fake executable planner result is rejected even when marked done`() {
        val base = (guide("Нажать без разрешения.", resourceId = "destination") as PlannerOutcome.Success).plan
        val planner = RepeatingPlanner(
            PlannerOutcome.Success(base.copy(actionType = "click", isDone = true))
        )
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emissions::add,
        )

        loop.startGoal("Продолжить")

        assertEquals(2, planner.calls)
        assertEquals(ScenarioPhase.FAILED, loop.session.phase)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowAnswer })
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance })
    }

    private class Fixture(outcome: PlannerOutcome) {
        val planner = RepeatingPlanner(outcome)
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(
            planner,
            { screen("screen-1", "destination", "Куда", 10) },
            clock(),
            emissions::add,
        )

        fun effects() = emissions.map { it.effect }
    }

    private open class RepeatingPlanner(private val outcome: PlannerOutcome) : GuidancePlanner {
        var calls = 0
        val repairFlags = mutableListOf<Boolean>()
        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            calls++
            repairFlags += repairFormat
            return outcome
        }
    }

    private class QueuePlanner(vararg outcomes: PlannerOutcome) : GuidancePlanner {
        private val queue = ArrayDeque(outcomes.toList())
        var calls = 0
        val repairFlags = mutableListOf<Boolean>()
        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            calls++
            repairFlags += repairFormat
            return queue.removeFirst()
        }
    }

    private class InspectingPlanner(private val outcome: PlannerOutcome) : GuidancePlanner {
        var lastScreen: PlannerScreen? = null
        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            lastScreen = screen
            return outcome
        }
    }

    companion object {
        private fun testRect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

        private fun clock(): () -> Long {
            var now = 0L
            return { ++now }
        }

        private fun answer(text: String) = PlannerOutcome.Success(
            PlannerResult(
                version = 1,
                intent = "goal",
                step = "answer",
                targetResourceId = "",
                targetIndex = -1,
                instruction = text,
                confidence = 0.95,
                reason = "answer",
                language = "ru-RU",
                isDone = true,
                actionType = "answer",
            )
        )

        private fun guide(
            text: String,
            resourceId: String = "",
            targetText: String = "",
        ) = PlannerOutcome.Success(
            PlannerResult(
                version = 1,
                intent = "goal",
                step = "guide",
                targetResourceId = resourceId,
                targetIndex = 0,
                instruction = text,
                confidence = 0.95,
                reason = "next safe target",
                language = "ru-RU",
                targetText = targetText,
                actionType = "guide",
            )
        )

        private fun screen(
            suffix: String,
            resourceId: String,
            text: String,
            left: Int,
            password: Boolean = false,
        ) = ScreenSnapshot(
            packageName = "example.$suffix",
            settled = true,
            elements = listOf(element(0, resourceId, text, left, password = password)),
        )

        private fun element(
            index: Int,
            resourceId: String,
            text: String,
            left: Int,
            editable: Boolean = false,
            hint: String = "",
            password: Boolean = false,
            visibleToUser: Boolean = true,
        ) = ScreenElement(
            index = index,
            resourceId = resourceId,
            text = text,
            className = if (editable) "EditText" else "Button",
            bounds = testRect(left, 20, left + 100, 80),
            editable = editable,
            clickable = !editable,
            hintText = hint,
            password = password,
            visibleToUser = visibleToUser,
        )

        private fun stableScreen() = ScreenSnapshot(
            packageName = "example.stable",
            settled = true,
            elements = listOf(
                element(0, "destination", "Куда", 10),
                element(1, "one", "Элемент один", 120),
                element(2, "two", "Элемент два", 230),
                element(3, "three", "Элемент три", 340),
                element(4, "four", "Элемент четыре", 450),
                element(5, "five", "Элемент пять", 560),
            ),
        )
    }
}
