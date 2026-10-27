package com.screensaathi.evals

import com.screensaathi.debug.TextProbeRequest
import org.junit.Assert.*
import org.junit.Test

class TextProbeRequestTest {
    @Test fun `debug text probe rejects unrelated missing empty and oversized input`() {
        assertNull(TextProbeRequest.acceptedQuery("other", "Секрет"))
        assertNull(TextProbeRequest.acceptedQuery(TextProbeRequest.ACTION, null))
        assertNull(TextProbeRequest.acceptedQuery(TextProbeRequest.ACTION, "  "))
        assertNull(TextProbeRequest.acceptedQuery(TextProbeRequest.ACTION, "я".repeat(2001)))
        assertEquals("Что находится на экране?", TextProbeRequest.acceptedQuery(TextProbeRequest.ACTION, "Что находится на экране?"))
    }
}
