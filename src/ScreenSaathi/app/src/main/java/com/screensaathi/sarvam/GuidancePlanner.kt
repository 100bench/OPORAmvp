package com.screensaathi.sarvam

import com.screensaathi.screen.PlannerScreen

/** Provider-neutral planner boundary used by the universal guidance loop. */
interface GuidancePlanner {
    fun planNext(
        screen: PlannerScreen,
        goal: String,
        stepNumber: Int,
        lastSafeInstruction: String?,
        repairFormat: Boolean = false,
    ): PlannerOutcome
}

/** Small injectable HTTP seam; production wraps the existing OkHttp client. */
internal fun interface GuidanceTransport {
    @Throws(java.io.IOException::class)
    fun execute(requestBody: String): GuidanceTransportResponse
}

internal data class GuidanceTransportResponse(
    val statusCode: Int,
    val body: String,
)
