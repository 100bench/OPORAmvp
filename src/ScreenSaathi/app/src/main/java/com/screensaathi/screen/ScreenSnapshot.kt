package com.screensaathi.screen

import android.graphics.Rect

/**
 * In-memory form of the accessibility snapshot (contracts/accessibility.schema.json).
 * A flat, own-package-filtered list of on-screen elements plus a settled flag.
 */

data class ScreenElement(
    val index: Int,
    val resourceId: String,
    val text: String,
    val className: String,
    val bounds: Rect,
    val editable: Boolean,
    val clickable: Boolean,
    val contentDescription: String = "",
    val hintText: String = "",
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    val inputType: Int = 0,
    val visibleToUser: Boolean = true,
    /** Local ancestry marker; never a substitute for visible dialog evidence. */
    val inDialog: Boolean = false,
    /** Nearest emitted clickable ancestor, retained only on the device. */
    val clickableAncestorIndex: Int? = null,
)

data class ScreenSnapshot(
    val packageName: String,
    val settled: Boolean,
    val elements: List<ScreenElement>,
    /** True only for a visible dialog node in the current Accessibility tree. */
    val activeDialog: Boolean = false,
    /** Null only for legacy synthetic constructors/captures; live snapshots always have bounds. */
    val viewport: ScreenViewport? = null,
) {
    val viewportOrLegacy: ScreenViewport get() = viewport ?: ScreenViewport.LEGACY_UNBOUNDED

    /** Live bounds for a step's resource_id, or null if not currently on screen. */
    fun boundsForResourceId(resourceId: String): Rect? {
        if (resourceId.isEmpty()) return null
        val matches = elements.filter { it.resourceId == resourceId }
        if (matches.isEmpty()) return null
        // A label and the field behind it can share an id; point at the one the
        // user can actually act on.
        return (matches.firstOrNull { it.editable || it.clickable } ?: matches.first()).bounds
    }

    /**
     * Bounds for a step that targets visible text rather than a view id.
     *
     * Third-party apps obfuscate their view ids, so the only stable handle on
     * an Uber or Ola field is the label the user can see. Matching is
     * case-insensitive and substring-based because these labels carry
     * punctuation and trailing hints ("Where to?", "Enter drop location").
     *
     * Prefers an interactive element over a plain label: tapping "Where to?"
     * means tapping the field, and the same words often appear on both a
     * TextView and the EditText behind it.
     */
    fun boundsForText(candidates: List<String>): Rect? {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null

        fun pick(matches: List<ScreenElement>): Rect? =
            (matches.firstOrNull { it.editable || it.clickable } ?: matches.firstOrNull())?.bounds

        // Exact label first: "Pay" must not resolve to "Payment history" while
        // a button literally labelled "Pay" is on screen.
        val exact = elements.filter { e ->
            e.text.isNotEmpty() && cleaned.any { e.text.trim().equals(it, ignoreCase = true) }
        }
        if (exact.isNotEmpty()) return pick(exact)

        // Then containment, in both directions — the model may say "Book
        // Appointment" for a button reading "Book", or vice versa. Very short
        // candidates are word-bounded so "OK" cannot match "BOOKING".
        val loose = elements.filter { e ->
            val et = e.text.trim()
            et.isNotEmpty() && cleaned.any { c ->
                if (c.length <= 3) {
                    Regex("\b${Regex.escape(c)}\b", RegexOption.IGNORE_CASE).containsMatchIn(et)
                } else {
                    et.contains(c, ignoreCase = true) || c.contains(et, ignoreCase = true)
                }
            }
        }
        return if (loose.isNotEmpty()) pick(loose) else null
    }

    fun elementForResourceId(resourceId: String): ScreenElement? =
        elements.firstOrNull { it.resourceId == resourceId }

    /** Provider callers must cross the sanitizer boundary before serialization. */
    @Deprecated(
        message = "Use ScreenSanitizer.sanitize(snapshot).toPromptText()",
        replaceWith = ReplaceWith("ScreenSanitizer.sanitize(this).toPromptText()"),
    )
    fun toPromptText(): String = ScreenSanitizer.sanitize(this).toPromptText()

    companion object {
        val EMPTY = ScreenSnapshot("", settled = false, elements = emptyList(), viewport = ScreenViewport.EMPTY)
    }

    /**
     * A cheap identity for "which screen is this". Package plus the first few
     * stable labels: enough to notice the user moved to a different page,
     * without churning on a countdown, a price tick, or a carousel — those
     * would otherwise read as a new screen several times a second.
     */
    fun signature(): String {
        if (packageName.isEmpty()) return ""
        val labels = elements.asSequence()
            .map { it.text.trim() }
            .filter { it.length in 2..40 }
            .distinct()
            .take(6)
            .sorted()
            .joinToString("|")
        // A window/IME resize can hide a target without changing any labels.
        // Include its safe viewport so the existing highlight invalidation
        // clears the ring before waiting for the next settled observation.
        return "$packageName#dialog=$activeDialog#viewport=$viewportOrLegacy#$labels"
    }
}
