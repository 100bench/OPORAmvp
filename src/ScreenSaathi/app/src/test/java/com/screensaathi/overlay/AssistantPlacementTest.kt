package com.screensaathi.overlay

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPlacementTest {
    @Test
    fun `keeps the preferred card position when it does not cover the target`() {
        val result = AssistantPlacement.avoidTarget(
            10, 50, 346, 260, rect(0, 0, 400, 800), rect(20, 500, 160, 570), 12,
        )

        assertEquals(AssistantPlacement.Avoidance.Move(10, 50), result)
    }

    @Test
    fun `moves a card away from a central target`() {
        val target = rect(200, 350, 320, 420)
        val result = AssistantPlacement.avoidTarget(
            40, 250, 346, 260, rect(0, 0, 400, 800), target, 12,
        )

        assertTrue(result is AssistantPlacement.Avoidance.Move)
        val moved = result as AssistantPlacement.Avoidance.Move
        assertFalse(overlaps(rect(moved.x, moved.y, moved.x + 346, moved.y + 260), target, 12))
    }

    @Test
    fun `target near bottom edge moves card above it`() {
        val target = rect(220, 690, 380, 750)
        val result = AssistantPlacement.avoidTarget(
            40, 550, 346, 260, rect(0, 0, 400, 800), target, 12,
        )

        assertTrue(result is AssistantPlacement.Avoidance.Move)
        assertTrue((result as AssistantPlacement.Avoidance.Move).y + 260 <= target.top - 12)
    }

    @Test
    fun `right-edge target is not covered after clamping`() {
        val target = rect(330, 200, 400, 270)
        val result = AssistantPlacement.avoidTarget(
            54, 190, 346, 260, rect(0, 0, 400, 800), target, 12,
        )

        assertTrue(result is AssistantPlacement.Avoidance.Move)
        val moved = result as AssistantPlacement.Avoidance.Move
        assertFalse(overlaps(rect(moved.x, moved.y, moved.x + 346, moved.y + 260), target, 12))
    }

    @Test
    fun `keyboard safe bottom is honored when placing near a target`() {
        val safeAboveKeyboard = rect(0, 24, 400, 500)
        val result = AssistantPlacement.avoidTarget(
            40, 350, 346, 220, safeAboveKeyboard, rect(200, 320, 350, 380), 12,
        )

        assertTrue(result is AssistantPlacement.Avoidance.Move)
        val moved = result as AssistantPlacement.Avoidance.Move
        assertTrue(moved.y >= safeAboveKeyboard.top)
        assertTrue(moved.y + 220 <= safeAboveKeyboard.bottom)
    }

    @Test
    fun `oversized target or insufficient space collapses the card`() {
        assertEquals(
            AssistantPlacement.Avoidance.Collapse,
            AssistantPlacement.avoidTarget(
                20, 20, 346, 260, rect(0, 0, 400, 500), rect(0, 0, 400, 500), 12,
            ),
        )
        assertEquals(
            AssistantPlacement.Avoidance.Collapse,
            AssistantPlacement.avoidTarget(
                20, 20, 346, 260, rect(0, 0, 300, 200), rect(50, 50, 100, 100), 12,
            ),
        )
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun overlaps(a: Rect, b: Rect, gap: Int) =
        a.left < b.right + gap && a.right > b.left - gap &&
            a.top < b.bottom + gap && a.bottom > b.top - gap
}
