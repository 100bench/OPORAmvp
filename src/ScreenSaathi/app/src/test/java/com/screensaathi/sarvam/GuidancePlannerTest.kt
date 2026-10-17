package com.screensaathi.sarvam

import android.graphics.Rect
import com.screensaathi.screen.PlannerScreen
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSanitizer
import com.screensaathi.screen.ScreenSnapshot
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidancePlannerTest {

    @Test
    fun `missing configuration is typed and makes no transport call`() {
        val transport = QueueTransport(validAnswer())
        val planner = SarvamPlanner(transport, guidanceConfigured = { false })

        val outcome = planner.planNext(safeScreen(), "Где профиль?", 1, null)

        assertFailure(PlannerFailureKind.CONFIGURATION, outcome)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `IOException is network failure and is not retried`() {
        val transport = QueueTransport(IOException("offline"))
        val planner = SarvamPlanner(transport)

        val outcome = planner.planNext(safeScreen(), "Где профиль?", 1, null)

        assertFailure(PlannerFailureKind.NETWORK, outcome)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `non 2xx is remote failure without response body leakage or retry`() {
        val secretBody = "server leaked raw secret 482913"
        val transport = QueueTransport(GuidanceTransportResponse(503, secretBody))
        val planner = SarvamPlanner(transport)

        val outcome = planner.planNext(safeScreen(), "Где профиль?", 1, null)

        assertFailure(PlannerFailureKind.REMOTE, outcome)
        assertFalse((outcome as PlannerOutcome.Failure).reason.contains(secretBody))
        assertEquals(1, transport.calls)
    }

    @Test
    fun `valid response uses one call`() {
        val transport = QueueTransport(validAnswer())
        val planner = SarvamPlanner(transport)

        val outcome = planner.planNext(safeScreen(), "Где профиль?", 1, null)

        assertTrue(outcome is PlannerOutcome.Success)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `single planner attempt returns invalid without owning orchestration retry`() {
        val transport = QueueTransport("{bad-json")
        val planner = SarvamPlanner(transport)

        val outcome = planner.planNext(safeScreen(), "Где профиль?", 1, null)

        assertFailure(PlannerFailureKind.INVALID_RESPONSE, outcome)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `legacy caller retries invalid then valid once with a repair request`() {
        val transport = QueueTransport("{bad-json", validAnswer())
        val planner = SarvamPlanner(transport)

        val outcome = planner.planOpenEnded("Где профиль?", rawSafeScreen(), "ru-RU")

        assertTrue(outcome != null)
        assertEquals(2, transport.calls)
        assertTrue(transport.requests.last().contains("repair_format"))
    }

    @Test
    fun `legacy caller stops after two invalid calls`() {
        val transport = QueueTransport("{bad-json", "{still-bad")
        val planner = SarvamPlanner(transport)

        val outcome = planner.planOpenEnded("Где профиль?", rawSafeScreen(), "ru-RU")

        assertEquals(null, outcome)
        assertEquals(2, transport.calls)
    }

    @Test
    fun `legacy caller forwards the real guidance step number`() {
        val transport = QueueTransport(validAnswer())
        val planner = SarvamPlanner(transport)

        planner.planOpenEnded(
            transcript = "Где профиль?",
            screen = rawSafeScreen(),
            spokenLanguage = "ru-RU",
            completedStep = "Откройте меню",
            stepNumber = 7,
        )

        assertTrue(transport.requests.single().contains("Номер логического шага: 7"))
    }

    @Test
    fun `serialized request contains only safe DTO and non executable schema`() {
        val rawEditable = "улица Секретная, 42"
        val plannerScreen = ScreenSanitizer.sanitize(
            ScreenSnapshot(
                packageName = "ru.example.app",
                settled = true,
                elements = listOf(
                    ScreenElement(
                        index = 1,
                        resourceId = "address",
                        text = rawEditable,
                        contentDescription = "Адрес",
                        hintText = "Введите адрес",
                        className = "EditText",
                        bounds = Rect(10, 20, 900, 120),
                        editable = true,
                        clickable = true,
                    )
                ),
            )
        )
        val transport = QueueTransport(validAnswer())
        val planner: GuidancePlanner = SarvamPlanner(transport)

        planner.planNext(
            screen = plannerScreen,
            goal = "Найти адрес",
            stepNumber = 2,
            lastSafeInstruction = "Откройте поиск",
        )

        val request = transport.requests.single()
        assertFalse(request.contains(rawEditable))
        assertFalse(request.contains("bounds", ignoreCase = true))
        assertFalse(request.contains("coordinate", ignoreCase = true))
        assertFalse(request.contains("type_text"))
        assertFalse(request.contains("launch_app"))
        assertFalse(request.contains("action_payload"))
        assertTrue(request.contains("недоверенные данные"))
        assertTrue(request.contains("оплат"))

        val actionTypes = JSONObject(request)
            .getJSONArray("tools").getJSONObject(0)
            .getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties").getJSONObject("action_type")
            .getJSONArray("enum")
        assertEquals(listOf("guide", "answer"), (0 until actionTypes.length()).map(actionTypes::getString))
    }

    private fun safeScreen() = PlannerScreen(
        packageName = "ru.example.app",
        settled = true,
        elements = emptyList(),
    )

    private fun rawSafeScreen() = ScreenSnapshot(
        packageName = "ru.example.app",
        settled = true,
        elements = emptyList(),
    )

    private fun validAnswer(): String = response(
        """{"is_done":true,"action_type":"answer",
            "instruction":"Профиль открывается в нижнем меню.",
            "language":"ru-RU","confidence":0.9,"reason":"direct answer"}"""
    )

    private fun response(args: String): String =
        """{"choices":[{"message":{"tool_calls":[{"function":{"name":"set_next_step","arguments":${
            JSONObject.quote(args)
        }}}]}}]}"""

    private fun assertFailure(kind: PlannerFailureKind, outcome: PlannerOutcome) {
        assertTrue(outcome is PlannerOutcome.Failure)
        assertEquals(kind, (outcome as PlannerOutcome.Failure).kind)
    }

    private class QueueTransport(vararg results: Any) : GuidanceTransport {
        private val queue = ArrayDeque(results.toList())
        val requests = mutableListOf<String>()
        var calls: Int = 0
            private set

        override fun execute(requestBody: String): GuidanceTransportResponse {
            calls++
            requests += requestBody
            return when (val next = queue.removeFirst()) {
                is IOException -> throw next
                is GuidanceTransportResponse -> next
                is String -> GuidanceTransportResponse(200, next)
                else -> error("Unsupported fake result: $next")
            }
        }
    }
}
