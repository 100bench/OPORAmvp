package com.screensaathi.session

enum class ScenarioLatencyStage {
    STT, SNAPSHOT, SAFETY, PLANNER, TARGET_RESOLUTION, FIRST_USEFUL_RENDER, TTS_START,
    GOAL_CONFIRMATION_RENDER, START_TO_HIGHLIGHT, REPLAN_TO_USEFUL_RENDER,
}

enum class ScenarioLatencyOutcome { SUCCESS, FAILURE }

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
    val stage: ScenarioLatencyStage? = null,
    val attempt: Int? = null,
    val outcome: ScenarioLatencyOutcome? = null,
)

fun interface ScenarioTelemetrySink {
    fun record(event: ScenarioTelemetryEvent)
}

object NoOpScenarioTelemetrySink : ScenarioTelemetrySink {
    override fun record(event: ScenarioTelemetryEvent) = Unit
}

class ScenarioLatencyTrace(
    private val clock: () -> Long,
    private val sink: ScenarioTelemetrySink,
) {
    private val startedAt = clock()
    private val recorded = mutableSetOf<ScenarioLatencyStage>()
    private var plannerAttempts = 0
    private var confirmedAt: Long? = null
    private var replanAt: Long? = null

    fun <T> measure(
        stage: ScenarioLatencyStage,
        outcome: (T) -> ScenarioLatencyOutcome = { ScenarioLatencyOutcome.SUCCESS },
        operation: () -> T,
    ): T {
        val attempt = synchronized(this) {
            if (stage == ScenarioLatencyStage.PLANNER) ++plannerAttempts else null
        }
        val start = clock()
        var result = ScenarioLatencyOutcome.FAILURE
        try {
            return operation().also { result = outcome(it) }
        } finally {
            record(stage, start, result, attempt)
        }
    }

    @Synchronized
    fun confirmationRendered() = record(ScenarioLatencyStage.GOAL_CONFIRMATION_RENDER, startedAt)

    @Synchronized
    fun guidanceConfirmed() { if (confirmedAt == null) confirmedAt = clock() }

    @Synchronized
    fun analysisStarted() {
        if (ScenarioLatencyStage.FIRST_USEFUL_RENDER in recorded && replanAt == null) replanAt = clock()
    }

    @Synchronized
    fun firstUsefulRender() {
        record(ScenarioLatencyStage.FIRST_USEFUL_RENDER, startedAt)
        confirmedAt?.let { record(ScenarioLatencyStage.START_TO_HIGHLIGHT, it) }
        replanAt?.let { record(ScenarioLatencyStage.REPLAN_TO_USEFUL_RENDER, it, attempt = plannerAttempts) }
        replanAt = null
    }

    @Synchronized
    fun ttsStarted() {
        if (ScenarioLatencyStage.FIRST_USEFUL_RENDER in recorded) record(ScenarioLatencyStage.TTS_START, startedAt)
    }

    @Synchronized
    private fun record(
        stage: ScenarioLatencyStage,
        start: Long,
        outcome: ScenarioLatencyOutcome = ScenarioLatencyOutcome.SUCCESS,
        attempt: Int? = null,
    ) {
        if (!recorded.add(stage) && stage != ScenarioLatencyStage.PLANNER &&
            stage != ScenarioLatencyStage.REPLAN_TO_USEFUL_RENDER) return
        val event = ScenarioTelemetryEvent(ScenarioTelemetryCategory.LATENCY,
            durationMs = (clock() - start).coerceAtLeast(0), stage = stage,
            attempt = attempt, outcome = outcome)
        // Diagnostics cannot prevent useful guidance, even with a broken sink.
        runCatching { sink.record(event) }
    }
}
