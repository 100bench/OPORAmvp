package com.screensaathi.evals

import com.screensaathi.sarvam.*
import com.screensaathi.screen.*
import com.screensaathi.session.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Offline replay: provider output is authored, never a claim about live model quality. */
@RunWith(Parameterized::class)
class RealUiTreeRegressionTest(private val name: String, private val case: JSONObject) {
    @Test fun replay() {
        val encoded = resource("ui_trees/real/" + case.getString("file"))
        val root = JSONObject(encoded)
        assertEquals(case.getString("capture_kind"), root.getString("captureKind"))
        val snapshot = UiTreeFixtureCodec.decode(encoded)
        assertNotNull(snapshot.viewport)
        assertTrue(snapshot.elements.filter { it.editable }.all {
            it.text.isEmpty() && it.contentDescription.isEmpty()
        })
        assertEquals(case.getString("disposition"), ScreenSafetyGate.assess(snapshot).disposition.name)
        val targetId = case.getString("target_resource_id")
        val targetText = case.optString("target_text")
        val resolved = if (targetId.isNotEmpty()) TargetResolver.resolveResourceId(targetId, snapshot)
            else TargetResolver.resolveLabel(targetText, snapshot.copy(elements = snapshot.elements.map {
                if (it.editable) it.copy(text = it.hintText) else it
            }))
        assertEquals(case.getString("resolver"), resolved.javaClass.simpleName)
        if (resolved is TargetResolver.Result.Found) {
            assertEquals(case.getInt("target_index"), resolved.candidate.element.index)
            assertEquals(case.optString("expected_target_resource_id", targetId), resolved.candidate.element.resourceId)
            assertEquals(ScreenElementEligibility.Eligible,
                resolved.candidate.element.eligibility(snapshot.viewportOrLegacy))
        }

        // Inject a synthetic editable value into the same captured structure: the real
        // sanitizer must exclude it even if a future replay fixture accidentally has it.
        val secret = "PRIVATE_EDITABLE_SENTINEL"
        val replay = snapshot.copy(elements = snapshot.elements.map {
            if (it.editable) it.copy(text = secret) else it
        })
        var calls = 0
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                calls++
                assertEquals(ScreenDisposition.ALLOW, ScreenSafetyGate.assess(replay).disposition)
                assertFalse(screen.toPromptText().contains(secret))
                assertFalse(screen.toPromptText().contains("editableValueEmpty"))
                val eligible = replay.elements.filter {
                    it.contextEligibility(replay.viewportOrLegacy) == ScreenElementEligibility.Eligible
                }.map { it.index }.toSet()
                assertTrue(screen.elements.all { it.index in eligible })
                return PlannerOutcome.Success(PlannerResult(1, "goal", "fixture", targetId,
                    case.optInt("planner_index", case.getInt("target_index")), "Нажмите выделенное поле.", 0.99,
                    "offline " + case.getString("capture_kind"), targetText = targetText))
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { replay }, emit = emissions::add)
        loop.startGoal("Помоги продолжить")
        if (loop.session.phase == ScenarioPhase.CONFIRMING_GOAL) loop.confirmGoal()
        assertEquals(name, case.getString("phase"), loop.session.phase.name)
        assertEquals(name, case.getInt("max_planner_calls"), calls)
        assertTrue(emissions.none { it.effect is ScenarioEffect.LaunchTargetApp })
        val recovery = case.getString("recovery")
        val renderedTargets = emissions.mapNotNull { it.resolvedTarget }
        assertEquals(name, if (recovery == "guidance") 1 else 0, renderedTargets.size)
        assertTrue("Missing recovery: $recovery", emissions.any {
            when (recovery) {
                "guidance" -> it.effect is ScenarioEffect.ShowGuidance
                "manual_modal" -> it.effect is ScenarioEffect.RequestManualModalDismiss
                "handoff" -> it.effect is ScenarioEffect.ShowSafeHandoff
                "auth" -> it.effect is ScenarioEffect.ShowDecision &&
                    loop.session.phase == ScenarioPhase.PAUSED_FOR_AUTH
                "target_not_found" -> it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.NOT_FOUND)
                "target_ambiguous" -> it.effect == ScenarioEffect.ShowTargetIssue(ScenarioTargetIssue.AMBIGUOUS)
                else -> false
            }
        })
        val viewport = snapshot.viewportOrLegacy
        renderedTargets.forEach {
            assertEquals(case.optString("expected_target_resource_id", targetId), it.resourceId)
            val expectedBounds = snapshot.elements.single { node -> node.index == case.getInt("target_index") }.bounds
            assertEquals(listOf(expectedBounds.left, expectedBounds.top, expectedBounds.right, expectedBounds.bottom),
                listOf(it.left, it.top, it.right, it.bottom))
            assertTrue(it.left >= viewport.left && it.top >= viewport.top)
            assertTrue(it.right <= viewport.right && it.bottom <= viewport.bottom)
        }
        if (recovery !in setOf("guidance")) {
            assertTrue(emissions.none { it.resolvedTarget != null || it.effect is ScenarioEffect.ShowGuidance })
        }
    }

    companion object {
        private fun resource(path: String) = checkNotNull(
            RealUiTreeRegressionTest::class.java.classLoader?.getResourceAsStream(path)
        ) { "Missing fixture: $path" }.bufferedReader().use { it.readText() }

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val cases = JSONArray(resource("ui_trees/real/cases.json"))
            return (0 until cases.length()).map { i ->
                val case = cases.getJSONObject(i)
                arrayOf(case.getString("capture_kind") + ": " + case.getString("file") +
                    " " + case.getString("recovery") + if (case.has("planner_index")) " id+label" else "", case)
            }
        }
    }
}
