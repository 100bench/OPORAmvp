package com.screensaathi.screen

/** Exact bounds of the window supplying a live accessibility root. */
data class ScreenViewport(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        /** Compatibility for old synthetic constructors and fixtures only; never live capture. */
        val LEGACY_UNBOUNDED = ScreenViewport(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        val EMPTY = ScreenViewport(0, 0, 0, 0)
    }
}
