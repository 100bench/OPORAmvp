package com.screensaathi.sarvam

import com.screensaathi.task.GuidedTask
import com.screensaathi.task.TaskStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is a language model behind a forced tool call, so the parser is
 * the boundary where its output stops being a suggestion and starts driving the
 * UI. These pin what happens when it answers imperfectly.
 */
class SarvamPlannerParseTest {

    private val task = GuidedTask(
        version = 1, id = "pay_bill", title = "Pay Electricity Bill",
        utterances = emptyList(),
        steps = listOf(
            TaskStep(
                id = "amount", resourceId = "amount_field",
                instruction = "Enter the bill amount in this box.",
                instructions = mapOf("hi-IN" to "इस बॉक्स में बिल की रकम भरिए।"),
            ),
            TaskStep(id = "submit", resourceId = "submit_button", instruction = "Tap to pay."),
        ),
    )

    private fun response(args: String): String =
        """{"choices":[{"message":{"tool_calls":[{"function":{"name":"set_plan","arguments":${
            org.json.JSONObject.quote(args)
        }}}]}}]}"""

    @Test
    fun `a well-formed Hindi plan is parsed whole`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":4},
                   "instruction":"इस बॉक्स में बिल की रकम भरिए।",
                   "language":"hi-IN","confidence":0.97,"reason":"matches amount request"}"""
            ),
            task, "hi-IN",
        )
        assertNotNull(plan)
        assertEquals("pay_bill", plan!!.intent)
        assertEquals("amount", plan.step)
        assertEquals("amount_field", plan.targetResourceId)
        assertEquals(4, plan.targetIndex)
        assertEquals("hi-IN", plan.language)
        assertEquals(0.97, plan.confidence, 0.001)
        assertEquals("hi-IN", plan.spoken.language)
    }

    @Test
    fun `a mislabelled language is corrected to the script actually written`() {
        // The model answered in English but claimed the user's Hindi. Trusting
        // the claim would 400 at Bulbul and the app would go mute.
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":1},
                   "instruction":"Enter the bill amount in this box.",
                   "language":"hi-IN","confidence":0.9,"reason":"ok"}"""
            ),
            task, "hi-IN",
        )
        assertEquals("en-IN", plan!!.language)
    }

    @Test
    fun `a missing language falls back to the language the user spoke`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":1},
                   "instruction":"इस बॉक्स में रकम भरिए।","confidence":0.9,"reason":"ok"}"""
            ),
            task, "hi-IN",
        )
        assertEquals("hi-IN", plan!!.language)
    }

    @Test
    fun `an empty instruction falls back to the DSL wording in the user's language`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"amount",
                   "target":{"resource_id":"amount_field","index":1},
                   "instruction":"","language":"hi-IN","confidence":0.9,"reason":"ok"}"""
            ),
            task, "hi-IN",
        )
        assertEquals("इस बॉक्स में बिल की रकम भरिए।", plan!!.instruction)
        assertEquals("hi-IN", plan.language)
    }

    @Test
    fun `an invented step is rejected so the caller falls back deterministically`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"teleport",
                   "target":{"resource_id":"x","index":1},
                   "instruction":"go","language":"en-IN","confidence":0.99,"reason":"ok"}"""
            ),
            task, "en-IN",
        )
        assertNull(plan)
    }

    @Test
    fun `the DSL resource id wins over the model's echo`() {
        val plan = SarvamPlanner.parse(
            response(
                """{"intent":"pay_bill","step":"submit",
                   "target":{"resource_id":"hallucinated_id","index":2},
                   "instruction":"Tap to pay.","language":"en-IN","confidence":0.8,"reason":"ok"}"""
            ),
            task, "en-IN",
        )
        assertEquals("submit_button", plan!!.targetResourceId)
    }

    @Test
    fun `prose instead of a tool call is rejected`() {
        val raw = """{"choices":[{"message":{"content":"Sure! Let me help you pay."}}]}"""
        assertNull(SarvamPlanner.parse(raw, task, "en-IN"))
    }

    @Test
    fun `an empty response is rejected`() {
        assertNull(SarvamPlanner.parse("{}", task, "en-IN"))
    }

    @Test
    fun `malformed guidance JSON is an invalid response`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome("{not-json", "Найти профиль")

        assertFailure(PlannerFailureKind.INVALID_RESPONSE, outcome)
    }

    @Test
    fun `forbidden executable action is rejected`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            guidanceResponse(
                """{"is_done":false,"action_type":"click",
                    "target":{"resource_id":"profile","text":"Профиль"},
                    "instruction":"Откройте профиль","language":"ru-RU",
                    "confidence":0.9,"reason":"target visible"}"""
            ),
            "Найти профиль",
        )

        assertFailure(PlannerFailureKind.INVALID_RESPONSE, outcome)
    }

    @Test
    fun `guide without a semantic target is rejected`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            guidanceResponse(
                """{"is_done":false,"action_type":"guide",
                    "instruction":"Откройте профиль","language":"ru-RU",
                    "confidence":0.9,"reason":"missing target"}"""
            ),
            "Найти профиль",
        )

        assertFailure(PlannerFailureKind.INVALID_RESPONSE, outcome)
    }

    @Test
    fun `final order or payment target is rejected`() {
        listOf(
            "Оплатить",
            "Заказать",
            "Оформить",
            "Подтвердить",
            "Купить",
            "Отправить заявление",
            "Place order",
            "Pay now",
            "Checkout",
            "Submit application",
        ).forEach { label ->
            val outcome = SarvamPlanner.parseGuidanceOutcome(
                guidanceResponse(
                    """{"is_done":false,"action_type":"guide",
                        "target":{"resource_id":"final_action","text":${org.json.JSONObject.quote(label)}},
                        "instruction":"Выберите указанную кнопку","language":"ru-RU",
                        "confidence":0.95,"reason":"final button"}"""
                ),
                "Завершить действие",
            )

            assertFailure(PlannerFailureKind.INVALID_RESPONSE, outcome)
        }
    }

    @Test
    fun `benign order history target remains guidable`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            guidanceResponse(
                """{"is_done":false,"action_type":"guide",
                    "target":{"resource_id":"order_history","text":"История заказов"},
                    "instruction":"Откройте «История заказов»","language":"ru-RU",
                    "confidence":0.9,"reason":"visible target"}"""
            ),
            "Посмотреть старые заказы",
        )

        assertTrue(outcome is PlannerOutcome.Success)
    }

    @Test
    fun `natural final action instructions are rejected for guide and answer`() {
        val unsafe = listOf(
            """{"is_done":false,"action_type":"guide",
                "target":{"resource_id":"primary_button"},
                "instruction":"Нажмите «Оплатить»","language":"ru-RU",
                "confidence":0.9,"reason":"visible target"}""",
            """{"is_done":false,"action_type":"guide",
                "target":{"resource_id":"primary_button"},
                "instruction":"Выберите «Заказать»","language":"ru-RU",
                "confidence":0.9,"reason":"visible target"}""",
            """{"is_done":true,"action_type":"answer",
                "instruction":"Нажмите «Оплатить»","language":"ru-RU",
                "confidence":0.9,"reason":"direct answer"}""",
            """{"is_done":true,"action_type":"answer",
                "instruction":"Чтобы продолжить, нажмите «Оплатить»","language":"ru-RU",
                "confidence":0.9,"reason":"direct answer"}""",
            """{"is_done":false,"action_type":"guide",
                "target":{"resource_id":"primary_button","text":"Подать заявление"},
                "instruction":"Выберите указанную кнопку","language":"ru-RU",
                "confidence":0.9,"reason":"visible target"}""",
        )

        unsafe.forEach { args ->
            assertFailure(
                PlannerFailureKind.INVALID_RESPONSE,
                SarvamPlanner.parseGuidanceOutcome(guidanceResponse(args), "Завершить заказ"),
            )
        }
    }

    @Test
    fun `descriptive answer may name a final control without directing the user`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            guidanceResponse(
                """{"is_done":true,"action_type":"answer",
                    "instruction":"На экране есть кнопка «Оплатить» и указан итог заказа.",
                    "language":"ru-RU","confidence":0.9,"reason":"screen description"}"""
            ),
            "Что написано на экране?",
        )

        assertTrue(outcome is PlannerOutcome.Success)
    }

    @Test
    fun `guidance parser enforces the declared schema`() {
        val invalidArguments = listOf(
            // Required fields cannot be silently defaulted.
            """{"action_type":"guide","target":{"text":"Профиль"},
                "instruction":"Откройте профиль","language":"ru-RU","confidence":0.9,"reason":"visible"}""",
            // Unknown and legacy flat fields are outside the new contract.
            """{"is_done":false,"action_type":"guide","target_text":"Профиль",
                "instruction":"Откройте профиль","language":"ru-RU","confidence":0.9,"reason":"visible"}""",
            """{"is_done":false,"action_type":"guide","target":{"text":"Профиль"},
                "instruction":"Откройте профиль","language":"ru-RU","confidence":0.9,"reason":"visible","extra":"x"}""",
            // Types, range and action/done consistency are strict.
            """{"is_done":"false","action_type":"guide","target":{"text":"Профиль"},
                "instruction":"Откройте профиль","language":"ru-RU","confidence":0.9,"reason":"visible"}""",
            """{"is_done":false,"action_type":"guide","target":{"text":"Профиль"},
                "instruction":"Откройте профиль","language":"ru-RU","confidence":1.1,"reason":"visible"}""",
            """{"is_done":true,"action_type":"guide","target":{"text":"Профиль"},
                "instruction":"Откройте профиль","language":"ru-RU","confidence":0.9,"reason":"visible"}""",
            """{"is_done":false,"action_type":"answer",
                "instruction":"Профиль находится в меню","language":"ru-RU","confidence":0.9,"reason":"answer"}""",
        )

        invalidArguments.forEach { args ->
            assertFailure(
                PlannerFailureKind.INVALID_RESPONSE,
                SarvamPlanner.parseGuidanceOutcome(guidanceResponse(args), "Найти профиль"),
            )
        }
    }

    @Test
    fun `guidance parser accepts exactly one tool call`() {
        val one = """{"function":{"name":"set_next_step","arguments":${
            org.json.JSONObject.quote(
                """{"is_done":true,"action_type":"answer","instruction":"Готово.",
                    "language":"ru-RU","confidence":0.9,"reason":"answer"}"""
            )
        }}}"""
        val raw = """{"choices":[{"message":{"tool_calls":[$one,$one]}}]}"""

        assertFailure(
            PlannerFailureKind.INVALID_RESPONSE,
            SarvamPlanner.parseGuidanceOutcome(raw, "Что дальше?"),
        )
    }

    @Test
    fun `valid one shot answer needs no target`() {
        val outcome = SarvamPlanner.parseGuidanceOutcome(
            guidanceResponse(
                """{"is_done":true,"action_type":"answer",
                    "instruction":"История заказов находится в разделе «Профиль».",
                    "language":"ru-RU","confidence":0.93,"reason":"visible navigation"}"""
            ),
            "Где история заказов?",
        )

        assertTrue(outcome is PlannerOutcome.Success)
        val plan = (outcome as PlannerOutcome.Success).plan
        assertEquals("answer", plan.actionType)
        assertTrue(plan.isDone)
        assertEquals("", plan.targetResourceId)
        assertEquals("", plan.targetText)
    }

    private fun guidanceResponse(args: String): String =
        """{"choices":[{"message":{"tool_calls":[{"function":{"name":"set_next_step","arguments":${
            org.json.JSONObject.quote(args)
        }}}]}}]}"""

    private fun assertFailure(kind: PlannerFailureKind, outcome: PlannerOutcome) {
        assertTrue(outcome is PlannerOutcome.Failure)
        assertEquals(kind, (outcome as PlannerOutcome.Failure).kind)
    }
}
