package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BankSafetyIntegrationTest {

    @Test
    fun `PIN slots and generic digit keypad stop before planner recorder and speech`() {
        fun node(index: Int, id: String, text: String, clickable: Boolean) = ScreenElement(
            index = index, resourceId = id, text = text, className = "Button",
            bounds = Rect().apply { right = 100; bottom = 100 },
            editable = false, clickable = clickable,
        )
        val screen = ScreenSnapshot("ordinary.example", true,
            listOf(node(0, "pin_slot", "", false)) + (0..9).map { node(it + 1, "", it.toString(), true) },
            viewport = ScreenViewport(0, 0, 200, 200),
        )
        var plannerCalls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                plannerCalls++
                throw AssertionError("PIN entry must stop locally")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { screen }, emit = emissions::add)
        loop.startGoal("Продолжить")
        loop.confirmGoal()
        assertEquals(ScenarioPhase.PAUSED_FOR_AUTH, loop.session.phase)
        assertEquals(0, plannerCalls)
        assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.effect is ScenarioEffect.SpeakText })
        assertTrue(VoiceCaptureSafetyBoundary { ScreenSafetyGate.assess(screen) }.runIfAllowed {
            throw AssertionError("Recorder and STT must not start")
        } != null)
    }

    @Test
    fun `visible disabled authentication evidence stops before planner or speech`() {
        listOf("Password", "SMS code", "Enter PIN").forEach { label ->
            var plannerCalls = 0
            val planner = object : GuidancePlanner {
                override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                    lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                    plannerCalls++
                    throw AssertionError("Authentication must stop locally")
                }
            }
            val screen = ScreenSnapshot("ordinary.example", true, listOf(ScreenElement(
                index = 0, resourceId = "caption", text = label, className = "TextView",
                bounds = Rect().apply { right = 100; bottom = 100 },
                editable = false, clickable = false, enabled = false,
            )), viewport = ScreenViewport(0, 0, 200, 200))
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { screen }, emit = emissions::add)
            loop.startGoal("Продолжить")
            loop.confirmGoal()
            assertEquals(label, ScenarioPhase.PAUSED_FOR_AUTH, loop.session.phase)
            assertEquals(0, plannerCalls)
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.effect is ScenarioEffect.SpeakText })
            assertTrue(VoiceCaptureSafetyBoundary { ScreenSafetyGate.assess(screen) }.runIfAllowed {
                throw AssertionError("Recorder and STT must not start")
            } != null)
        }
    }

    @Test
    fun `capture generation cannot advance after protected screen invalidates it`() {
        var snapshot = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = emptyList(),
        )
        val boundary = VoiceCaptureSafetyBoundary { ScreenSafetyGate.assess(snapshot) }

        val token = boundary.beginCapture()
        assertEquals(VoiceCapturePhase.STARTING, boundary.phase)
        assertTrue(boundary.markRecording(token))
        assertTrue(boundary.beginStopping(token))
        assertEquals(VoiceCapturePhase.STOPPING, boundary.phase)

        snapshot = snapshot.copy(packageName = "ru.sberbankmobile")
        boundary.invalidateCapture()

        assertEquals(VoiceCapturePhase.IDLE, boundary.phase)
        assertTrue(!boundary.markProcessing(token))
        assertTrue(!boundary.isCurrent(token))
    }

    @Test
    fun `completion from an old recognition cannot clear a newer recording`() {
        val safe = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = emptyList(),
        )
        val boundary = VoiceCaptureSafetyBoundary { ScreenSafetyGate.assess(safe) }

        val old = boundary.beginCapture()
        assertTrue(boundary.markRecording(old))
        assertTrue(boundary.beginStopping(old))
        assertTrue(boundary.markProcessing(old))

        val current = boundary.beginCapture()
        assertTrue(boundary.markRecording(current))

        assertTrue(!boundary.finish(old))
        assertTrue(!boundary.invalidateCapture(old))
        assertTrue(boundary.isCurrent(current, VoiceCapturePhase.RECORDING))
        assertEquals(VoiceCapturePhase.RECORDING, boundary.phase)
    }

    @Test
    fun `protected event queued during begin wins after atomic capture publication`() {
        val safe = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = emptyList(),
        )
        val beginHasReadSafety = CountDownLatch(1)
        val allowBeginToPublish = CountDownLatch(1)
        val invalidationQueued = CountDownLatch(1)
        val boundary = VoiceCaptureSafetyBoundary {
            val assessment = ScreenSafetyGate.assess(safe)
            beginHasReadSafety.countDown()
            assertTrue(allowBeginToPublish.await(2, TimeUnit.SECONDS))
            assessment
        }
        val state = VoiceCaptureState(boundary)
        var start: VoiceCaptureStart? = null

        val beginThread = Thread { start = state.begin(turn = 7) }
        beginThread.start()
        assertTrue(beginHasReadSafety.await(2, TimeUnit.SECONDS))

        val protectedEvent = Thread {
            invalidationQueued.countDown()
            state.invalidateAll()
        }
        protectedEvent.start()
        assertTrue(invalidationQueued.await(2, TimeUnit.SECONDS))
        allowBeginToPublish.countDown()

        beginThread.join(2_000)
        protectedEvent.join(2_000)

        assertTrue(start is VoiceCaptureStart.Started)
        assertEquals(null, state.current())
        assertTrue(!state.isListening())
        assertEquals(VoiceCapturePhase.IDLE, boundary.phase)
    }

    @Test
    fun `fresh safety check blocks STT when screen becomes bank after recording`() {
        var snapshot = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = emptyList(),
        )
        var recorderStarts = 0
        var sttCalls = 0
        val boundary = VoiceCaptureSafetyBoundary { ScreenSafetyGate.assess(snapshot) }

        assertEquals(null, boundary.runIfAllowed { recorderStarts++ })
        snapshot = snapshot.copy(packageName = "ru.sberbankmobile")
        val assessment = boundary.runIfAllowed { sttCalls++ }

        assertEquals(1, recorderStarts)
        assertEquals(0, sttCalls)
        assertEquals(ScreenDisposition.SAFE_HANDOFF, assessment?.disposition)
    }

    @Test
    fun `voice boundary blocks recorder STT and TTS before a bank capture starts`() {
        var recorderStarts = 0
        var sttCalls = 0
        var ttsCalls = 0
        val bank = ScreenSnapshot(
            packageName = "ru.sberbankmobile",
            settled = true,
            elements = emptyList(),
        )
        val boundary = VoiceCaptureSafetyBoundary {
            ScreenSafetyGate.assess(bank)
        }

        val assessment = boundary.runIfAllowed {
            recorderStarts++
            sttCalls++
            ttsCalls++
        }

        assertEquals(ScreenDisposition.SAFE_HANDOFF, assessment?.disposition)
        assertEquals(0, recorderStarts)
        assertEquals(0, sttCalls)
        assertEquals(0, ttsCalls)
    }

    @Test
    fun `voice boundary aborts and deletes an active capture on bank transition`() {
        var stopped = 0
        var deleted = 0
        var sttCalls = 0
        var ttsCalls = 0
        val bank = ScreenSnapshot(
            packageName = "ru.sberbankmobile",
            settled = true,
            elements = emptyList(),
        )
        val boundary = VoiceCaptureSafetyBoundary {
            ScreenSafetyGate.assess(bank)
        }

        val assessment = boundary.abortIfUnsafe(
            stopCapture = { stopped++ },
            deleteCapture = { deleted++ },
        )
        if (assessment == null) {
            sttCalls++
            ttsCalls++
        }

        assertEquals(ScreenDisposition.SAFE_HANDOFF, assessment?.disposition)
        assertEquals(1, stopped)
        assertEquals(1, deleted)
        assertEquals(0, sttCalls)
        assertEquals(0, ttsCalls)
    }

    @Test
    fun `known bank stops locally before planner recording or speech`() {
        var plannerCalls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome {
                plannerCalls++
                throw AssertionError("A banking UI tree must never reach the network planner")
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val bank = ScreenSnapshot(
            packageName = "ru.sberbankmobile",
            settled = true,
            elements = emptyList(),
        )
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { bank },
            clock = { 1_000L },
            emit = emissions::add,
        )

        loop.startGoal("Перевести деньги")

        val effects = emissions.map { it.effect }
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertEquals(ScenarioMode.SAFE_HANDOFF, loop.session.mode)
        assertEquals(0, plannerCalls)
        assertTrue(effects.any { it == ScenarioEffect.ClearHighlight })
        assertTrue(effects.any { it is ScenarioEffect.ShowSafeHandoff })
        assertTrue(effects.none { it == ScenarioEffect.BeginRecognition })
        assertTrue(effects.none { it is ScenarioEffect.SpeakText })
        assertTrue(effects.none { it is ScenarioEffect.ShowGuidance })

        val localMessage = ScreenSafetyGate.assess(bank).explanation
        assertTrue(localMessage.contains("банковское приложение", ignoreCase = true))
        assertTrue(localMessage.contains("не отправляются", ignoreCase = true))
    }

    @Test
    fun `repeat recognition stop emits every cleanup effect before handoff`() {
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val planner = object : GuidancePlanner {
            override fun planNext(
                screen: PlannerScreen,
                goal: String,
                stepNumber: Int,
                lastSafeInstruction: String?,
                repairFormat: Boolean,
            ): PlannerOutcome = throw AssertionError("Ambiguous recognition must not plan")
        }
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = {
                ScreenSnapshot(packageName = "example.app", settled = true, elements = emptyList())
            },
            clock = { 1_000L },
            emit = emissions::add,
        )
        loop.startGoal("", ambiguous = true)
        loop.repeatRecognition()
        emissions.clear()

        loop.stop()

        val effects = emissions.map { it.effect }
        assertTrue(ScenarioEffect.CancelPendingWork in effects)
        assertTrue(ScenarioEffect.StopNetworkMonitoring in effects)
        assertTrue(ScenarioEffect.StopSpeech in effects)
        assertTrue(ScenarioEffect.ClearHighlight in effects)
        assertTrue(ScenarioEffect.DeleteResumeCapsule in effects)
    }
}
