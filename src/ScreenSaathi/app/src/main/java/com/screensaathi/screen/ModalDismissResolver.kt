package com.screensaathi.screen

/** Finds a dismiss control from visible dialog descendants, without guessing by position. */
object ModalDismissResolver {
    sealed interface Result {
        data object NotModal : Result
        data class CloseTarget(val element: ScreenElement) : Result
        data object ManualDismiss : Result
    }

    private val closeLabels = setOf("×", "✕", "✖", "закрыть", "закрыть окно", "close", "dismiss")
    private val closeIdTokens = setOf("close", "dismiss")

    fun resolve(snapshot: ScreenSnapshot): Result {
        if (!snapshot.activeDialog) return Result.NotModal
        val descendants = snapshot.elements.filter {
            it.inDialog && it.eligibility(snapshot.viewportOrLegacy) == ScreenElementEligibility.Eligible
        }
        val buttons = descendants.filter { it.clickable && !it.editable }
        val candidates = linkedSetOf<ScreenElement>()

        buttons.filterTo(candidates, ::hasCloseMeaning)
        descendants.asSequence()
            .filter { !it.clickable && hasCloseMeaning(it) }
            .forEach { label ->
                val ancestor = buttons.singleOrNull { button ->
                    button.index == label.clickableAncestorIndex &&
                        contains(button, label) && localControl(button, label)
                }
                if (ancestor != null) {
                    candidates.add(ancestor)
                } else {
                    // Geometry alone cannot establish ancestry; sibling controls
                    // can overlap in WebViews and must not receive the ring.
                    return Result.ManualDismiss
                }
            }

        return if (candidates.size == 1) Result.CloseTarget(candidates.single()) else Result.ManualDismiss
    }

    private fun hasCloseMeaning(element: ScreenElement): Boolean =
        sequenceOf(element.text, element.contentDescription, element.hintText)
            .any { it.trim().lowercase() in closeLabels } ||
            element.resourceId.substringAfterLast('/').lowercase()
                .split('_', '-', '.')
                .any { it in closeIdTokens }

    private fun contains(parent: ScreenElement, child: ScreenElement): Boolean =
        parent.index != child.index &&
            parent.bounds.left <= child.bounds.left && parent.bounds.top <= child.bounds.top &&
            parent.bounds.right >= child.bounds.right && parent.bounds.bottom >= child.bounds.bottom

    private fun localControl(parent: ScreenElement, child: ScreenElement): Boolean {
        val parentArea = area(parent)
        val childArea = area(child)
        return childArea > 0 && parentArea <= childArea * 16
    }

    private fun area(element: ScreenElement): Long =
        (element.bounds.right - element.bounds.left).coerceAtLeast(0).toLong() *
            (element.bounds.bottom - element.bounds.top).coerceAtLeast(0).toLong()
}
