package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiTargetResolverTest {

    @Test
    fun `planner index resolves the same visible actionable node`() {
        val expected = element(index = 7)
        val snapshot = snapshot(expected, element(index = 8, text = "Другое"))

        val result = TargetResolver.resolveIndex(7, snapshot)

        assertTrue("Expected Found, got ${result.javaClass.simpleName}", result is TargetResolver.Result.Found)
        assertSame(expected, (result as TargetResolver.Result.Found).candidate.element)
    }

    @Test
    fun `planner index cannot target absent hidden disabled or static nodes`() {
        val candidates = listOf(
            snapshot(element(index = 1)) to 99,
            snapshot(element(index = 2).copy(visibleToUser = false)) to 2,
            snapshot(element(index = 3).copy(enabled = false)) to 3,
            snapshot(element(index = 4).copy(clickable = false)) to 4,
        )

        candidates.forEach { (screen, index) ->
            assertTrue(TargetResolver.resolveIndex(index, screen) !is TargetResolver.Result.Found)
        }
    }

    @Test
    fun `planner index cannot redirect a static label to its clickable ancestor`() {
        val parent = element(index = 6, text = "Куда поедете?")
        val label = element(index = 7, text = "Куда поедете?").copy(
            clickable = false,
            clickableAncestorIndex = parent.index,
        )

        val result = TargetResolver.resolveIndex(label.index, snapshot(parent, label))

        assertTrue(result !is TargetResolver.Result.Found)
    }

    @Test
    fun `planner index cannot redirect a static label to a nearby input`() {
        val label = element(index = 10, text = "Адрес").copy(
            bounds = rect(40, 100, 900, 180),
            clickable = false,
        )
        val field = element(index = 11, text = "").copy(
            bounds = rect(40, 220, 900, 380),
            editable = true,
        )

        val result = TargetResolver.resolveIndex(label.index, snapshot(label, field))

        assertTrue(result !is TargetResolver.Result.Found)
    }

    @Test
    fun `semantic taxi label resolves to containing card not full screen ancestor`() {
        val fullScreen = element(index = 6, text = "").copy(
            bounds = rect(0, 0, 1080, 2200),
        )
        val label = element(index = 7, text = "Такси").copy(
            bounds = rect(666, 648, 901, 682),
            clickable = false,
            clickableAncestorIndex = fullScreen.index,
        )
        val card = element(index = 8, text = "").copy(
            bounds = rect(540, 463, 1028, 706),
        )

        val result = TargetResolver.resolveLabel("Такси", snapshot(fullScreen, label, card))

        assertTrue("Expected Found, got ${result.javaClass.simpleName}", result is TargetResolver.Result.Found)
        assertSame(card, (result as TargetResolver.Result.Found).candidate.element)
    }

    private fun snapshot(vararg elements: ScreenElement) = ScreenSnapshot(
        packageName = "ru.yandex.taxi",
        settled = true,
        elements = elements.toList(),
        viewport = ScreenViewport(0, 0, 1080, 2200),
    )

    private fun element(index: Int, text: String = "Куда поедете?") = ScreenElement(
        index = index,
        resourceId = "node_$index",
        text = text,
        className = "android.widget.Button",
        bounds = Rect().apply {
            left = 40
            top = 100 + index * 20
            right = 900
            bottom = 220 + index * 20
        },
        editable = false,
        clickable = true,
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
