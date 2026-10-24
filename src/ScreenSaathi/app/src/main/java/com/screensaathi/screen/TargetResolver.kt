package com.screensaathi.screen

import android.graphics.Rect
import android.util.Log

/**
 * Turns a natural-language target ("Wi-Fi", "the search button") into one
 * concrete on-screen element, or into an honest refusal.
 *
 * Ranked rather than `contains`-matched. Plain containment is how "call"
 * matched "Recall Settings": every candidate scores, exact beats partial, and a
 * near-tie is reported as ambiguous instead of silently picking whichever node
 * the tree walk happened to reach first.
 *
 * Deliberately app-agnostic. There is no Uber/Settings/taxi branch here — the
 * only inputs are the live accessibility tree and the user's words.
 */
object TargetResolver {

    /** How a candidate matched, best first. Higher scores win. */
    private const val SCORE_EXACT_TEXT = 100
    private const val SCORE_EXACT_DESC = 95
    private const val SCORE_EXACT_ID = 90
    private const val SCORE_NORMALIZED = 80
    private const val SCORE_WORD_PREFIX = 60
    private const val SCORE_CONTAINS_DESC = 45
    private const val SCORE_CONTAINS_TEXT = 40
    private const val SCORE_ID_CONTAINS = 30

    /** Below this, a match is too weak to point at anything. */
    private const val MIN_SCORE = 30

    /**
     * A second candidate this close to the winner means we genuinely cannot
     * tell them apart, and guessing would put the ring on the wrong control.
     */
    private const val AMBIGUITY_MARGIN = 10

    data class Candidate(
        val element: ScreenElement,
        val score: Int,
        val why: String,
    ) {
        val bounds: Rect get() = element.bounds
    }

    sealed interface Result {
        data class Found(val candidate: Candidate) : Result
        data class Ambiguous(val candidates: List<Candidate>) : Result
        data class NotFound(val query: String) : Result
    }

