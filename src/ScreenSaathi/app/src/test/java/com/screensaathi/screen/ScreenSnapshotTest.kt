package com.screensaathi.screen

import android.graphics.Rect
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScreenSnapshotTest {
    @Test
    fun `opening a dialog changes screen fingerprint even when labels stay identical`() {
        val button = ScreenElement(
            index = 0,
            resourceId = "continue",
            text = "Продолжить",
            className = "Button",
            bounds = Rect(10, 20, 200, 80),
            editable = false,
            clickable = true,
        )
        val screen = ScreenSnapshot("example.app", true, listOf(button))

        assertNotEquals(screen.signature(), screen.copy(activeDialog = true).signature())
    }
}
