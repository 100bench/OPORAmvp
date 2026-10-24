package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalDismissResolverTest {
    @Test
    fun `unique labelled close button inside dialog is selected`() {
        val close = node(0, "close_button", "Закрыть", testRect(900, 400, 1040, 540), true, true)
        val result = ModalDismissResolver.resolve(dialog(close))

        assertTrue(result is ModalDismissResolver.Result.CloseTarget)
        assertSame(close, (result as ModalDismissResolver.Result.CloseTarget).element)
    }

    @Test
    fun `unique glyph cross inside dialog is selected`() {
        val close = node(0, "", "×", testRect(900, 400, 1040, 540), true, true)

        assertTrue(ModalDismissResolver.resolve(dialog(close)) is ModalDismissResolver.Result.CloseTarget)
    }

    @Test
    fun `resource id alone can identify a visible close button`() {
        val close = node(0, "example.app:id/btn_close", "", testRect(900, 400, 1040, 540), true, true)

        val result = ModalDismissResolver.resolve(dialog(close))

        assertTrue(result is ModalDismissResolver.Result.CloseTarget)
        assertSame(close, (result as ModalDismissResolver.Result.CloseTarget).element)
    }

    @Test
    fun `disabled close button cannot be highlighted`() {
        val close = node(0, "close", "Закрыть", testRect(900, 400, 1040, 540), true, true)
            .copy(enabled = false)

        assertTrue(ModalDismissResolver.resolve(dialog(close)) is ModalDismissResolver.Result.ManualDismiss)
    }

    @Test
    fun `nested static cross maps to its one clickable parent`() {
        val button = node(0, "", "", testRect(900, 400, 1040, 540), true, true)
        val cross = node(1, "", "×", testRect(950, 440, 990, 480), false, true)
            .copy(clickableAncestorIndex = 0)

        val result = ModalDismissResolver.resolve(dialog(button, cross))

        assertTrue(result is ModalDismissResolver.Result.CloseTarget)
        assertSame(button, (result as ModalDismissResolver.Result.CloseTarget).element)
    }

    @Test
    fun `dialog without a close target asks for manual dismissal`() {
        val body = node(0, "", "Приз для вас", testRect(50, 600, 900, 800), false, true)

        assertTrue(ModalDismissResolver.resolve(dialog(body)) is ModalDismissResolver.Result.ManualDismiss)
    }

    @Test
    fun `overlapping sibling must not be mistaken for a cross parent`() {
        val unrelatedButton = node(0, "", "", testRect(900, 400, 1040, 540), true, true)
        val siblingCross = node(1, "", "×", testRect(950, 440, 990, 480), false, true)

        assertTrue(
            ModalDismissResolver.resolve(dialog(unrelatedButton, siblingCross))
                is ModalDismissResolver.Result.ManualDismiss
        )
    }

    @Test
    fun `two close controls are ambiguous`() {
        val a = node(0, "close", "×", testRect(20, 400, 120, 500), true, true)
        val b = node(1, "dismiss", "Закрыть", testRect(900, 400, 1040, 540), true, true)

        assertTrue(ModalDismissResolver.resolve(dialog(a, b)) is ModalDismissResolver.Result.ManualDismiss)
    }

    @Test
    fun `close control outside dialog is ignored`() {
        val close = node(0, "close", "Закрыть", testRect(900, 400, 1040, 540), true, false)

        assertTrue(ModalDismissResolver.resolve(dialog(close)) is ModalDismissResolver.Result.ManualDismiss)
    }

    @Test
    fun `inert modal container on an ordinary screen is not a popup`() {
        val container = node(0, "top_modal_views_container", "", testRect(0, 0, 1080, 2280), false, false)

        assertTrue(
            ModalDismissResolver.resolve(ScreenSnapshot("example.app", true, listOf(container)))
                is ModalDismissResolver.Result.NotModal
        )
    }

    private fun dialog(vararg elements: ScreenElement) =
        ScreenSnapshot("example.app", true, elements.toList(), activeDialog = true)

    private fun node(
        index: Int,
        resourceId: String,
        text: String,
        bounds: Rect,
        clickable: Boolean,
        inDialog: Boolean,
    ) = ScreenElement(index, resourceId, text, "View", bounds, editable = false,
        clickable = clickable, inDialog = inDialog)

    private fun testRect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}
