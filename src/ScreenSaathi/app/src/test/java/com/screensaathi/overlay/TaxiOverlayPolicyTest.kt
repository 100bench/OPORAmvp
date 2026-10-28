package com.screensaathi.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaxiOverlayPolicyTest {

    @Test
    fun `normal taxi overlay keeps only voice instruction and stop surfaces`() {
        val visibility = TaxiOverlayPolicy.visibility(removalPending = false)

        assertTrue(visibility.instruction)
        assertTrue(visibility.voice)
        assertTrue(visibility.stop)
        assertFalse(visibility.textInput)
        assertFalse(visibility.language)
        assertFalse(visibility.next)
        assertFalse(visibility.choices)
        assertFalse(visibility.circleSelection)
        assertFalse(visibility.removalPrompt)
    }

    @Test
    fun `removal confirmation temporarily replaces taxi guidance controls`() {
        val visibility = TaxiOverlayPolicy.visibility(removalPending = true)

        assertTrue(visibility.removalPrompt)
        assertFalse(visibility.instruction)
        assertFalse(visibility.voice)
        assertFalse(visibility.stop)
    }

    @Test
    fun `failed taxi step is recoverable from the visible microphone`() {
        val instruction = TaxiOverlayPolicy.failureInstruction("Сервис AI временно не ответил.")

        assertTrue(instruction.contains("нажмите микрофон", ignoreCase = true))
        assertFalse(TaxiOverlayPolicy.offerResume)
        assertFalse(TaxiOverlayPolicy.speakVoiceErrors)
    }
}
