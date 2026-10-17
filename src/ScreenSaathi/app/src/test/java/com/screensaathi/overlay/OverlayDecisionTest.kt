package com.screensaathi.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayDecisionTest {

    @Test
    fun `scenario decision presets use the approved Russian labels`() {
        assertEquals(DecisionButtons("Верно", "Повторить"), DecisionButtons.VERIFY_OR_REPEAT)
        assertEquals(DecisionButtons("Начать", "Отмена"), DecisionButtons.START_OR_CANCEL)
        assertEquals(DecisionButtons("Повторить", "Остановить"), DecisionButtons.RETRY_OR_STOP)
        assertEquals(DecisionButtons("Продолжить", "Остановить"), DecisionButtons.CONTINUE_OR_STOP)
    }

    @Test
    fun `ordinary overlay command has no decision controls`() {
        val command = OverlayCommand(PillState.IDLE)

        assertNull(command.decisionButtons)
        assertFalse(
            OverlayCommand::class.java.declaredFields.any { it.name == "confirmationRequired" }
        )
    }

    @Test
    fun `decision controls stay independent from concrete app choices`() {
        val buttons = DecisionButtons.START_OR_CANCEL
        val command = OverlayCommand(
            pillState = PillState.GUIDING,
            choices = listOf("Яндекс Go"),
            decisionButtons = buttons,
        )

        assertEquals(listOf("Яндекс Go"), command.choices)
        assertEquals(buttons, command.decisionButtons)
        assertEquals(setOf(DecisionChoice.PRIMARY, DecisionChoice.SECONDARY), DecisionChoice.entries.toSet())
    }
}
