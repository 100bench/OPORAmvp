package com.screensaathi.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantPlacementTest {

    @Test
    fun `compact bubble always docks to the nearest edge`() {
        assertEquals(24, AssistantPlacement.dockX(100, 180, 0, 1080, 24))
        assertEquals(876, AssistantPlacement.dockX(760, 180, 0, 1080, 24))
    }

    @Test
    fun `centre line chooses the right edge consistently`() {
        assertEquals(876, AssistantPlacement.dockX(450, 180, 0, 1080, 24))
    }
}
