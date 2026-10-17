package com.screensaathi.sarvam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SarvamSttTest {

    @Test
    fun `confidence is parsed only when the provider supplies it`() {
        val supplied = SarvamStt.parseResponse(
            """{"text":"Заказать такси","language":"ru","confidence":0.73}"""
        )
        val absent = SarvamStt.parseResponse(
            """{"text":"Заказать такси","language":"ru"}"""
        )

        assertEquals(0.73, supplied?.confidence ?: 0.0, 0.0001)
        assertNull(absent?.confidence)
    }

    @Test
    fun `low supplied confidence and deterministic empty or too short text are ambiguous`() {
        assertTrue(SarvamStt.isAmbiguous(SarvamStt.Result("Заказать такси", "ru", 0.59)))
        assertTrue(SarvamStt.isAmbiguous(SarvamStt.Result("", "ru", null)))
        assertTrue(SarvamStt.isAmbiguous(SarvamStt.Result("да", "ru", null)))
        assertFalse(SarvamStt.isAmbiguous(SarvamStt.Result("Заказать такси", "ru", null)))
    }
}
