package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetResolverTest {

    @Test
    fun `cyrillic labels do not all normalize to the same empty string`() {
        val snapshot = ScreenSnapshot(
            packageName = "ru.yandex.taxi",
            settled = true,
            elements = listOf(
                element(0, "Еда", Rect(10, 10, 210, 110)),
                element(1, "Такси", Rect(220, 10, 420, 110)),
            ),
        )

        assertTrue(TargetResolver.resolve("Лавка", snapshot) is TargetResolver.Result.NotFound)
    }

    @Test
    fun `finds an exact cyrillic food target`() {
        val snapshot = ScreenSnapshot(
            packageName = "ru.yandex.taxi",
            settled = true,
            elements = listOf(
                element(0, "Лавка", Rect(10, 10, 210, 110)),
                element(1, "Еда", Rect(220, 10, 420, 110)),
            ),
        )

        val result = TargetResolver.resolve("Лавка", snapshot)

        assertTrue(result is TargetResolver.Result.Found)
        assertEquals("Лавка", (result as TargetResolver.Result.Found).candidate.element.text)
    }

    @Test
    fun `does not resolve a natural language query to a non interactive id container`() {
        val snapshot = ScreenSnapshot(
            packageName = "com.google.android.calculator",
            settled = true,
            elements = listOf(
                ScreenElement(
                    index = 0,
                    resourceId = "main_calculator",
                    text = "",
                    className = "ViewGroup",
                    bounds = Rect(0, 0, 1080, 2392),
                    editable = false,
                    clickable = false,
                ),
            ),
        )

        val result = TargetResolver.resolve("Calculator", snapshot)

        assertTrue(result is TargetResolver.Result.NotFound)
    }

    @Test
    fun `resolver keeps using raw local bounds after planner DTO split`() {
        val localBounds = Rect(31, 47, 731, 247)
        val snapshot = ScreenSnapshot(
            packageName = "ru.example.app",
            settled = true,
            elements = listOf(element(9, "Продолжить", localBounds)),
        )

        val result = TargetResolver.resolve("Продолжить", snapshot)

        assertTrue(result is TargetResolver.Result.Found)
        assertSame(localBounds, (result as TargetResolver.Result.Found).candidate.element.bounds)
        assertTrue(PlannerElement::class.java.declaredFields.none { it.type.name == "android.graphics.Rect" })
    }

    private fun element(index: Int, text: String, bounds: Rect) = ScreenElement(
        index = index,
        resourceId = "",
        text = text,
        className = "TextView",
        bounds = bounds,
        editable = false,
        clickable = true,
    )
}
