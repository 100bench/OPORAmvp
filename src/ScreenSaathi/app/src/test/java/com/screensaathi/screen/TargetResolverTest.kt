package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetResolverTest {

    @Test
    fun `Samsung bottom node outside app viewport is never a target`() {
        val node = element(0, "Куда", testRect(45, 2157, 1035, 2290))
        val result = TargetResolver.resolve("Куда", ScreenSnapshot("example.app", true,
            listOf(node), viewport = ScreenViewport(0, 63, 1080, 2153)))
        assertEquals("NotVisible", result.javaClass.simpleName)
    }

    @Test
    fun `clipped and hidden matches are not visible while disabled and password matches are not actionable`() {
        val node = element(0, "Куда", testRect(10, 10, 110, 80))
        listOf(
            node.copy(bounds = testRect(10, 190, 110, 280)) to "NotVisible",
            node.copy(visibleToUser = false) to "NotVisible",
            node.copy(enabled = false) to "NotActionable",
            node.copy(password = true) to "NotActionable",
            node.copy(clickable = false) to "NotActionable",
        ).forEach { (candidate, expected) ->
            assertEquals(expected, TargetResolver.resolve("Куда", ScreenSnapshot("example.app", true,
                listOf(candidate), viewport = ScreenViewport(0, 0, 200, 200))).javaClass.simpleName)
        }
    }

    @Test
    fun `VIN label normalizes to its clickable ancestor before ranking`() {
        val input = element(0, "", testRect(10, 10, 400, 200)).copy(editable = true)
        val label = element(1, "VIN", testRect(20, 20, 100, 60))
            .copy(clickable = false, clickableAncestorIndex = 0)
        val result = TargetResolver.resolve("VIN", ScreenSnapshot("example.app", true, listOf(input, label)))
        assertSame(input, (result as TargetResolver.Result.Found).candidate.element)
    }

    @Test
    fun `two separated labels sharing a clickable ancestor are one control`() {
        val button = element(0, "", testRect(10, 10, 400, 200))
        val label = element(1, "Выбрать", testRect(20, 20, 100, 60))
            .copy(clickable = false, clickableAncestorIndex = 0)
        val other = label.copy(index = 2, bounds = testRect(200, 20, 300, 60))
        val result = TargetResolver.resolve("Выбрать", ScreenSnapshot("example.app", true,
            listOf(button, label, other)))
        assertSame(button, (result as TargetResolver.Result.Found).candidate.element)
    }

    @Test
    fun `equal labels on genuinely distinct buttons remain ambiguous`() {
        val first = element(0, "Выбрать", testRect(10, 10, 200, 100))
        val second = first.copy(index = 1, bounds = testRect(10, 150, 200, 250))
        assertTrue(TargetResolver.resolve("Выбрать", ScreenSnapshot("example.app", true,
            listOf(first, second))) is TargetResolver.Result.Ambiguous)
    }

    @Test
    fun `cyrillic labels do not all normalize to the same empty string`() {
        val snapshot = ScreenSnapshot(
            packageName = "ru.yandex.taxi",
            settled = true,
            elements = listOf(
                element(0, "Еда", testRect(10, 10, 210, 110)),
                element(1, "Такси", testRect(220, 10, 420, 110)),
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
                element(0, "Лавка", testRect(10, 10, 210, 110)),
                element(1, "Еда", testRect(220, 10, 420, 110)),
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
        val localBounds = testRect(31, 47, 731, 247)
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

    @Test
    fun `static field label resolves to one nearby editable control`() {
        val field = ScreenElement(
            index = 1,
            resourceId = "",
            text = "",
            className = "EditText",
            bounds = testRect(45, 433, 742, 613),
            editable = true,
            clickable = true,
        )
        val region = field.copy(index = 2, bounds = testRect(748, 433, 1035, 613))
        val snapshot = ScreenSnapshot("example.app", true, listOf(
            element(0, "Госномер", testRect(45, 265, 413, 365)).copy(clickable = false),
            field,
            region,
        ))

        val result = TargetResolver.resolve("Госномер", snapshot)

        assertTrue(result is TargetResolver.Result.Found)
        assertSame(field, (result as TargetResolver.Result.Found).candidate.element)
    }

    @Test
    fun `static label never guesses between two nearby editable controls`() {
        val label = element(0, "Контакт", testRect(10, 10, 410, 90)).copy(clickable = false)
        val first = ScreenElement(1, "", "", "EditText", testRect(10, 120, 200, 200), true, true)
        val second = first.copy(index = 2, bounds = testRect(220, 120, 410, 200))
        val snapshot = ScreenSnapshot("example.app", true, listOf(label, first, second))

        val result = TargetResolver.resolve("Контакт", snapshot)

        assertFalse(result is TargetResolver.Result.Found)
    }

    @Test
    fun `static label inside an editable control resolves to that control`() {
        val input = ScreenElement(
            0, "", "", "EditText", testRect(45, 433, 1035, 726), true, true,
        )
        val label = element(1, "Госномер", testRect(45, 625, 1035, 726))
            .copy(clickable = false)

        val result = TargetResolver.resolve(
            "Госномер",
            ScreenSnapshot("example.app", true, listOf(input, label)),
        )

        assertTrue(result is TargetResolver.Result.Found)
        assertSame(input, (result as TargetResolver.Result.Found).candidate.element)
    }

    @Test
    fun `intervening label prevents mapping to a different field below`() {
        val plate = element(0, "Госномер", testRect(10, 10, 410, 80)).copy(clickable = false)
        val vin = element(1, "VIN", testRect(10, 100, 410, 160)).copy(clickable = false)
        val input = ScreenElement(
            2, "", "", "EditText", testRect(10, 190, 410, 280), true, true,
        )

        val result = TargetResolver.resolve(
            "Госномер",
            ScreenSnapshot("example.app", true, listOf(plate, vin, input)),
        )

        assertTrue(result is TargetResolver.Result.NotActionable)
    }

    @Test
    fun `mention inside VIN explanation is not a госномер target`() {
        val vin = element(0, "VIN-номер", testRect(45, 265, 874, 365))
            .copy(clickable = false)
        val input = ScreenElement(
            1, "", "", "EditText", testRect(45, 433, 1035, 726), true, true,
        )
        val explanation = element(
            2,
            "Госномер и VIN не видны в объявлении, но нужны для проверки",
            testRect(45, 625, 1035, 726),
        ).copy(clickable = false)

        val result = TargetResolver.resolve(
            "Госномер",
            ScreenSnapshot("example.app", true, listOf(vin, input, explanation)),
        )

        assertTrue(result is TargetResolver.Result.NotActionable)
        assertTrue(
            TargetResolver.resolve(
                explanation.text,
                ScreenSnapshot("example.app", true, listOf(vin, input, explanation)),
            ) is TargetResolver.Result.NotActionable
        )
    }

    @Test
    fun `short sentence inside a different form field is not a label`() {
        val input = ScreenElement(
            0, "", "", "EditText", testRect(45, 433, 1035, 726), true, true,
        )
        val explanation = element(1, "Госномер не указан", testRect(45, 625, 1035, 726))
            .copy(clickable = false)

        val result = TargetResolver.resolve(
            explanation.text,
            ScreenSnapshot("example.app", true, listOf(input, explanation)),
        )

        assertTrue(result is TargetResolver.Result.NotActionable)
    }

    @Test
    fun `distant static label without an editable neighbour is not highlighted`() {
        val label = element(0, "Госномер", testRect(10, 10, 410, 80)).copy(clickable = false)
        val input = ScreenElement(
            1, "", "", "EditText", testRect(10, 500, 410, 580), true, true,
        )

        val result = TargetResolver.resolve(
            "Госномер",
            ScreenSnapshot("example.app", true, listOf(label, input)),
        )

        assertTrue(result is TargetResolver.Result.NotActionable)
    }

    @Test
    fun `plain text row cannot borrow an unrelated search field above`() {
        val row = element(0, "Такси", testRect(10, 100, 410, 170)).copy(clickable = false)
        val search = ScreenElement(
            1, "search", "", "EditText", testRect(10, 10, 410, 80), true, true,
        )

        val result = TargetResolver.resolve(
            "Такси",
            ScreenSnapshot("example.app", true, listOf(row, search)),
        )

        assertTrue(result is TargetResolver.Result.NotActionable)
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

    private fun testRect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
