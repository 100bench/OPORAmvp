package com.screensaathi.evals

import com.screensaathi.sarvam.PlannerOutcome
import com.screensaathi.sarvam.SarvamPlanner
import com.screensaathi.screen.PlannerElement
import com.screensaathi.screen.PlannerScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.ceil

/** Opt-in latency profile for the exact production one-step guidance adapter. */
class LatencyProfileTest {
    private data class ProfileCase(
        val id: String,
        val goal: String,
        val screen: PlannerScreen,
        val required: Boolean = true,
        val expectedActionType: String? = null,
        val requireHelpfulFollowUp: Boolean = false,
        val visibleOptionsToMention: List<String> = emptyList(),
    )

    private data class Sample(
        val caseId: String,
        val totalMs: Long,
        val succeeded: Boolean,
        val required: Boolean,
    )

    @Test
    fun `profile the live planner call`() {
        assumeTrue(
            "LIVE profiling not enabled",
            System.getenv("SCREENSAATHI_EVAL_LIVE") == "1",
        )
        val prompt = EvalPaths.resolve("app/src/main/assets/prompts/planner_v1.md")
        assumeTrue("production prompt missing", prompt != null)
        val planner = SarvamPlanner.forLiveEval(prompt!!.readText())
        val repeats = System.getenv("REPEATS")?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val cases = profileCases()
        val samples = buildList {
            repeat(repeats) {
                cases.forEach { case ->
                    val started = System.nanoTime()
                    val outcome = planner.planNext(case.screen, case.goal, 1, null)
                    add(
                        Sample(
                            caseId = case.id,
                            totalMs = (System.nanoTime() - started) / 1_000_000,
                            succeeded = outcome is PlannerOutcome.Success &&
                                (case.expectedActionType == null ||
                                    (outcome.plan.actionType == case.expectedActionType &&
                                        !outcome.plan.instruction.contains("скрин", ignoreCase = true))) &&
                                (!case.requireHelpfulFollowUp ||
                                    HELPFUL_FOLLOW_UP.containsMatchIn(outcome.plan.instruction)) &&
                                (case.visibleOptionsToMention.isEmpty() ||
                                    case.visibleOptionsToMention.any {
                                        outcome.plan.instruction.contains(it, ignoreCase = true)
                                    }),
                            required = case.required,
                        )
                    )
                }
            }
        }

        assertEquals(cases.size * repeats, samples.size)
        val required = samples.filter(Sample::required)
        report(samples, repeats)
        assertTrue(
            "required live cases failed: ${required.filterNot(Sample::succeeded).map(Sample::caseId)}",
            required.isNotEmpty() && required.all(Sample::succeeded),
        )
        assertTrue(
            "required production planner p95 must stay within 10 seconds",
            percentile(required.map(Sample::totalMs).sorted(), 95.0) <= 10_000L,
        )
    }

    private fun profileCases() = listOf(
        ProfileCase(
            id = "question_screen",
            goal = "Что находится на экране?",
            screen = screen(
                "example.app",
                element(0, "settings", "Настройки"),
                element(1, "profile", "Профиль"),
            ),
            expectedActionType = "answer",
            requireHelpfulFollowUp = true,
            visibleOptionsToMention = listOf("Настройки", "Профиль"),
        ),
        ProfileCase(
            id = "question",
            goal = "Где история поездок?",
            screen = screen(
                "ru.yandex.taxi",
                element(0, "profile", "Профиль"),
                element(1, "ride_history", "История поездок"),
            ),
        ),
        ProfileCase(
            id = "guide",
            goal = "Хочу заказать такси",
            screen = screen(
                "ru.yandex.taxi",
                element(0, "destination", "Куда?", editable = true),
                element(1, "saved_places", "Сохранённые места"),
            ),
        ),
        ProfileCase(
            id = "honest_failure",
            goal = "Найди кнопку продолжения",
            screen = PlannerScreen("example.unknown", settled = true, elements = emptyList()),
            required = false,
        ),
    )

    private fun screen(packageName: String, vararg elements: PlannerElement) =
        PlannerScreen(packageName, settled = true, elements = elements.toList())

    private fun element(
        index: Int,
        resourceId: String,
        label: String,
        editable: Boolean = false,
    ) = PlannerElement(
        index = index,
        resourceId = resourceId,
        label = label,
        description = null,
        role = if (editable) "EditText" else "Button",
        editable = editable,
        clickable = !editable,
        enabled = true,
        selected = false,
        checked = false,
        scrollable = false,
    )

    private fun report(samples: List<Sample>, repeats: Int) {
        val sorted = samples.map(Sample::totalMs).sorted()
        println()
        println("=== UNIVERSAL GUIDANCE LATENCY PROFILE ===")
        println("repeats=$repeats samples=${samples.size} successes=${samples.count(Sample::succeeded)}")
        println(
            "total p50=${percentile(sorted, 50.0)} ms " +
                "p95=${percentile(sorted, 95.0)} ms min=${sorted.first()} ms max=${sorted.last()} ms"
        )
        samples.groupBy(Sample::caseId).forEach { (caseId, caseSamples) ->
            val values = caseSamples.map(Sample::totalMs).sorted()
            println(
                "$caseId p50=${percentile(values, 50.0)} ms " +
                    "success=${caseSamples.count(Sample::succeeded)}/${caseSamples.size}"
            )
        }
    }

    private fun percentile(sorted: List<Long>, p: Double): Long =
        sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]

    companion object {
        private val HELPFUL_FOLLOW_UP = Regex("помог|подскаж|скажите, что", RegexOption.IGNORE_CASE)
    }
}
