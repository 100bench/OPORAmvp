package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenElementEligibilityTest {
    private val viewport = ScreenViewport(0, 0, 100, 100)

    @Test
    fun `visibility enabled password and positive area are required`() {
        val cases = listOf(
            element().copy(visibleToUser = false) to ScreenElementIneligibility.HIDDEN,
            element().copy(enabled = false) to ScreenElementIneligibility.DISABLED,
            element().copy(password = true) to ScreenElementIneligibility.PASSWORD,
            element(rect(10, 10, 10, 20)) to ScreenElementIneligibility.EMPTY_BOUNDS,
            element(rect(20, 10, 10, 20)) to ScreenElementIneligibility.EMPTY_BOUNDS,
        )
        cases.forEach { (element, reason) ->
            assertEquals(ScreenElementEligibility.Ineligible(reason), element.eligibility(viewport))
        }
    }

    @Test
    fun `fully below or edge touching viewport is outside`() {
        listOf(rect(0, 101, 20, 120), rect(0, 100, 20, 120)).forEach {
            assertEquals(ScreenElementEligibility.Ineligible(ScreenElementIneligibility.OUTSIDE_VIEWPORT),
                element(it).eligibility(viewport))
        }
    }

    @Test
    fun `center must be inside even when half the area intersects`() {
        assertEquals(ScreenElementEligibility.Ineligible(ScreenElementIneligibility.MOSTLY_CLIPPED),
            element(rect(50, 20, 150, 40)).eligibility(viewport))
    }

    @Test
    fun `exactly half visible with center on inclusive edge is eligible`() {
        assertEquals(ScreenElementEligibility.Eligible,
            element(rect(-50, 20, 50, 40)).eligibility(viewport))
    }

    @Test
    fun `just under half visible is rejected even with center inside`() {
        assertEquals(ScreenElementEligibility.Ineligible(ScreenElementIneligibility.MOSTLY_CLIPPED),
            element(rect(-42, -42, 100, 100)).eligibility(viewport))
    }

    @Test
    fun `invalid viewport fails closed`() {
        assertEquals(ScreenElementEligibility.Ineligible(ScreenElementIneligibility.OUTSIDE_VIEWPORT),
            element().eligibility(ScreenViewport(100, 100, 0, 0)))
    }

    @Test
    fun `large positive coordinates do not overflow center or area`() {
        assertEquals(ScreenElementEligibility.Eligible,
            element(rect(1_000_000_000, 1_000_000_000, 2_000_000_000, 2_000_000_000))
                .eligibility(ScreenViewport(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)))
    }

    @Test
    fun `full integer span handles subtraction and multiplication overflow`() {
        val fullSpan = element(rect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(ScreenElementEligibility.Eligible,
            fullSpan.eligibility(ScreenViewport(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)))
        assertEquals(ScreenElementEligibility.Ineligible(ScreenElementIneligibility.MOSTLY_CLIPPED),
            fullSpan.eligibility(ScreenViewport(Int.MIN_VALUE, Int.MIN_VALUE, 0, 0)))
    }

    // android.jar constructors are no-ops in local JVM tests; fields are real.
    private fun rect(l: Int, t: Int, r: Int, b: Int) = Rect().apply {
        left = l; top = t; right = r; bottom = b
    }

    private fun element(bounds: Rect = rect(10, 10, 20, 20)) = ScreenElement(
        index = 0, resourceId = "control", text = "Control", className = "Button",
        bounds = bounds, editable = false, clickable = true,
    )
}
