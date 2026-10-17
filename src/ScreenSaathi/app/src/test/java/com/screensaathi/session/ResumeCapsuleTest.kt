package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeCapsuleTest {

    @Test
    fun `capsule serializes exactly the approved minimal fields`() {
        val session = resumableSession().copy(
            screenFingerprint = "fingerprint-secret",
            currentGuidance = ScenarioGuidance(
                instruction = "transient-guidance-secret",
                target = ScenarioTarget(resourceId = "resource-secret", text = "label-secret"),
            ),
            scrollAttempts = 9,
            replanFailures = 7,
            networkAutoRetries = 1,
            preserveReplanFailures = true,
        )

        val capsule = ResumeCapsule.fromSession(
            session = session,
            savedWallTimeMs = 1_700_000_000_000,
            savedElapsedRealtimeMs = 42_000,
        )
        val encoded = ResumeCapsuleCodec.encode(capsule)
        val json = JSONObject(encoded)

        assertEquals(
            setOf(
                "goal",
                "target_package",
                "mode",
                "step_number",
                "last_safe_instruction",
                "saved_wall_time_ms",
                "saved_elapsed_realtime_ms",
            ),
            json.keys().asSequence().toSet(),
        )
        assertFalse(encoded.contains("fingerprint-secret"))
        assertFalse(encoded.contains("transient-guidance-secret"))
        assertFalse(encoded.contains("resource-secret"))
        assertFalse(encoded.contains("label-secret"))
        assertFalse(encoded.contains("bounds", ignoreCase = true))
        assertFalse(encoded.contains("highlight", ignoreCase = true))
        assertFalse(encoded.contains("audio", ignoreCase = true))
        assertFalse(encoded.contains("contact", ignoreCase = true))
        assertEquals(capsule, ResumeCapsuleCodec.decode(encoded))
    }

    @Test
    fun `codec preserves nullable approved fields without inventing state`() {
        val capsule = ResumeCapsule(
            goal = "Открыть настройки",
            targetPackage = null,
            mode = ScenarioMode.GUIDANCE,
            stepNumber = 3,
            lastSafeInstruction = null,
            savedWallTimeMs = 1_700_000_000_000,
            savedElapsedRealtimeMs = 42_000,
        )

        assertEquals(capsule, ResumeCapsuleCodec.decode(ResumeCapsuleCodec.encode(capsule)))
    }

    @Test
    fun `codec rejects corrupt unknown and structurally invalid capsules`() {
        assertNull(ResumeCapsuleCodec.decode("not-json"))
        assertNull(
            ResumeCapsuleCodec.decode(
                ResumeCapsuleCodec.encode(validCapsule()).dropLast(1),
            )
        )
        assertNull(
            ResumeCapsuleCodec.decode(
                JSONObject(ResumeCapsuleCodec.encode(validCapsule()))
                    .put("screen_tree", "must-not-exist")
                    .toString(),
            )
        )
        assertNull(
            ResumeCapsuleCodec.decode(
                JSONObject(ResumeCapsuleCodec.encode(validCapsule()))
                    .put("mode", "UNKNOWN")
                    .toString(),
            )
        )
        assertNull(
            ResumeCapsuleCodec.decode(
                JSONObject(ResumeCapsuleCodec.encode(validCapsule()))
                    .put("step_number", 0)
                    .toString(),
            )
        )
        assertNull(
            ResumeCapsuleCodec.decode(
                JSONObject(ResumeCapsuleCodec.encode(validCapsule()))
                    .put("goal", 123)
                    .toString(),
            )
        )
        assertNull(
            ResumeCapsuleCodec.decode(
                JSONObject(ResumeCapsuleCodec.encode(validCapsule()))
                    .put("step_number", "2")
                    .toString(),
            )
        )
    }

    @Test
    fun `resume reducer starts from live analysis without restoring a target or fingerprint`() {
        val transition = ScenarioReducer.reduce(
            ScenarioSession.idle(1),
            ScenarioEvent.ResumeRequested(validCapsule()),
            nowMs = 2,
        )

        assertEquals(ScenarioPhase.ANALYZING_SCREEN, transition.session.phase)
        assertEquals("Заказать такси", transition.session.goal)
        assertEquals("com.example.taxi", transition.session.targetPackage)
        assertEquals(2, transition.session.stepNumber)
        assertEquals("Нажмите пункт назначения", transition.session.lastSafeInstruction)
        assertNull(transition.session.screenFingerprint)
        assertNull(transition.session.currentGuidance)
        assertEquals(listOf(ScenarioEffect.AnalyzeCurrentScreen), transition.effects)
    }

    @Test
    fun `resume reevaluates the live safety gate before any planner call`() {
        val planner = RecordingPlanner()
        val live = ScreenSnapshot(
            packageName = "example.app",
            settled = true,
            elements = listOf(element("pay", "Оплатить")),
        )
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { live },
            clock = { 100L },
            emit = {},
        )

        loop.resume(validCapsule())

        assertEquals(0, planner.calls)
        assertEquals(ScenarioPhase.SAFE_HANDOFF, loop.session.phase)
        assertNull(loop.session.currentGuidance)
        assertEquals(live.signature(), loop.session.screenFingerprint)
    }

    @Test
    fun `resume replans from the live tree and advances after the stored safe step`() {
        val planner = RecordingPlanner()
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val live = ScreenSnapshot(
            packageName = "com.example.taxi",
            settled = true,
            elements = listOf(element("destination", "Куда")),
        )
        val loop = UniversalGuidanceLoop(
            planner = planner,
            snapshotProvider = { live },
            clock = { 100L },
            emit = emissions::add,
        )

        loop.resume(validCapsule())

        assertEquals(1, planner.calls)
        assertEquals(3, planner.seenStep)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertEquals(3, loop.session.stepNumber)
        assertEquals(live.signature(), loop.session.screenFingerprint)
        assertTrue(emissions.any { it.effect is ScenarioEffect.ShowGuidance })
    }

    private class RecordingPlanner : GuidancePlanner {
        var calls = 0
        var seenStep = -1

        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            calls++
            seenStep = stepNumber
            return PlannerOutcome.Success(
                PlannerResult(
                    version = 1,
                    intent = "goal",
                    step = "guide",
                    targetResourceId = "destination",
                    targetIndex = 0,
                    instruction = "Нажмите поле Куда",
                    confidence = 0.95,
                    reason = "visible target",
                    language = "ru-RU",
                    actionType = "guide",
                )
            )
        }
    }

    private fun validCapsule() = ResumeCapsule(
        goal = "Заказать такси",
        targetPackage = "com.example.taxi",
        mode = ScenarioMode.GUIDANCE,
        stepNumber = 2,
        lastSafeInstruction = "Нажмите пункт назначения",
        savedWallTimeMs = 1_700_000_000_000,
        savedElapsedRealtimeMs = 42_000,
    )

    private fun resumableSession() = ScenarioSession(
        goal = "Заказать такси",
        targetPackage = "com.example.taxi",
        mode = ScenarioMode.GUIDANCE,
        phase = ScenarioPhase.GUIDING_STEP,
        stepNumber = 2,
        lastSafeInstruction = "Нажмите пункт назначения",
        scrollAttempts = 0,
        replanFailures = 0,
        createdAtMs = 1,
        updatedAtMs = 2,
    )

    private fun element(resourceId: String, text: String) = ScreenElement(
        index = 0,
        resourceId = resourceId,
        text = text,
        className = "Button",
        bounds = Rect(10, 20, 110, 80),
        editable = false,
        clickable = true,
        visibleToUser = true,
    )
}
