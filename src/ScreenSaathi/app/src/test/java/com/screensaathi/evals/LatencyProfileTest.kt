package com.screensaathi.evals

import com.screensaathi.sarvam.*
import com.screensaathi.screen.*
import com.screensaathi.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.ceil

/** Paid opt-in: real production prompt/HTTP adapter, safety, sanitizer and local resolver. */
class LatencyProfileTest {
    private data class Case(val id: String, val file: String, val goal: String,
        val expectedIndex: Int? = null, val answerTerm: String? = null)
    private data class Sample(val id: String, val kind: String, val millis: Long,
        val calls: Int, val repairs: Int, val usable: Boolean, val safetyErrors: Int,
        val approximateInputTokens: Int, val approximateOutputTokens: Int, val phase: String, val quality: String)

    @Test fun `profile the live planner call`() {
        assumeTrue("LIVE profiling not enabled", System.getenv("SCREENSAATHI_EVAL_LIVE") == "1")
        val prompt = checkNotNull(EvalPaths.resolve("app/src/main/assets/prompts/planner_v1.md")).readText()
        val planner = SarvamPlanner.forLiveEval(prompt)
        val repeats = System.getenv("REPEATS")?.toIntOrNull()?.coerceIn(2, 20) ?: 2
        val samples = buildList {
            repeat(repeats) { cases().forEach { add(runCase(it, planner, prompt.length)) } }
        }
        val safetyErrors = samples.sumOf { it.safetyErrors }
        val repairs = samples.count { it.repairs > 0 }
        val usable = samples.count { it.usable }
        val latency = samples.map { it.millis }.sorted()
        println("=== OPORA LIVE ACCEPTANCE ===")
        println("date=${Instant.now()} model=${Sarvam.PLANNER_MODEL} prompt=planner_v1.md " +
            "sha256=${MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray()).joinToString("") { "%02x".format(it) }}")
        samples.forEach { println("case=${it.id} source=${it.kind} ms=${it.millis} calls=${it.calls} " +
            "repair=${it.repairs} usable=${it.usable} safetyErrors=${it.safetyErrors} phase=${it.phase} quality=${it.quality}") }
        println("samples=${samples.size} actualCalls=${samples.sumOf { it.calls }} usable=$usable " +
            "repairSamples=$repairs safetyErrors=$safetyErrors p50=${percentile(latency, 50)} p95=${percentile(latency, 95)}")
        println("approxInputTokens=${samples.sumOf { it.approximateInputTokens }} " +
            "approxOutputTokens=${samples.sumOf { it.approximateOutputTokens }} " +
            "estimate=characters/4_excluding_tool_schema_and_output_envelope_not_billed_usage")
        assertTrue("At least 20 samples must actually reach the planner", samples.count { it.calls > 0 } >= 20)
        assertTrue("Live acceptance gate failed (see per-case metadata)",
            AcceptanceGate.accepts(samples.size, usable, repairs, safetyErrors))
    }

    @Test fun `live corpus has safe readable cases with independently specified targets`() {
        assertTrue(cases().size * 2 >= 20)
        cases().forEach { case ->
            val snapshot = fixture(case.file)
            assertEquals(case.id, ScreenDisposition.ALLOW, ScreenSafetyGate.assess(snapshot).disposition)
            assertTrue(case.id, ScreenSanitizer.sanitize(snapshot).elements.isNotEmpty())
            case.expectedIndex?.let { index ->
                assertEquals(case.id, ScreenElementEligibility.Eligible,
                    snapshot.elements.single { it.index == index }.eligibility(snapshot.viewportOrLegacy))
            }
        }
    }

