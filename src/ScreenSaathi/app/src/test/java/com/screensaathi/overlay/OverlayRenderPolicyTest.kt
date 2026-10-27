package com.screensaathi.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayRenderPolicyTest {
    @Test
    fun `identical layout skips window update but geometry inset expansion highlight and flags update`() {
        val policy = OverlayRenderPolicy()
        val key = OverlayLayoutKey(10, 20, 320, -2, 100, 8, 0, 0, false, null)
        var updates = 0
        fun apply(next: OverlayLayoutKey) = policy.update(next) { updates++ }
        apply(key)
        apply(key.copy())
        assertEquals(1, updates)
        listOf(key.copy(x = 11), key.copy(y = 21), key.copy(width = 96),
            key.copy(measuredHeight = 200), key.copy(imeTop = 400),
            key.copy(expanded = true), key.copy(highlight = HighlightBounds(1, 2, 3, 4)),
            key.copy(flags = 0), key.copy(softInputMode = 16)).forEach { next ->
            apply(next)
            apply(next.copy())
        }
        assertEquals(10, updates)
    }

    @Test
    fun `failed window update can retry and every changed drag frame reaches window`() {
        val policy = OverlayRenderPolicy()
        val key = OverlayLayoutKey(0, 0, 96, -2, 96, 8, 0, 0, false, null)
        runCatching { policy.update(key) { error("window unavailable") } }
        var updates = 0
        policy.update(key) { updates++ }
        (1..10).forEach { x -> policy.update(key.copy(x = x)) { updates++ } }
        assertEquals(11, updates)
    }
}
