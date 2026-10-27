package com.screensaathi.evals

import android.graphics.Rect
import com.screensaathi.sarvam.GuidancePlanner
import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.PlannerResult
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenViewport
import com.screensaathi.session.ScenarioEffect
import com.screensaathi.session.ScenarioPhase
import com.screensaathi.session.ScenarioTargetIssue
import com.screensaathi.session.UniversalGuidanceEmission
import com.screensaathi.session.UniversalGuidanceLoop
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalGuidanceAcceptanceTest {

    @Test
    fun `valid fixture plan with a local target issue makes only one planner call`() {
        val base = fixture("ui_trees/yandex_go/destination.json")
        val target = base.snapshot.elements.first { it.resourceId == base.plan.targetResourceId }
        val other = target.copy(index = 90, resourceId = "other", text = "Другое", contentDescription = "Другое")
        val cases = listOf(
            listOf(target.copy(visibleToUser = false)) to ScenarioTargetIssue.NOT_VISIBLE,
            listOf(target, target.copy(index = 91, bounds = Rect().apply {
                left = 48; top = 500; right = 1032; bottom = 630
            })) to ScenarioTargetIssue.AMBIGUOUS,
            listOf(target.copy(enabled = false)) to ScenarioTargetIssue.NOT_ACTIONABLE,
            emptyList<ScreenElement>() to ScenarioTargetIssue.NOT_FOUND,
        )
        cases.forEach { (targets, issue) ->
            val snapshot = base.snapshot.copy(elements = targets + other)
            val planner = FixturePlanner(base.plan)
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { snapshot }, emit = emissions::add)

            loop.startGoal(base.goal)
            loop.observeScreen(snapshot)
            loop.recheckCurrentScreen()

            assertEquals(issue.name, 1, planner.calls)
            assertTrue(issue.name, emissions.any { it.effect == ScenarioEffect.ShowTargetIssue(issue) })
            assertTrue(emissions.none { it.effect is ScenarioEffect.ShowGuidance || it.resolvedTarget != null })
        }
    }

    @Test
    fun `synthetic address button with weak map PIN chrome reaches one guide`() {
        val base = fixture("ui_trees/yandex_go/destination.json")
        val target = base.snapshot.elements.first { it.resourceId == base.plan.targetResourceId }
        for (role in listOf("android.widget.Button", "android.widget.TextView")) {
            val screen = base.snapshot.copy(packageName = "ordinary.route.example", elements = listOf(
                target.copy(className = role, editable = false, clickable = true),
                target.copy(index = 50, resourceId = "source_pin", text = "", editable = false, clickable = false),
                target.copy(index = 51, resourceId = "pin_slot", text = "", editable = false, clickable = false),
            ))
            val planner = FixturePlanner(base.plan)
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { screen }, emit = emissions::add)
            loop.startGoal(base.goal)
            loop.confirmGoal()
            assertEquals(role, ScenarioPhase.GUIDING_STEP, loop.session.phase)
            assertEquals(1, planner.calls)
            assertEquals(1, emissions.count { it.effect is ScenarioEffect.ShowGuidance })
        }
    }

    @Test
    fun `every acceptance tree uses one safe app agnostic guidance path`() {
        fixtures().forEach { fixture ->
            assertEquals("synthetic", fixture.captureKind)
            println("capture_kind=synthetic case=" + fixture.id)
            assertTrue(
                "${fixture.id}: fixture planner output must remain read-only",
                fixture.plan.actionType in setOf("guide", "answer"),
            )
            val planner = FixturePlanner(fixture.plan)
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(
                planner = planner,
                snapshotProvider = { fixture.snapshot },
                emit = emissions::add,
                appLabelProvider = { fixture.appLabel },
            )

            loop.startGoal(fixture.goal)
            if (fixture.expectedPhase == ScenarioPhase.GUIDING_STEP) loop.confirmGoal()

            assertEquals(fixture.id, fixture.expectedPhase, loop.session.phase)
            assertEquals(fixture.id, fixture.expectedPlannerCalls, planner.calls)
            assertTrue(
                "${fixture.id}: no more than one local target may be rendered",
                emissions.count { it.resolvedTarget != null } <= 1,
            )
            assertFalse(
                "${fixture.id}: fixture guidance must never launch an app",
                emissions.any { it.effect is ScenarioEffect.LaunchTargetApp },
            )
            assertFalse(
                "${fixture.id}: provider contract is read-only",
                planner.receivedActionTypes.any { it !in setOf("guide", "answer") },
            )
            fixture.forbiddenProviderValues.forEach { forbidden ->
                assertFalse(
                    "${fixture.id}: editable/raw value crossed the sanitizer boundary",
                    planner.lastScreen?.toPromptText().orEmpty().contains(forbidden),
                )
            }

            when (fixture.expectedPhase) {
                ScenarioPhase.GUIDING_STEP -> {
                    val shown = emissions.single { it.effect is ScenarioEffect.ShowGuidance }
                    assertEquals(fixture.id, fixture.plan.targetResourceId, shown.resolvedTarget?.resourceId)
                }
                ScenarioPhase.PAUSED_FOR_AUTH,
                ScenarioPhase.SAFE_HANDOFF,
                ScenarioPhase.WAITING_FOR_MANUAL_SCROLL,
                -> assertTrue(
                    "${fixture.id}: unsafe/unusable tree must not render guidance",
                    emissions.none { it.effect is ScenarioEffect.ShowGuidance },
                )
                else -> error("Unexpected acceptance phase for ${fixture.id}")
            }
        }
    }

    @Test
    fun `unknown ordinary package is guided without an allowlist`() {
        val fixture = fixture("ui_trees/unknown_app/ordinary.json")
        val planner = FixturePlanner(fixture.plan)
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { fixture.snapshot }, emit = emissions::add)

        loop.startGoal(fixture.goal)
        loop.confirmGoal()

        assertEquals(1, planner.calls)
        assertEquals(ScenarioPhase.GUIDING_STEP, loop.session.phase)
        assertEquals(1, emissions.count { it.resolvedTarget != null })
    }

    @Test
    fun `production guidance contains no acceptance package or screen sequence branches`() {
        val cases = fixtures()
        val mainRoot = File("src/main")
        val scanRoots = listOf(
            File(mainRoot, "java/com/screensaathi/session"),
            File(mainRoot, "java/com/screensaathi/screen"),
            File(mainRoot, "java/com/screensaathi/sarvam"),
            File(mainRoot, "assets/prompts"),
        )
        scanRoots.forEach { root ->
            assertTrue("Production route not found: ${root.absolutePath}", root.isDirectory)
        }
        val sources = scanRoots.asSequence()
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension in setOf("kt", "md") }
            .associateWith(File::readText)

        cases.flatMap { fixture -> fixture.antiHardcodeTokens.map { fixture to it } }
            .forEach { (fixture, token) ->
            val hits = sources.filterValues { it.contains(token, ignoreCase = true) }.keys
            if (token in fixture.allowedSafetyPolicyTokens) {
                assertTrue("Named safety token '$token' has no policy coverage", hits.isNotEmpty())
                assertEquals(
                    "Named safety token '$token' belongs only to ScreenSafetyGate",
                    setOf(File(mainRoot, "java/com/screensaathi/session/ScreenSafetyGate.kt")),
                    hits,
                )
            } else {
                assertTrue("Production hardcodes acceptance token '$token' in $hits", hits.isEmpty())
            }
        }
    }

    private class FixturePlanner(private val plan: PlannerResult) : GuidancePlanner {
        var calls = 0
        var lastScreen: PlannerScreen? = null
        val receivedActionTypes = mutableListOf<String>()

        override fun planNext(
            screen: PlannerScreen,
            goal: String,
            stepNumber: Int,
            lastSafeInstruction: String?,
            repairFormat: Boolean,
        ): PlannerOutcome {
            calls++
            lastScreen = screen
            receivedActionTypes += plan.actionType
            return PlannerOutcome.Success(plan)
        }
    }

    private data class AcceptanceFixture(
        val id: String,
        val captureKind: String,
        val goal: String,
        val snapshot: ScreenSnapshot,
        val appLabel: String?,
        val expectedPhase: ScenarioPhase,
        val expectedPlannerCalls: Int,
        val plan: PlannerResult,
        val forbiddenProviderValues: List<String>,
        val antiHardcodeTokens: List<String>,
        val allowedSafetyPolicyTokens: Set<String>,
    )

    private fun fixtures(): List<AcceptanceFixture> = FIXTURE_PATHS.map(::fixture)

    private fun fixture(path: String): AcceptanceFixture {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing acceptance fixture: $path"
        }
        val root = stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val elements = root.getJSONArray("elements")
        val snapshot = ScreenSnapshot(
            packageName = root.getString("package_name"),
            settled = root.getBoolean("settled"),
            viewport = root.optJSONObject("viewport")?.let {
                ScreenViewport(it.getInt("left"), it.getInt("top"), it.getInt("right"), it.getInt("bottom"))
            } ?: ScreenViewport.LEGACY_UNBOUNDED,
            elements = List(elements.length()) { index ->
                val node = elements.getJSONObject(index)
                val bounds = node.getJSONArray("bounds")
                ScreenElement(
                    index = node.optInt("index", index),
                    resourceId = node.optString("resource_id"),
                    text = node.optString("text"),
                    className = node.optString("class_name", "android.view.View"),
                    bounds = Rect().apply {
                        left = bounds.getInt(0)
                        top = bounds.getInt(1)
                        right = bounds.getInt(2)
                        bottom = bounds.getInt(3)
                    },
                    editable = node.optBoolean("editable"),
                    clickable = node.optBoolean("clickable"),
                    contentDescription = node.optString("content_description"),
                    hintText = node.optString("hint_text"),
                    enabled = node.optBoolean("enabled", true),
                    selected = node.optBoolean("selected"),
                    checked = node.optBoolean("checked"),
                    scrollable = node.optBoolean("scrollable"),
                    password = node.optBoolean("password"),
                    inputType = node.optInt("input_type"),
                    visibleToUser = node.optBoolean("visible_to_user", true),
                )
            },
        )
        val plan = root.getJSONObject("planner_result")
        return AcceptanceFixture(
            id = root.getString("id"),
            captureKind = root.getString("capture_kind"),
            goal = root.getString("goal"),
            snapshot = snapshot,
            appLabel = root.optString("app_label").takeIf(String::isNotBlank),
            expectedPhase = ScenarioPhase.valueOf(root.getString("expected_phase")),
            expectedPlannerCalls = root.getInt("expected_planner_calls"),
            plan = PlannerResult(
                version = 1,
                intent = "goal",
                step = "fixture",
                targetResourceId = plan.optString("target_resource_id"),
                targetIndex = plan.optInt("target_index", -1),
                instruction = plan.getString("instruction"),
                confidence = 0.99,
                reason = "synthetic acceptance fixture",
                language = "ru-RU",
                isDone = plan.optBoolean("is_done"),
                targetText = plan.optString("target_text"),
                actionType = plan.getString("action_type"),
            ),
            forbiddenProviderValues = root.optJSONArray("forbidden_provider_values")?.let { values ->
                List(values.length()) { values.getString(it) }
            }.orEmpty(),
            antiHardcodeTokens = root.getJSONArray("anti_hardcode_tokens").let { values ->
                List(values.length()) { values.getString(it) }
            },
            allowedSafetyPolicyTokens = root.optJSONArray("allowed_safety_policy_tokens")
                ?.let { values -> List(values.length()) { values.getString(it) }.toSet() }
                .orEmpty(),
        )
    }

    private companion object {
        val FIXTURE_PATHS = listOf(
            "ui_trees/yandex_go/destination.json",
            "ui_trees/yandex_go/final_handoff.json",
            "ui_trees/samsung_settings/font_size.json",
            "ui_trees/cdek/pickup_point.json",
            "ui_trees/gosuslugi/auth.json",
            "ui_trees/bank/home.json",
            "ui_trees/unknown_app/ordinary.json",
            "ui_trees/unknown_app/unusable.json",
        )
    }
}
