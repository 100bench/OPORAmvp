package com.screensaathi.screen

import android.graphics.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiTreeFixtureTest {
    @Test fun `editable values and unapproved labels never reach the fixture`() {
        val secrets = listOf("private editable value", "private hint", "private description")
        val snapshot = snapshot(
            element(0).copy(editable = true, text = secrets[0], hintText = secrets[1],
                contentDescription = secrets[2]),
            element(1).copy(text = "private address", hintText = "private name",
                contentDescription = "private plate"),
            element(2).copy(text = "Продолжить", hintText = "Статическая подсказка",
                contentDescription = "Кнопка продолжения"),
        )
        val json = UiTreeFixtureCodec.encodeSanitized(snapshot,
            (secrets + listOf("Продолжить", "Статическая подсказка", "Кнопка продолжения")).toSet())
        (secrets + listOf("private address", "private name", "private plate")).forEach {
            assertFalse(it, json.contains(it))
        }
        val replay = UiTreeFixtureCodec.decode(json)
        assertEquals("", replay.elements[0].text)
        assertEquals("", replay.elements[0].hintText)
        assertEquals("", replay.elements[0].contentDescription)
        assertEquals("[REDACTED]", replay.elements[1].text)
        assertEquals("Продолжить", replay.elements[2].text)
        assertEquals("Статическая подсказка", replay.elements[2].hintText)
        assertEquals("Кнопка продолжения", replay.elements[2].contentDescription)
    }

    @Test fun `approved labels still redact phone email long numbers and OTP`() {
        val labels = listOf("Email: sample@example.com", "Телефон +7 (999) 123-45-67",
            "Номер 1234567890123456", "Код 482913")
        val json = UiTreeFixtureCodec.encodeSanitized(
            snapshot(*labels.mapIndexed { i, label -> element(i).copy(text = label) }.toTypedArray()),
            labels.toSet())
        listOf("sample@example.com", "+7 (999) 123-45-67", "1234567890123456", "482913").forEach {
            assertFalse(it, json.contains(it))
        }
        assertEquals(listOf("Email: [EMAIL]", "Телефон [PHONE]", "Номер [NUMBER]", "Код [OTP]"),
            UiTreeFixtureCodec.decode(json).elements.map { it.text })
    }

    @Test fun `password nodes are omitted and ancestry never points to an omitted node`() {
        val json = UiTreeFixtureCodec.encodeSanitized(snapshot(
            element(3).copy(text = "password-secret", password = true),
            element(4).copy(clickableAncestorIndex = 3),
            element(5).copy(clickableAncestorIndex = 4),
        ), setOf("password-secret"))
        assertFalse(json.contains("password-secret"))
        val replay = UiTreeFixtureCodec.decode(json)
        assertEquals(listOf(4, 5), replay.elements.map { it.index })
        assertNull(replay.elements[0].clickableAncestorIndex)
        assertEquals(4, replay.elements[1].clickableAncestorIndex)
    }

    @Test fun `structural geometry role ids and flags survive replay`() {
        val source = snapshot(element(7).copy(resourceId = "example:id/search", className = "EditText",
            editable = true, clickable = true, enabled = false, selected = true, checked = true,
            scrollable = true, inputType = 2, visibleToUser = false, inDialog = true),
            element(8).copy(clickableAncestorIndex = 7))
            .copy(activeDialog = true, viewport = ScreenViewport(0, 24, 1080, 2100))
        val replay = UiTreeFixtureCodec.decode(UiTreeFixtureCodec.encodeSanitized(source, emptySet()))
        assertEquals("example.app", replay.packageName)
        assertTrue(replay.settled)
        assertTrue(replay.activeDialog)
        assertEquals(ScreenViewport(0, 24, 1080, 2100), replay.viewport)
        with(replay.elements.first()) {
            assertEquals(7, index)
            assertEquals("example:id/search", resourceId)
            assertEquals("EditText", className)
            assertEquals(listOf(10, 20, 110, 120), listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
            assertTrue(editable && clickable && selected && checked && scrollable && inDialog)
            assertFalse(enabled || visibleToUser || password)
            assertEquals(2, inputType)
        }
        assertEquals(7, replay.elements[1].clickableAncestorIndex)
        assertNull(UiTreeFixtureCodec.decode(UiTreeFixtureCodec.encodeSanitized(snapshot(), emptySet())).viewport)
    }

    @Test fun `decoder rejects raw legacy unknown and unsafe fixture payloads`() {
        val safe = UiTreeFixtureCodec.encodeSanitized(snapshot(element(0)), emptySet())
        val invalid = listOf("{}", JSONObject(safe).put("schemaVersion", 99).toString(),
            JSONObject(safe).put("captureKind", "raw").toString(),
            JSONObject(safe).put("rawTree", "forbidden").toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("password", true) }.toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("text", "code 482913") }.toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("editable", true)
                .put("text", "value") }.toString())
        invalid.forEach { json ->
            try { UiTreeFixtureCodec.decode(json); fail("Unsafe schema accepted") }
            catch (_: IllegalArgumentException) { /* expected */ }
        }
    }

    private fun snapshot(vararg elements: ScreenElement) = ScreenSnapshot("example.app", true, elements.toList())
    private fun element(index: Int) = ScreenElement(index, "control", "", "TextView",
        Rect().apply { left = 10; top = 20; right = 110; bottom = 120 }, false, false)
}
