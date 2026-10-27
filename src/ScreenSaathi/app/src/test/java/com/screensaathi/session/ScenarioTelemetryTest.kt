package com.screensaathi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTelemetryTest {

    @Test
    fun `human confirmation dwell stays in end to end timing with separate start and replan spans`() {
        var now = 0L
        val events = mutableListOf<ScenarioTelemetryEvent>()
        val trace = ScenarioLatencyTrace({ now }, ScenarioTelemetrySink(events::add))
        now = 30
        trace.confirmationRendered()
        now = 1000
        trace.guidanceConfirmed()
        now = 1020
        trace.firstUsefulRender()
        now = 1100
        trace.analysisStarted()
        now = 1120
        trace.analysisStarted() // Duplicate observations must not restart the timer.
        now = 1150
        trace.firstUsefulRender()
        trace.firstUsefulRender()
        assertEquals(listOf(ScenarioLatencyStage.GOAL_CONFIRMATION_RENDER,
            ScenarioLatencyStage.FIRST_USEFUL_RENDER, ScenarioLatencyStage.START_TO_HIGHLIGHT,
            ScenarioLatencyStage.REPLAN_TO_USEFUL_RENDER), events.map { it.stage })
        assertEquals(listOf(30L, 1020L, 20L, 50L), events.map { it.durationMs })
    }

    @Test
    fun `trace records bounded outcomes and nonnegative durations once per stage or planner attempt`() {
        var now = 100L
        val events = mutableListOf<ScenarioTelemetryEvent>()
        val trace = ScenarioLatencyTrace({ now }, ScenarioTelemetrySink(events::add))
        trace.measure(ScenarioLatencyStage.SNAPSHOT) { now += 5 }
        trace.measure(ScenarioLatencyStage.SNAPSHOT) { now += 3 }
        trace.measure(ScenarioLatencyStage.SAFETY) { now -= 2 }
        trace.measure(ScenarioLatencyStage.PLANNER) { now += 20 }
        trace.measure(ScenarioLatencyStage.PLANNER) { now += 30 }
        trace.ttsStarted() // A confirmation spoken before useful guidance is not this milestone.
        trace.firstUsefulRender()
        trace.firstUsefulRender()
        now += 4
        trace.ttsStarted()
        trace.ttsStarted()
        assertEquals(listOf(ScenarioLatencyStage.SNAPSHOT, ScenarioLatencyStage.SAFETY,
            ScenarioLatencyStage.PLANNER, ScenarioLatencyStage.PLANNER,
            ScenarioLatencyStage.FIRST_USEFUL_RENDER, ScenarioLatencyStage.TTS_START), events.map { it.stage })
        assertEquals(listOf(5L, 0L, 20L, 30L, 56L, 60L), events.map { it.durationMs })
        assertEquals(listOf(1, 2), events.filter { it.stage == ScenarioLatencyStage.PLANNER }.map { it.attempt })
        assertTrue(ScenarioTelemetryEvent::class.java.getDeclaredField("outcome").type.isEnum)
    }

    @Test
    fun `failed measurement is technical only and does not swallow operation failure`() {
        val events = mutableListOf<ScenarioTelemetryEvent>()
        val trace = ScenarioLatencyTrace({ 10L }, ScenarioTelemetrySink(events::add))
        val result = runCatching { trace.measure(ScenarioLatencyStage.STT) { error("private input") } }
        assertTrue(result.isFailure)
        assertEquals(ScenarioLatencyOutcome.FAILURE, events.single().outcome)
        assertTrue(!events.single().toString().contains("private input"))
    }

    @Test
    fun `telemetry event can carry only synthetic technical metadata`() {
        val event = ScenarioTelemetryEvent(
            category = ScenarioTelemetryCategory.SCENARIO_COMPLETED,
            durationMs = 1_234,
            packageName = "ru.example.app",
            appVersion = "1.0-debug",
            deviceModel = "synthetic-device",
            androidVersion = "12",
            networkType = "wifi",
            installId = "install-test-42",
            errorCategory = null,
        )

        assertEquals(ScenarioTelemetryCategory.SCENARIO_COMPLETED, event.category)
        assertEquals(1_234L, event.durationMs)

        val forbiddenNames = setOf(
            "goal", "transcript", "label", "tree", "audio", "request", "response", "body",
        )
        val fieldNames = ScenarioTelemetryEvent::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { field -> forbiddenNames.any(field::contains) })
    }

    @Test
    fun `fake sink records metadata while default sink is a no-op`() {
        val event = ScenarioTelemetryEvent(
            category = ScenarioTelemetryCategory.ERROR,
            errorCategory = "network",
        )
        val recorded = mutableListOf<ScenarioTelemetryEvent>()
        val fake = ScenarioTelemetrySink(recorded::add)

        fake.record(event)
        NoOpScenarioTelemetrySink.record(event)

        assertEquals(listOf(event), recorded)
    }
}
