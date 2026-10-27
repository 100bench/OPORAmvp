package com.screensaathi.screen

enum class ScreenElementIneligibility {
    HIDDEN, DISABLED, PASSWORD, EMPTY_BOUNDS, OUTSIDE_VIEWPORT, MOSTLY_CLIPPED
}

sealed interface ScreenElementEligibility {
    data object Eligible : ScreenElementEligibility
    data class Ineligible(val reason: ScreenElementIneligibility) : ScreenElementEligibility
}

/** Action targets require enabled state in addition to safe visible geometry. */
fun ScreenElement.eligibility(viewport: ScreenViewport): ScreenElementEligibility {
    if (!visibleToUser) return ineligible(ScreenElementIneligibility.HIDDEN)
    if (!enabled) return ineligible(ScreenElementIneligibility.DISABLED)
    return contextEligibility(viewport)
}

/** Informational context retains disabled captions, but uses the same geometry boundary. */
internal fun ScreenElement.contextEligibility(viewport: ScreenViewport): ScreenElementEligibility {
    if (!visibleToUser) return ineligible(ScreenElementIneligibility.HIDDEN)
    if (password) return ineligible(ScreenElementIneligibility.PASSWORD)
    val left = bounds.left.toLong()
    val top = bounds.top.toLong()
    val right = bounds.right.toLong()
    val bottom = bounds.bottom.toLong()
    val width = right - left
    val height = bottom - top
    if (width <= 0 || height <= 0) return ineligible(ScreenElementIneligibility.EMPTY_BOUNDS)
    val visibleWidth = minOf(right, viewport.right.toLong()) - maxOf(left, viewport.left.toLong())
    val visibleHeight = minOf(bottom, viewport.bottom.toLong()) - maxOf(top, viewport.top.toLong())
    if (visibleWidth <= 0 || visibleHeight <= 0) return ineligible(ScreenElementIneligibility.OUTSIDE_VIEWPORT)
    // Doubled center avoids rounding a negative or half-pixel center onto an edge.
    if (left + right < viewport.left.toLong() * 2 || left + right >= viewport.right.toLong() * 2 ||
        top + bottom < viewport.top.toLong() * 2 || top + bottom >= viewport.bottom.toLong() * 2
    ) return ineligible(ScreenElementIneligibility.MOSTLY_CLIPPED)

    // ceil(width * height / 2), split before multiplication: even full Int-span
    // rectangles fit in Long at half area. Division also avoids visible-area overflow.
    val halfArea = (width / 2) * height + (width % 2) * (height / 2 + height % 2)
    val requiredVisibleWidth = halfArea / visibleHeight + if (halfArea % visibleHeight == 0L) 0 else 1
    if (visibleWidth < requiredVisibleWidth) return ineligible(ScreenElementIneligibility.MOSTLY_CLIPPED)
    return ScreenElementEligibility.Eligible
}

private fun ineligible(reason: ScreenElementIneligibility) = ScreenElementEligibility.Ineligible(reason)
