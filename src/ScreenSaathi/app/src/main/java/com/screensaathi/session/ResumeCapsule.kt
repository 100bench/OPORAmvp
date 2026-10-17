package com.screensaathi.session

import org.json.JSONObject

/**
 * The complete process-restoration payload. It intentionally cannot represent
 * a screen tree, fingerprint, target, bounds, highlight, audio or user secret.
 */
data class ResumeCapsule(
    val goal: String,
    val targetPackage: String?,
    val mode: ScenarioMode,
    val stepNumber: Int,
    val lastSafeInstruction: String?,
    val savedWallTimeMs: Long,
    val savedElapsedRealtimeMs: Long,
) {
    init {
        require(goal.isNotBlank()) { "Resume goal cannot be blank" }
        require(mode == ScenarioMode.GUIDANCE) { "Only guidance sessions are resumable" }
        require(stepNumber >= 1) { "Resume step must be positive" }
        require(savedWallTimeMs >= 0) { "Wall timestamp cannot be negative" }
        require(savedElapsedRealtimeMs >= 0) { "Elapsed timestamp cannot be negative" }
    }

    companion object {
        fun fromSession(
            session: ScenarioSession,
            savedWallTimeMs: Long,
            savedElapsedRealtimeMs: Long,
        ): ResumeCapsule {
            require(
                session.phase in setOf(
                    ScenarioPhase.GUIDING_STEP,
                    ScenarioPhase.WAITING_FOR_SCREEN_CHANGE,
                )
            ) { "Only safe visible guidance transitions are resumable" }
            return ResumeCapsule(
                goal = session.goal.trim(),
                targetPackage = session.targetPackage?.trim()?.takeIf(String::isNotEmpty),
                mode = session.mode,
                stepNumber = session.stepNumber,
                lastSafeInstruction = session.lastSafeInstruction?.trim()?.takeIf(String::isNotEmpty),
                savedWallTimeMs = savedWallTimeMs,
                savedElapsedRealtimeMs = savedElapsedRealtimeMs,
            )
        }
    }
}

/** Strict JSON codec: exact keys are part of the privacy boundary. */
object ResumeCapsuleCodec {
    private const val GOAL = "goal"
    private const val TARGET_PACKAGE = "target_package"
    private const val MODE = "mode"
    private const val STEP_NUMBER = "step_number"
    private const val LAST_SAFE_INSTRUCTION = "last_safe_instruction"
    private const val SAVED_WALL_TIME_MS = "saved_wall_time_ms"
    private const val SAVED_ELAPSED_REALTIME_MS = "saved_elapsed_realtime_ms"

    private val allowedKeys = setOf(
        GOAL,
        TARGET_PACKAGE,
        MODE,
        STEP_NUMBER,
        LAST_SAFE_INSTRUCTION,
        SAVED_WALL_TIME_MS,
        SAVED_ELAPSED_REALTIME_MS,
    )

    fun encode(capsule: ResumeCapsule): String = JSONObject()
        .put(GOAL, capsule.goal)
        .put(TARGET_PACKAGE, capsule.targetPackage ?: JSONObject.NULL)
        .put(MODE, capsule.mode.name)
        .put(STEP_NUMBER, capsule.stepNumber)
        .put(LAST_SAFE_INSTRUCTION, capsule.lastSafeInstruction ?: JSONObject.NULL)
        .put(SAVED_WALL_TIME_MS, capsule.savedWallTimeMs)
        .put(SAVED_ELAPSED_REALTIME_MS, capsule.savedElapsedRealtimeMs)
        .toString()

    fun decode(encoded: String): ResumeCapsule? = runCatching {
        val json = JSONObject(encoded)
        if (json.keys().asSequence().toSet() != allowedKeys) return null
        ResumeCapsule(
            goal = json.strictString(GOAL).trim(),
            targetPackage = json.optionalString(TARGET_PACKAGE),
            mode = ScenarioMode.valueOf(json.strictString(MODE)),
            stepNumber = json.strictInt(STEP_NUMBER),
            lastSafeInstruction = json.optionalString(LAST_SAFE_INSTRUCTION),
            savedWallTimeMs = json.strictLong(SAVED_WALL_TIME_MS),
            savedElapsedRealtimeMs = json.strictLong(SAVED_ELAPSED_REALTIME_MS),
        )
    }.getOrNull()

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key)) null else strictString(key).trim().takeIf(String::isNotEmpty)

    private fun JSONObject.strictString(key: String): String =
        (get(key) as? String) ?: error("$key must be a string")

    private fun JSONObject.strictInt(key: String): Int = when (val value = get(key)) {
        is Int -> value
        is Long -> value.toInt().takeIf { it.toLong() == value }
            ?: error("$key is outside Int range")
        else -> error("$key must be an integer")
    }

    private fun JSONObject.strictLong(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("$key must be an integer")
    }
}
