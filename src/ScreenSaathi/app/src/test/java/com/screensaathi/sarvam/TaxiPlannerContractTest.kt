package com.screensaathi.sarvam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiPlannerContractTest {

    @Test
    fun `final order button is accepted as visible guidance`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            response(
                """{"is_done":false,"action_type":"guide",
                    "target":{"resource_id":"","text":"Заказать","index":17},
                    "instruction":"Нажмите кнопку «Заказать».","language":"ru-RU",
                    "confidence":0.97,"reason":"final visible taxi control"}""",
            ),
            goal = "Заказать такси",
            groundedLabels = listOf("Заказать"),
        )

        assertTrue(outcome is PlannerOutcome.Success)
        val plan = (outcome as PlannerOutcome.Success).plan
        assertEquals("Заказать", plan.targetText)
        assertEquals(17, plan.targetIndex)
        assertEquals("guide", plan.actionType)
    }

    @Test
    fun `model may select an actionable element only by snapshot index`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            response(
                """{"is_done":false,"action_type":"guide",
                    "target":{"resource_id":"","text":"","index":4},
                    "instruction":"Нажмите выделенное поле.","language":"ru-RU",
                    "confidence":0.91,"reason":"actionable field"}""",
            ),
            goal = "Заказать такси",
        )

        assertTrue(outcome is PlannerOutcome.Success)
        val plan = (outcome as PlannerOutcome.Success).plan
        assertEquals(4, plan.targetIndex)
        assertEquals("", plan.targetResourceId)
        assertEquals("", plan.targetText)
    }

    private fun response(arguments: String): String =
        """{"choices":[{"message":{"tool_calls":[{"function":{"name":"set_next_step","arguments":${
            org.json.JSONObject.quote(arguments)
        }}}]}}]}"""
}
