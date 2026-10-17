package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSanitizerTest {

    @Test
    fun `planner DTO contains semantic data and cannot represent coordinates`() {
        val planner = ScreenSanitizer.sanitize(
            snapshot(
                element(
                    index = 7,
                    text = "Продолжить",
                    contentDescription = "Кнопка продолжения",
                    className = "Button",
                    clickable = true,
                    selected = true,
                    checked = false,
                    scrollable = false,
                )
            )
        )

        assertEquals("ru.example.app", planner.packageName)
        assertTrue(planner.settled)
        assertEquals(1, planner.elements.size)
        with(planner.elements.single()) {
            assertEquals(7, index)
            assertEquals("Продолжить", label)
            assertEquals("Кнопка продолжения", description)
            assertEquals("Button", role)
            assertTrue(clickable)
            assertTrue(enabled)
            assertTrue(selected)
            assertFalse(checked)
            assertFalse(scrollable)
        }

        val fields = PlannerElement::class.java.declaredFields
        assertTrue(fields.none { it.type.name == "android.graphics.Rect" })
        assertTrue(fields.none { it.name.lowercase() in setOf("bounds", "rect", "x", "y", "left", "top", "right", "bottom") })
        val prompt = planner.toPromptText()
        assertFalse(prompt.contains("10, 20"))
        assertFalse(prompt.contains("110, 120"))
    }

    @Test
    fun `editable node exposes its hint and description but never current value`() {
        val planner = ScreenSanitizer.sanitize(
            snapshot(
                element(
                    text = "улица Примерная, 42",
                    contentDescription = "Адрес доставки",
                    hintText = "Введите адрес",
                    className = "EditText",
                    editable = true,
                    clickable = true,
                )
            )
        )

        val safe = planner.elements.single()
        assertEquals("Введите адрес", safe.label)
        assertEquals("Адрес доставки", safe.description)
        assertFalse(planner.toPromptText().contains("улица Примерная, 42"))
    }

    @Test
    fun `password hidden and empty values are omitted`() {
        val planner = ScreenSanitizer.sanitize(
            snapshot(
                element(index = 1, text = "secret-value", password = true),
                element(index = 2, text = "Скрытый текст", visibleToUser = false),
                element(index = 3, text = "", contentDescription = "", hintText = "", resourceId = "empty"),
                element(index = 4, text = "Обычный экран"),
            )
        )

        assertEquals(listOf(3, 4), planner.elements.map { it.index })
        val empty = planner.elements.first()
        assertNull(empty.label)
        assertNull(empty.description)
        val prompt = planner.toPromptText()
        assertFalse(prompt.contains("secret-value"))
        assertFalse(prompt.contains("Скрытый текст"))
        assertFalse(prompt.contains("label=\"\""))
        assertFalse(prompt.contains("description=\"\""))
    }

    @Test
    fun `personal patterns become stable markers and Russian labels stay readable`() {
        val planner = ScreenSanitizer.sanitize(
            snapshot(
                element(index = 1, text = "Email: ivan.petrov@example.com"),
                element(index = 2, text = "Телефон +7 (999) 123-45-67"),
                element(index = 3, text = "Код 482913"),
                element(index = 4, text = "Номер 1234567890123456"),
                element(index = 5, text = "История заказов"),
            )
        )

        val prompt = planner.toPromptText()
        assertTrue(prompt.contains("[EMAIL]"))
        assertTrue(prompt.contains("[PHONE]"))
        assertTrue(prompt.contains("[OTP]"))
        assertTrue(prompt.contains("[NUMBER]"))
        assertTrue(prompt.contains("История заказов"))
        listOf(
            "ivan.petrov@example.com",
            "+7 (999) 123-45-67",
            "482913",
            "1234567890123456",
        ).forEach { forbidden -> assertFalse(prompt.contains(forbidden)) }
    }

    private fun snapshot(vararg elements: ScreenElement) = ScreenSnapshot(
        packageName = "ru.example.app",
        settled = true,
        elements = elements.toList(),
    )

    private fun element(
        index: Int = 0,
        resourceId: String = "control",
        text: String = "",
        contentDescription: String = "",
        hintText: String = "",
        className: String = "TextView",
        editable: Boolean = false,
        clickable: Boolean = false,
        enabled: Boolean = true,
        selected: Boolean = false,
        checked: Boolean = false,
        scrollable: Boolean = false,
        password: Boolean = false,
        visibleToUser: Boolean = true,
    ) = ScreenElement(
        index = index,
        resourceId = resourceId,
        text = text,
        contentDescription = contentDescription,
        hintText = hintText,
        className = className,
        bounds = Rect(10, 20, 110, 120),
        editable = editable,
        clickable = clickable,
        enabled = enabled,
        selected = selected,
        checked = checked,
        scrollable = scrollable,
        password = password,
        visibleToUser = visibleToUser,
    )
}