    fun resolve(query: String, snapshot: ScreenSnapshot): Result {
        val q = normalize(query)
        if (q.isEmpty()) return Result.NotFound(query)

        val scored = snapshot.elements
            .mapNotNull { score(q, it) }
            .filter { it.score >= MIN_SCORE }
            // Prefer something the user can actually act on, then the tighter
            // of two equal matches — a label wrapping a button should lose to
            // the button.
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.element.clickable || it.element.editable }
                    .thenBy { it.element.bounds.width().toLong() * it.element.bounds.height() }
            )

        if (scored.isEmpty()) {
            Log.d(TAG, "TARGET_RESOLUTION pkg=${snapshot.packageName} result=NOT_FOUND " +
                "elements=${snapshot.elements.size}")
            return Result.NotFound(query)
        }

        var best = scored.first()
        val rivals = scored.drop(1).filter { best.score - it.score < AMBIGUITY_MARGIN }

        // Two different elements scoring the same is only ambiguous if they are
        // actually different things — a label and the button containing it are
        // the same target, and the sort above already picked the better one.
        val genuineRivals = rivals.filter { !overlaps(it.bounds, best.bounds) }
        if (genuineRivals.isNotEmpty()) {
            Log.d(TAG, "TARGET_RESOLUTION pkg=${snapshot.packageName} result=AMBIGUOUS " +
                "candidates=${genuineRivals.size + 1}")
            return Result.Ambiguous(listOf(best) + genuineRivals)
        }

        // A loose match in explanatory copy is not a target. An exact, short
        // static label can be mapped to an input only when the geometry is
        // unambiguous. Keep unrelated bare text rows targetable.
        val fields = snapshot.elements.filter { it.visibleToUser && it.enabled && it.editable && !it.password }
        if (!best.element.isActionable) {
            val containing = fields.filter { contains(it.bounds, best.bounds) }
            val below = fields.filter { field ->
                field.bounds.top >= best.bounds.bottom &&
                    field.bounds.top - best.bounds.bottom <=
                    maxOf(120, (best.bounds.bottom - best.bounds.top) * 2) &&
                    horizontalOverlap(field.bounds, best.bounds) >=
                    minOf(
                        field.bounds.right - field.bounds.left,
                        best.bounds.right - best.bounds.left,
                    ) * 0.3
            }
            if (containing.isNotEmpty() || below.isNotEmpty()) {
                val shortExactLabel = best.score >= SCORE_NORMALIZED &&
                    best.element.text.length <= MAX_FIELD_LABEL_LENGTH &&
                    best.element.text.trim().split(Regex("\\s+")).size <= MAX_FIELD_LABEL_WORDS
                if (!shortExactLabel) return Result.NotFound(query)
                if (containing.size > 1) return Result.NotFound(query)
                if (containing.size + below.size != 1) return Result.NotFound(query)
                val field = (containing + below).single()
                val interveningLabel = snapshot.elements.any { other ->
                    other !== best.element && other.visibleToUser && !other.isActionable &&
                        other.text.isNotBlank() &&
                        other.bounds.top >= best.bounds.bottom &&
                        other.bounds.bottom <= field.bounds.top &&
                        horizontalOverlap(other.bounds, field.bounds) > 0
                }
                if (interveningLabel) return Result.NotFound(query)
                best = Candidate(field, best.score, "label_for_editable")
            } else if (best.score >= SCORE_NORMALIZED && fields.any { field ->
                    field.bounds.top >= best.bounds.bottom &&
                        field.bounds.top - best.bounds.bottom <= MAX_FORM_LABEL_DISTANCE &&
                        horizontalOverlap(field.bounds, best.bounds) > 0
                }) {
                // Looks like a form label, but its control is too far away
                // for a reliable association.
                return Result.NotFound(query)
            } else if (best.score < SCORE_NORMALIZED && best.element.text.length > MAX_FIELD_LABEL_LENGTH) {
                return Result.NotFound(query)
            }
        }

        Log.d(TAG, "TARGET_RESOLUTION pkg=${snapshot.packageName} result=FOUND " +
            "class=${best.element.className} clickable=${best.element.clickable} " +
            "editable=${best.element.editable} why=${best.why} score=${best.score} " +
            "boundsInScreen=${best.bounds.toShortString()}")
        return Result.Found(best)
    }

    private fun score(q: String, e: ScreenElement): Candidate? {
        val text = normalize(e.text)
        val id = normalize(e.resourceId.substringAfterLast('/').replace('_', ' '))

        // ScreenElement folds text/contentDescription into one field, so an
        // exact hit on it covers both; the id is scored separately.
        return when {
            text.isNotEmpty() && text == q -> Candidate(e, SCORE_EXACT_TEXT, "exact_text")
            id.isNotEmpty() && e.isActionable && id == q -> Candidate(e, SCORE_EXACT_ID, "exact_id")
            text.isNotEmpty() && stripped(text) == stripped(q) ->
                Candidate(e, SCORE_NORMALIZED, "normalized")
            // "wifi" should find "Wi-Fi", and "search" should find "Search
            // settings" — but only on a word boundary, so "call" cannot reach
            // inside "Recall".
            text.isNotEmpty() && startsWithWord(text, q) ->
                Candidate(e, SCORE_WORD_PREFIX, "word_prefix")
            text.isNotEmpty() && containsWord(text, q) ->
                Candidate(e, SCORE_CONTAINS_TEXT, "contains_word")
            id.isNotEmpty() && e.isActionable && containsWord(id, q) ->
                Candidate(e, SCORE_ID_CONTAINS, "id_contains")
            else -> null
        }
    }

    private fun normalize(s: String) = s.trim().lowercase()

    /** Drops separators so "wi-fi", "wi fi" and "wifi" compare equal. */
    private fun stripped(s: String) = s.replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun startsWithWord(haystack: String, needle: String): Boolean =
        haystack == needle || haystack.startsWith("$needle ") ||
            stripped(haystack).startsWith(stripped(needle))

    private fun containsWord(haystack: String, needle: String): Boolean =
        Regex("\\b${Regex.escape(needle)}\\b").containsMatchIn(haystack)

    private val ScreenElement.isActionable: Boolean
        get() = clickable || editable

    private fun overlaps(a: Rect, b: Rect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun contains(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    private fun horizontalOverlap(a: Rect, b: Rect): Int =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)

    private const val TAG = "TargetResolver"
    private const val MAX_FIELD_LABEL_LENGTH = 40
    private const val MAX_FIELD_LABEL_WORDS = 2
    private const val MAX_FORM_LABEL_DISTANCE = 600
}