    /** Runs offline too: protected and modal fixtures must never enter the paid denominator. */
    @Test fun `local safety and modal outcomes never call planner`() {
        val rows = listOf(
            "common/auth_synthetic.json" to ScenarioPhase.PAUSED_FOR_AUTH,
            "yandex_go/final_handoff_synthetic.json" to ScenarioPhase.SAFE_HANDOFF,
            "auto_ru/final_publish_synthetic.json" to ScenarioPhase.SAFE_HANDOFF,
            "common/popup_close_synthetic.json" to ScenarioPhase.WAITING_FOR_MODAL_DISMISS,
            "common/popup_manual_synthetic.json" to ScenarioPhase.WAITING_FOR_MODAL_DISMISS)
        rows.forEach { (file, phase) ->
            val snapshot = fixture(file)
            var calls = 0
            val planner = object : GuidancePlanner {
                override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                    lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                    calls++
                    return PlannerOutcome.Failure(PlannerFailureKind.NETWORK, "Unexpected call")
                }
            }
            val emissions = mutableListOf<UniversalGuidanceEmission>()
            val loop = UniversalGuidanceLoop(planner, { snapshot }, emit = emissions::add)
            loop.startGoal("Помоги продолжить")
            assertEquals(file, phase, loop.session.phase)
            assertEquals(file, 0, calls)
            assertEquals(file, if (file.contains("popup_close")) 1 else 0,
                emissions.count { it.resolvedTarget != null })
        }
    }

    private fun runCase(case: Case, delegate: GuidancePlanner, promptChars: Int): Sample {
        val snapshot = fixture(case.file)
        val kind = JSONObject(resource(case.file)).getString("captureKind")
        var calls = 0
        var repairs = 0
        var safetyErrors = 0
        var inputChars = 0
        var outputChars = 0
        var callNanos = 0L
        val planner = object : GuidancePlanner {
            override fun planNext(screen: PlannerScreen, goal: String, stepNumber: Int,
                lastSafeInstruction: String?, repairFormat: Boolean): PlannerOutcome {
                calls++
                if (repairFormat) repairs++
                if (ScreenSafetyGate.assess(snapshot).disposition != ScreenDisposition.ALLOW) safetyErrors++
                inputChars += promptChars + screen.toPromptText().length + goal.length
                val started = System.nanoTime()
                val result = delegate.planNext(screen, goal, stepNumber, lastSafeInstruction, repairFormat)
                callNanos += System.nanoTime() - started
                if (result is PlannerOutcome.Success) outputChars += result.plan.instruction.length
                return result
            }
        }
        val emissions = mutableListOf<UniversalGuidanceEmission>()
        val loop = UniversalGuidanceLoop(planner, { snapshot }, emit = emissions::add)
        loop.startGoal(case.goal)
        if (loop.session.phase == ScenarioPhase.CONFIRMING_GOAL) loop.confirmGoal()
        val targets = emissions.mapNotNull { it.resolvedTarget }
        val viewport = snapshot.viewportOrLegacy
        safetyErrors += targets.count { it.left < viewport.left || it.top < viewport.top ||
            it.right > viewport.right || it.bottom > viewport.bottom }
        var quality = "target"
        val usable = if (case.expectedIndex != null) {
            val expected = snapshot.elements.single { it.index == case.expectedIndex }
            loop.session.phase == ScenarioPhase.GUIDING_STEP && targets.size == 1 && targets.single().let {
                it.left == expected.bounds.left && it.top == expected.bounds.top &&
                    it.right == expected.bounds.right && it.bottom == expected.bounds.bottom
            }
        } else {
            val answers = emissions.mapNotNull { (it.effect as? ScenarioEffect.ShowAnswer)?.instruction }
            val a = answers.singleOrNull().orEmpty()
            quality = "answers=${answers.size},short=${a.length <= 350},term=${a.contains(case.answerTerm!!, true)},helpful=${Regex("помог|помочь|подскаж|скажите, что", RegexOption.IGNORE_CASE).containsMatchIn(a)}"
            loop.session.phase == ScenarioPhase.COMPLETED && targets.isEmpty() && answers.size == 1 &&
                answers.single().let { it.length <= 350 && it.contains(case.answerTerm!!, true) &&
                    !it.contains("скрин", true) && Regex("помог|помочь|подскаж|скажите, что", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        }
        return Sample(case.id, kind, callNanos / 1_000_000, calls, repairs,
            usable && calls in 1..2 && repairs <= 1, safetyErrors,
            ceil(inputChars / 4.0).toInt(), ceil(outputChars / 4.0).toInt(), loop.session.phase.name, quality)
    }

    private fun cases() = listOf(
        Case("yandex_destination", "yandex_go/destination_empty_keyboard.json", "Помоги указать, куда поехать", 45),
        Case("yandex_destination_question", "yandex_go/destination_empty_keyboard.json", "Что находится на экране?", answerTerm = "Куда"),
        Case("auto_sell", "auto_ru/sell_entry_synthetic.json", "Помоги продать автомобиль", 0),
        Case("auto_plate", "auto_ru/plate_synthetic.json", "Помоги указать госномер автомобиля", 0),
        Case("auto_vin", "auto_ru/vin_synthetic.json", "Помоги указать VIN автомобиля", 0),
        Case("auto_form", "auto_ru/later_form_synthetic.json", "Помоги указать пробег автомобиля", 0),
        Case("auto_plate_question", "auto_ru/plate_synthetic.json", "Что находится на экране?", answerTerm = "Госномер"),
        Case("auto_vin_question", "auto_ru/vin_synthetic.json", "Какие поля есть на экране?", answerTerm = "VIN"),
        Case("viewport_duplicate", "common/off_viewport_duplicate_synthetic.json", "Помоги продолжить", 0),
        Case("yandex_map", "yandex_go/map_synthetic.json", "Помоги выбрать адрес на карте", 0))

    private fun resource(file: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream("ui_trees/real/$file"))
        .bufferedReader().use { it.readText() }
    private fun fixture(file: String) = UiTreeFixtureCodec.decode(resource(file))
    private fun percentile(sorted: List<Long>, p: Int) = sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceAtLeast(0)]
}
