package com.screensaathi.session

enum class ScenarioTelemetryCategory {
    SCENARIO_STARTED,
    SCENARIO_COMPLETED,
    SAFE_HANDOFF,
    ERROR,
    LATENCY,
}

/** Technical metadata only. User content has no representable field here. */
data class ScenarioTelemetryEvent(
    val category: ScenarioTelemetryCategory,
    val durationMs: Long? = null,
    val packageName: String? = null,
    val appVersion: String? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val networkType: String? = null,
    val installId: String? = null,
    val errorCategory: String? = null,
)

fun interface ScenarioTelemetrySink {
    fun record(event: ScenarioTelemetryEvent)
}

object NoOpScenarioTelemetrySink : ScenarioTelemetrySink {
    override fun record(event: ScenarioTelemetryEvent) = Unit
}
