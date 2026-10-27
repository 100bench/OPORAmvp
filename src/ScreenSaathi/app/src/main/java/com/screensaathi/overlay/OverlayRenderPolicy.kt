package com.screensaathi.overlay

data class OverlayLayoutKey(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val measuredHeight: Int,
    val flags: Int,
    val softInputMode: Int,
    val imeTop: Int,
    val expanded: Boolean,
    val highlight: HighlightBounds?,
)

class OverlayRenderPolicy {
    private var applied: OverlayLayoutKey? = null

    fun update(key: OverlayLayoutKey, apply: () -> Unit) {
        if (key == applied) return
        apply()
        applied = key
    }
}
