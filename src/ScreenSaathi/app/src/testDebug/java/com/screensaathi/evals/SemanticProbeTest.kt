package com.screensaathi.evals

import android.graphics.Rect
import com.screensaathi.debug.SemanticProbe
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenViewport
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticProbeTest {
    // Catches arbitrary-label/ID leakage and losing the actionable parent of a safe label.
    @Test fun `only fixed tokens and numeric ancestry survive unknown personal labels`() {
        val parent = element(0, "private full name").copy(clickable = true, resourceId = "private-address-id")
        val label = element(1, "Куда поедем? Москва private account").copy(clickableAncestorIndex = 0)
        val output = SemanticProbe.encode(screen(parent, label, element(2, "Безопасность")))
        val json = JSONObject(output)
        assertEquals(3, json.getInt("elements"))
        val node = json.getJSONArray("matches").getJSONObject(0)
        assertEquals(1, node.getInt("index"))
        assertEquals(0, node.getInt("actionIndex"))
        assertEquals("DESTINATION_PROMPT", node.getString("token"))
        assertEquals("PHRASE", node.getString("match"))
        assertEquals(10, node.getInt("left"))
        assertEquals(110, node.getInt("right"))
        listOf("private", "Москва", "Куда", "Безопасность").forEach { assertFalse(output.contains(it)) }
    }

    // Catches matching current editable values, hidden/password/disabled nodes and substring guesses.
    @Test fun `unreadable ineligible and filled editables never emit semantic matches`() {
        val output = JSONObject(SemanticProbe.encode(screen(
            element(0, "Такси").copy(editable = true, hintText = "Куда поедем?"),
            element(2, "Такси").copy(visibleToUser = false),
            element(3, "Такси").copy(enabled = false),
            element(4, "Таксист"),
            element(5, "secret").copy(editable = true, editableValueEmpty = true, hintText = "Куда поедете?"),
        )))
        assertEquals(1, output.getJSONArray("matches").length())
        val node = output.getJSONArray("matches").getJSONObject(0)
        assertEquals(5, node.getInt("index"))
        assertEquals("EXACT", node.getString("match"))
        assertTrue(node.getBoolean("editable"))
        assertEquals("DESTINATION_PROMPT", node.getString("token"))
    }

    @Test fun `password boundary suppresses unrelated generic labels`() {
        val json = JSONObject(SemanticProbe.encode(screen(element(0, "Такси"), element(1, "secret").copy(password = true))))
        assertEquals("PAUSE_AUTH", json.getString("disposition"))
        assertEquals(0, json.getJSONArray("matches").length())
    }

    // Catches bypassing the local final-action boundary just because another safe label is present.
    @Test fun `local risk suppresses all match geometry`() {
        val json = JSONObject(SemanticProbe.encode(screen(element(0, "Куда поедем?"), element(1, "Заказать такси"))))
        assertEquals("SAFE_HANDOFF", json.getString("disposition"))
        assertEquals("FINAL_ACTION", json.getString("risk"))
        assertEquals(0, json.getJSONArray("matches").length())
    }

    @Test fun `only exact known own start button yields assistant confirmation geometry`() {
        val button = element(10, "Начать").copy(resourceId = "decision_primary_button", clickable = true)
        val own = screen(button).copy(packageName = "com.screensaathi")
        val json = JSONObject(SemanticProbe.encode(screen(element(0, "unknown")), own))
        val match = json.getJSONArray("matches").getJSONObject(0)
        assertEquals("ASSISTANT_START", match.getString("token"))
        assertEquals(10, match.getInt("left"))
        for (unsafe in listOf(own.copy(packageName = "other"), screen(button.copy(text = "Отправить")).copy(packageName = "com.screensaathi"),
            screen(button.copy(resourceId = "other")).copy(packageName = "com.screensaathi"))) {
            assertEquals(0, JSONObject(SemanticProbe.encode(screen(element(0, "unknown")), unsafe)).getJSONArray("matches").length())
        }
    }

    @Test fun `approved public test place yields a token without surrounding result address`() {
        val output = SemanticProbe.encode(screen(element(0, "Красная площадь, private address").copy(clickable = true),
            element(1, "Red Square").copy(editable = true)))
        val matches = JSONObject(output).getJSONArray("matches")
        assertEquals(1, matches.length())
        assertEquals("PUBLIC_TEST_PLACE", matches.getJSONObject(0).getString("token"))
        assertEquals("PHRASE", matches.getJSONObject(0).getString("match"))
        assertFalse(output.contains("private"))
        assertFalse(output.contains("Красная"))
    }

    private fun element(index: Int, text: String) = ScreenElement(index, "private-id", text, "private-class",
        Rect().apply { left = 10; top = 20; right = 110; bottom = 120 }, false, false)
    private fun screen(vararg elements: ScreenElement) = ScreenSnapshot("private.package", true, elements.toList(),
        viewport = ScreenViewport(0, 0, 1080, 2153))
}
