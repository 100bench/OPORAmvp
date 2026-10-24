package com.screensaathi.overlay

import android.graphics.Rect

/**
 * Where the floating assistant is allowed to sit.
 *
 * Deliberately Android-free arithmetic so the clamping and keyboard-avoidance
 * rules can be reasoned about (and unit tested) without a device. The caller
 * supplies the real numbers it got from WindowInsets; nothing here guesses at
 * a screen size or a keyboard height.
 */
object AssistantPlacement {

    sealed interface Avoidance {
        data class Move(val x: Int, val y: Int) : Avoidance
        data object Collapse : Avoidance
    }

    /** Distance from an edge, in px, within which a release docks to it. */
    fun snapMarginPx(density: Float): Int = (12f * density).toInt()

    /**
     * Clamp a proposed top-left so the whole window stays inside [safe].
     *
     * [safe] is the usable area *after* status bar, navigation bar, cutout and
     * (when open) the IME have been subtracted — so the assistant can never be
     * parked under system UI where it cannot be grabbed.
     */
    fun clamp(x: Int, y: Int, w: Int, h: Int, safe: Rect): Pair<Int, Int> {
        val maxX = (safe.right - w).coerceAtLeast(safe.left)
        val maxY = (safe.bottom - h).coerceAtLeast(safe.top)
        return x.coerceIn(safe.left, maxX) to y.coerceIn(safe.top, maxY)
    }

    /**
     * Horizontal resting place after a drag: nearest side, or centred when
     * released around the middle. Matches how floating assistants behave — a
     * bubble left mid-screen reads as dropped rather than placed.
     */
    fun snapX(x: Int, w: Int, safe: Rect, density: Float): Int {
        val margin = snapMarginPx(density)
        val centre = x + w / 2
        val third = safe.width() / 3
        return when {
            centre < safe.left + third -> safe.left + margin
            centre > safe.right - third -> safe.right - w - margin
            else -> safe.left + (safe.width() - w) / 2
        }
    }

    /**
     * Lift the assistant clear of the keyboard, and only then.
     *
     * Returns null when it already fits, so a user who has parked the pill high
     * up is not yanked around every time a field takes focus. When it does not
     * fit, the window is placed just above the IME rather than at some fixed
     * offset — the caller passes the measured inset, so nothing here assumes a
     * keyboard height.
     */
    fun avoidKeyboard(y: Int, h: Int, imeTopPx: Int, gapPx: Int): Int? {
        if (imeTopPx <= 0) return null
        val bottom = y + h
        if (bottom <= imeTopPx - gapPx) return null
        return (imeTopPx - gapPx - h).coerceAtLeast(0)
    }

    /** Choose a visible, untouching card position or ask the renderer to collapse. */
    fun avoidTarget(
        preferredX: Int,
        preferredY: Int,
        width: Int,
        height: Int,
        safe: Rect,
        target: Rect,
        gap: Int,
    ): Avoidance {
        if (width <= 0 || height <= 0 || width > safe.right - safe.left ||
            height > safe.bottom - safe.top
        ) return Avoidance.Collapse

        val preferred = clamp(preferredX, preferredY, width, height, safe)
        fun clear(x: Int, y: Int): Boolean =
            x + width <= target.left - gap || x >= target.right + gap ||
                y + height <= target.top - gap || y >= target.bottom + gap

        if (clear(preferred.first, preferred.second)) {
            return Avoidance.Move(preferred.first, preferred.second)
        }

        val candidates = listOf(
            preferredX to (target.top - gap - height),
            preferredX to (target.bottom + gap),
            (target.left - gap - width) to preferredY,
            (target.right + gap) to preferredY,
            safe.left to safe.top,
            (safe.right - width) to safe.top,
            safe.left to (safe.bottom - height),
            (safe.right - width) to (safe.bottom - height),
        ).map { (x, y) -> clamp(x, y, width, height, safe) }
            .distinct()
            .filter { (x, y) -> clear(x, y) }

        val nearest = candidates.minByOrNull { (x, y) ->
            kotlin.math.abs(x - preferred.first) + kotlin.math.abs(y - preferred.second)
        } ?: return Avoidance.Collapse
        return Avoidance.Move(nearest.first, nearest.second)
    }
}
