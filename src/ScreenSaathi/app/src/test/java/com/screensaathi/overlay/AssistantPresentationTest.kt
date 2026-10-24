package com.screensaathi.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPresentationTest {
    @Test
    fun `collapsed circle has a circle sized window and expanded card has a card sized window`() {
        val idle = AssistantPresentation().accept(OverlayCommand(PillState.IDLE))
        val guiding = idle.accept(
            OverlayCommand(PillState.GUIDING, expanded = true, instruction = "Нажмите кнопку")
        )

        assertFalse(idle.cardVisible)
        assertEquals(96, idle.windowWidthDp)
        assertTrue(guiding.cardVisible)
        assertEquals(346, guiding.windowWidthDp)
    }

    @Test
    fun `manual collapse retains the newest instruction for reopening`() {
        val visible = AssistantPresentation().accept(
            OverlayCommand(PillState.GUIDING, expanded = true, instruction = "Первый шаг")
        )
        val collapsed = visible.toggle().accept(
            OverlayCommand(PillState.GUIDING, expanded = true, instruction = "Второй шаг")
        )

        assertFalse(collapsed.cardVisible)
        assertEquals(96, collapsed.windowWidthDp)
        assertEquals("Второй шаг", collapsed.lastInstruction)
        assertEquals("Второй шаг", collapsed.toggle().lastInstruction)
        assertTrue(collapsed.toggle().cardVisible)
    }

    @Test
    fun `active listening opens the card even after manual collapse`() {
        val collapsed = AssistantPresentation().accept(
            OverlayCommand(PillState.GUIDING, expanded = true, instruction = "Шаг")
        ).toggle()

        val listening = collapsed.accept(OverlayCommand(PillState.LISTENING, expanded = true))

        assertTrue(listening.cardVisible)
        assertEquals(346, listening.windowWidthDp)
        assertEquals("Шаг", listening.lastInstruction)
    }

    @Test
    fun `explicit command collapse resets a manually opened card`() {
        val opened = AssistantPresentation().accept(
            OverlayCommand(PillState.GUIDING, expanded = true, instruction = "Шаг")
        ).toggle().toggle()

        val collapsed = opened.accept(OverlayCommand(PillState.IDLE, expanded = false))

        assertFalse(collapsed.cardVisible)
        assertFalse(collapsed.accept(OverlayCommand(PillState.IDLE, expanded = false)).cardVisible)
    }

    @Test
    fun `removal confirmation stays visible through command updates`() {
        val pending = AssistantPresentation().requestRemoval()

        val updated = pending.accept(OverlayCommand(PillState.GUIDING, expanded = false))

        assertTrue(updated.removalPending)
        assertTrue(updated.cardVisible)
    }

    @Test
    fun `long press opens removal confirmation and cancel restores the circle and instruction`() {
        val circle = AssistantPresentation().accept(
            OverlayCommand(PillState.GUIDING, expanded = true, instruction = "Нажмите кнопку")
        ).toggle()

        val confirm = circle.requestRemoval()

        assertTrue(confirm.removalPending)
        assertTrue(confirm.cardVisible)
        assertEquals(346, confirm.windowWidthDp)

        val cancelled = confirm.cancelRemoval()
        assertFalse(cancelled.removalPending)
        assertFalse(cancelled.cardVisible)
        assertEquals("Нажмите кнопку", cancelled.lastInstruction)
    }

    @Test
    fun `confirmation is required before removal can stop the overlay`() {
        val current = AssistantPresentation()

        assertFalse(current.canConfirmRemoval)
        assertTrue(current.requestRemoval().canConfirmRemoval)
        assertFalse(current.requestRemoval().cancelRemoval().canConfirmRemoval)
    }
}
