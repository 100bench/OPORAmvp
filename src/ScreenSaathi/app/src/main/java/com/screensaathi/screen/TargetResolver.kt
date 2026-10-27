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
        data class NotVisible(val query: String) : Result
        data class Ambiguous(val candidates: List<Candidate>) : Result
        data class NotActionable(val query: String) : Result
        data class NotFound(val query: String) : Result
    }

    fun resolve(query: String, snapshot: ScreenSnapshot): Result {
        val q = normalize(query)
        if (q.isEmpty()) return Result.NotFound(query)

        return rank(query, snapshot, snapshot.elements
            .mapNotNull { score(q, it) }
            .filter { it.score >= MIN_SCORE })
    }

    /** Exact ID route shares normalization and eligibility with semantic matching. */
    fun resolveResourceId(resourceId: String, snapshot: ScreenSnapshot): Result = rank(
        resourceId, snapshot, snapshot.elements
            .filter { resourceId.isNotBlank() && it.resourceId == resourceId }
            .map { Candidate(it, SCORE_EXACT_ID, "exact_id") },
    )

    /** Planner labels must be copied exactly, unlike natural-language queries. */
    fun resolveLabel(label: String, snapshot: ScreenSnapshot): Result = rank(
        label, snapshot, snapshot.elements
            .filter { label.isNotBlank() && normalize(it.text) == normalize(label) }
            .map { Candidate(it, SCORE_EXACT_TEXT, "exact_text") },
    )

    private fun rank(query: String, snapshot: ScreenSnapshot, matches: List<Candidate>): Result {
        if (matches.isEmpty()) return Result.NotFound(query)
        val failures = mutableListOf<ScreenElementIneligibility>()
        val normalized = matches.mapNotNull { candidate ->
            val eligibility = candidate.element.eligibility(snapshot.viewportOrLegacy)
            if (eligibility is ScreenElementEligibility.Ineligible) {
                failures += eligibility.reason
                return@mapNotNull null
            }
            val target = actionableControl(candidate, snapshot) ?: return@mapNotNull null
            val targetEligibility = target.eligibility(snapshot.viewportOrLegacy)
            if (targetEligibility is ScreenElementEligibility.Ineligible) {
                failures += targetEligibility.reason
                return@mapNotNull null
            }
            candidate.copy(element = target,
                why = if (target.index == candidate.element.index) candidate.why else "label_for_control")
        }
        val scored = normalized
            .groupBy { it.element.index }.values.map { group -> group.maxBy { it.score } }
            // Rank only normalized usable controls; preserve semantic scores.
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.element.clickable || it.element.editable }
                    .thenBy { area(it.element.bounds) }
            )
            .fold(mutableListOf<Candidate>()) { distinct, candidate ->
                if (distinct.none { equivalentBounds(it.bounds, candidate.bounds) }) distinct += candidate
                distinct
            }

        if (scored.isEmpty()) {
            val result = if (failures.size == matches.size && failures.all { it in VISIBILITY_FAILURES }) {
                Result.NotVisible(query)
            } else Result.NotActionable(query)
            Log.d(TAG, "TARGET_RESOLUTION result=${result.javaClass.simpleName}")
            return result
        }

        val best = scored.first()
        val rivals = scored.drop(1).filter { best.score - it.score < AMBIGUITY_MARGIN }

        if (rivals.isNotEmpty()) {
            Log.d(TAG, "TARGET_RESOLUTION pkg=${snapshot.packageName} result=AMBIGUOUS " +
                "candidates=${rivals.size + 1}")
            return Result.Ambiguous(listOf(best) + rivals)
        }

        Log.d(TAG, "TARGET_RESOLUTION result=FOUND score=${best.score} why=${best.why}")
        return Result.Found(best)
    }

    private fun actionableControl(candidate: Candidate, snapshot: ScreenSnapshot): ScreenElement? {
        val element = candidate.element
        if (element.isActionable) return element
        element.clickableAncestorIndex?.let { index ->
            // Explicit ancestry is stronger than spatial proximity. An invalid
            // ancestor must not cause a guess at a different neighbouring field.
            return snapshot.elements.singleOrNull { it.index == index && it.isActionable }
        }
        // A loose match in explanatory copy is not a target. An exact, short
        // static label can be mapped to an input only when the geometry is
        // unambiguous. Bare text without a reliable control is never a target.
        val fields = snapshot.elements.filter {
            it.editable && it.eligibility(snapshot.viewportOrLegacy) == ScreenElementEligibility.Eligible
        }
        val best = candidate
        run {
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
                val shortExactLabel = best.element.text.isNotBlank() && best.score >= SCORE_NORMALIZED &&
                    best.element.text.length <= MAX_FIELD_LABEL_LENGTH &&
                    best.element.text.trim().split(Regex("\\s+")).size <= MAX_FIELD_LABEL_WORDS
                if (!shortExactLabel) return null
                val candidates = (containing + below).distinctBy { it.index }
                if (candidates.size != 1) return null
                val field = candidates.single()
                val interveningLabel = snapshot.elements.any { other ->
                    other !== best.element && other.visibleToUser && !other.isActionable &&
                        other.text.isNotBlank() &&
                        other.bounds.top >= best.bounds.bottom &&
                        other.bounds.bottom <= field.bounds.top &&
                        horizontalOverlap(other.bounds, field.bounds) > 0
                }
                if (interveningLabel) return null
                return field
            }
        }
        return null
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

    private fun area(bounds: Rect): Long =
        (bounds.right.toLong() - bounds.left) * (bounds.bottom.toLong() - bounds.top)

    // Small overlaps can be genuinely distinct controls. Only equal geometry
    // is a safe fallback when the tree omitted a shared ancestor index.
    private fun equivalentBounds(a: Rect, b: Rect): Boolean =
        a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

    private fun contains(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left && outer.top <= inner.top &&
            outer.right >= inner.right && outer.bottom >= inner.bottom

    private fun horizontalOverlap(a: Rect, b: Rect): Int =
        (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)

    private const val TAG = "TargetResolver"
    private const val MAX_FIELD_LABEL_LENGTH = 40
    private const val MAX_FIELD_LABEL_WORDS = 2
    private val VISIBILITY_FAILURES = setOf(ScreenElementIneligibility.HIDDEN,
        ScreenElementIneligibility.EMPTY_BOUNDS, ScreenElementIneligibility.OUTSIDE_VIEWPORT,
        ScreenElementIneligibility.MOSTLY_CLIPPED)
}
