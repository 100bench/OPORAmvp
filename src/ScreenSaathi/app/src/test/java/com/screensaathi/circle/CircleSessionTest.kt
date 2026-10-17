package com.screensaathi.circle

import com.screensaathi.screen.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the MVP boundary: a circle is resolved from the live UI tree only. */
class CircleSessionTest {

    private class FailIfCaptured : ScreenCaptureProvider {
        override val isAvailable: Boolean = true
        var calls = 0

        override fun captureCurrentScreen(onResult: (Result<ScreenFrame>) -> Unit) {
            calls++
            throw AssertionError("The tree-only MVP must never capture a screenshot")
        }
    }

    private class FailIfVisionCalled : VisionProvider {
        override val isAvailable: Boolean = true
        override val displayName: String = "forbidden in MVP"
        var calls = 0

        override fun analyzeSelection(
            frame: ScreenFrame,
            selection: ScreenSelection,
            userPrompt: String,
            onResult: (VisionResult) -> Unit,
        ) {
            calls++
            throw AssertionError("The tree-only MVP must never call a vision model")
        }
    }

    private val capture = FailIfCaptured()
    private val vision = FailIfVisionCalled()

    private fun session(snapshot: ScreenSnapshot? = null) = CircleSession(
        capture = capture,
        vision = vision,
        snapshotProvider = { snapshot },
        clock = { 1_000L },
    )

    private fun path(offsetY: Int = 0) = listOf(
        SelectionPoint(100, 500 + offsetY),
        SelectionPoint(900, 500 + offsetY),
        SelectionPoint(900, 600 + offsetY),
        SelectionPoint(100, 600 + offsetY),
    )

    @Test
    fun `selection uses the accessibility snapshot without capturing pixels`() {
        val snapshot = ScreenSnapshot(
            packageName = "com.example.app",
            settled = true,
            elements = emptyList(),
        )
        val s = session(snapshot)
        var resolved: CircleContext? = null

        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "ru-RU") { resolved = it }

        assertNotNull(resolved)
        assertEquals("com.example.app", resolved!!.selection.packageName)
        assertEquals(snapshot.signature(), resolved!!.selection.screenSignature)
        assertNull(resolved!!.frame)
        assertNull(resolved!!.selection.cropPath)
        assertEquals(0, capture.calls)
        assertEquals(0, vision.calls)
    }

    @Test
    fun `missing accessibility tree reports an honest unavailable target`() {
        val s = session()
        var resolved: CircleContext? = null

        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "ru-RU") { resolved = it }

        assertNotNull(resolved)
        assertNull(resolved!!.target.element)
        assertTrue(resolved!!.target.reason.contains("accessibility service"))
        assertEquals("ru-RU", resolved!!.languageCode)
        assertEquals(0, capture.calls)
    }

    @Test
    fun `visual analysis is locally unavailable and never reaches vision provider`() {
        val s = session()
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "ru-RU") {}

        var result: VisionResult? = null
        s.analyzeVisually("Что здесь?") { result = it }

        assertEquals(
            VisionResult.Unavailable.Reason.NO_PIXELS,
            (result as VisionResult.Unavailable).reason,
        )
        assertEquals(0, vision.calls)
    }

    @Test
    fun `a newer selection replaces the previous tree context synchronously`() {
        val s = session(ScreenSnapshot("com.example.app", true, emptyList()))

        s.onSelectionDrawn(path(offsetY = 0), SelectionShape.FREEFORM, "ru-RU") {}
        val firstTop = s.context!!.selection.bounds.top
        s.onSelectionDrawn(path(offsetY = 800), SelectionShape.FREEFORM, "ru-RU") {}

        assertTrue(s.context!!.selection.bounds.top != firstTop)
        assertEquals(0, capture.calls)
    }

    @Test
    fun `turns and active task remain attached to the live selection`() {
        val s = session()
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "ru-RU") {}

        s.addTurn("Что это?", CircleIntent.INFORMATION, "Кнопка.")
        s.setActiveTask("taxi")

        assertEquals(1, s.context!!.turns.size)
        assertTrue(s.context!!.isAgentActive)
    }

    @Test
    fun `clear drops the context without invoking pixel providers`() {
        val s = session()
        s.onSelectionDrawn(path(), SelectionShape.FREEFORM, "ru-RU") {}

        s.clear()

        assertNull(s.context)
        assertEquals(0, capture.calls)
        assertEquals(0, vision.calls)
    }
}
