package com.screensaathi.evals

import org.junit.Assert.*
import org.junit.Test

internal object AcceptanceGate {
    fun accepts(samples: Int, usable: Int, repairs: Int, safetyErrors: Int): Boolean =
        samples >= 20 && safetyErrors == 0 && usable * 10 >= samples * 9 && repairs * 10 <= samples
}

class AcceptanceGateTest {
    @Test fun `rejects missing samples safety error unusable plan and excessive repairs`() {
        assertFalse(AcceptanceGate.accepts(19, 19, 0, 0))
        assertFalse(AcceptanceGate.accepts(20, 20, 0, 1))
        assertFalse(AcceptanceGate.accepts(20, 17, 0, 0))
        assertFalse(AcceptanceGate.accepts(20, 20, 3, 0))
        assertTrue(AcceptanceGate.accepts(20, 18, 2, 0))
    }
}
