package com.screensaathi.screen

import android.graphics.Rect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiTreeFixtureTest {
    @Test fun `only approved static hint of a proven empty editable survives`() {
        val source = element(0).copy(editable = true, text = "Куда поедете?",
            hintText = "Куда поедете?", editableValueEmpty = true)
        val replay = UiTreeFixtureCodec.decode(UiTreeFixtureCodec.encodeSanitized(snapshot(
            source, source.copy(index = 1, editableValueEmpty = false),
            source.copy(index = 2, hintText = "unapproved hint"),
            source.copy(index = 3, password = true),
        ), setOf("Куда поедете?")))
        assertEquals(listOf("Куда поедете?", "", ""), replay.elements.map { it.hintText })
        assertTrue(replay.elements.all { it.text.isEmpty() && it.contentDescription.isEmpty() })
        assertFalse(ScreenSanitizer.sanitize(replay).toPromptText().contains("editableValueEmpty"))
    }

    @Test fun `v2 provenance is required strict and distinguishes synthetic from real`() {
        val metadata = UiTreeFixtureMetadata("accessibility_debug_export", "2026-10-27", "SM-N975F",
            "12", "5.98.4", "destination-empty-keyboard")
        val json = UiTreeFixtureCodec.encodeSanitized(snapshot(element(0)), emptySet(), metadata)
        val root = JSONObject(json)
        assertEquals(2, root.getInt("schemaVersion"))
        assertEquals("sanitized_real", root.getString("captureKind"))
        assertEquals("SM-N975F", root.getJSONObject("metadata").getString("deviceModel"))
        UiTreeFixtureCodec.decode(json)
        val synthetic = JSONObject(UiTreeFixtureCodec.encodeSanitized(snapshot(), emptySet()))
        assertEquals("synthetic", synthetic.getString("captureKind"))
        listOf(
            JSONObject(json).put("schemaVersion", 1),
            JSONObject(json).apply { put("schemaVersion", 1); put("captureKind", "sanitized_accessibility"); remove("metadata") },
            JSONObject(json).apply { remove("metadata") },
            JSONObject(json).put("captureKind", "synthetic"),
            JSONObject(json).apply { getJSONObject("metadata").put("account", "forbidden") },
            JSONObject(json).apply { getJSONObject("metadata").put("captureDate", "2026-99-99") },
        ).forEach {
            try { UiTreeFixtureCodec.decode(it.toString()); fail("Invalid provenance accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `approved empty field hints still pass personal pattern redaction`() {
        val hint = "sample@example.com"
        val json = UiTreeFixtureCodec.encodeSanitized(snapshot(element(0).copy(
            editable = true, text = hint, contentDescription = hint, hintText = hint,
            editableValueEmpty = true)), setOf(hint))
        assertFalse(json.contains(hint))
        val node = UiTreeFixtureCodec.decode(json).elements.single()
        assertEquals("[EMAIL]", node.hintText)
        assertEquals("", node.text)
        assertEquals("", node.contentDescription)
    }

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
        val source = snapshot(element(7).copy(resourceId = "android:id/button1", className = "EditText",
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
            assertEquals("android:id/button1", resourceId)
            assertEquals("EditText", className)
            assertEquals(listOf(10, 20, 110, 120), listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))
            assertTrue(editable && clickable && selected && checked && scrollable && inDialog)
            assertFalse(enabled || visibleToUser || password)
            assertEquals(2, inputType)
        }
        assertEquals(7, replay.elements[1].clickableAncestorIndex)
        assertNull(UiTreeFixtureCodec.decode(UiTreeFixtureCodec.encodeSanitized(snapshot(), emptySet())).viewport)
    }

    @Test fun `external identifiers are opaque before serialization even with empty approval`() {
        val ids = listOf("plate_A123BC77", "vin_WVWZZZ1JZXW000001", "address_Private_Street")
        val roles = listOf("Private_Person_View", "plate_A123BC77_View", "address_Private_Street_View")
        val source = snapshot(*ids.mapIndexed { i, id ->
            element(i).copy(resourceId = id, className = roles[i])
        }.toTypedArray())
        val json = UiTreeFixtureCodec.encodeSanitized(source, emptySet())
        assertTrue("External identifiers reached serialization", (ids + roles).none { json.contains(it) })
        val replay = UiTreeFixtureCodec.decode(json)
        assertEquals(3, replay.elements.map { it.resourceId }.toSet().size)
        assertEquals(3, replay.elements.map { it.className }.toSet().size)
        assertTrue(replay.elements.all { it.resourceId.isNotBlank() && it.className.isNotBlank() })
    }

    @Test fun `opaque identifiers retain equality and distinction without trusting label approval`() {
        val first = element(0).copy(resourceId = "plate_A123BC77", className = "Private_Person_View")
        val second = first.copy(index = 1, clickableAncestorIndex = 0)
        val third = first.copy(index = 2, resourceId = "plate_B456CD78", className = "Another_Private_View")
        val source = snapshot(first, second, third)
        val labels = setOf(first.resourceId, first.className, third.resourceId, third.className)
        val json = UiTreeFixtureCodec.encodeSanitized(source, labels)
        assertTrue("Label approval bypassed identifier privacy", labels.none { json.contains(it) })
        val replay = UiTreeFixtureCodec.decode(json)
        assertEquals(replay.elements[0].resourceId, replay.elements[1].resourceId)
        assertEquals(replay.elements[0].className, replay.elements[1].className)
        assertNotEquals(replay.elements[0].resourceId, replay.elements[2].resourceId)
        assertNotEquals(replay.elements[0].className, replay.elements[2].className)
        assertEquals(0, replay.elements[1].clickableAncestorIndex)
        val repeat = UiTreeFixtureCodec.decode(UiTreeFixtureCodec.encodeSanitized(source, emptySet()))
        assertEquals(replay.elements.map { it.resourceId }, repeat.elements.map { it.resourceId })
        assertEquals(replay.elements.map { it.className }, repeat.elements.map { it.className })
    }

    @Test fun `decoder rejects raw legacy unknown and unsafe fixture payloads`() {
        val safe = UiTreeFixtureCodec.encodeSanitized(snapshot(element(0)), emptySet())
        val invalid = listOf("{}", JSONObject(safe).put("schemaVersion", 99).toString(),
            JSONObject(safe).put("captureKind", "raw").toString(),
            JSONObject(safe).put("rawTree", "forbidden").toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("password", true) }.toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("text", "code 482913") }.toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("resourceId", "plate_A123BC77") }.toString(),
            JSONObject(safe).apply { getJSONArray("elements").getJSONObject(0).put("className", "Private_Person_View") }.toString(),
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
