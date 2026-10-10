package com.screensaathi.opora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OporaSettingsTest {

    @Test
    fun `normalizes an ordinary phone number`() {
        assertEquals("+79991234567", OporaSettings.normalizedPhone("+7 (999) 123-45-67"))
    }

    @Test
    fun `rejects ussd and emergency numbers`() {
        assertNull(OporaSettings.normalizedPhone("*100#"))
        assertNull(OporaSettings.normalizedPhone("112"))
    }

    @Test
    fun `rejects implausible phone lengths`() {
        assertNull(OporaSettings.normalizedPhone("12345"))
        assertNull(OporaSettings.normalizedPhone("1234567890123456"))
    }
}
