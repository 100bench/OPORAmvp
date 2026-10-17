package com.screensaathi.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTelemetryTest {

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
