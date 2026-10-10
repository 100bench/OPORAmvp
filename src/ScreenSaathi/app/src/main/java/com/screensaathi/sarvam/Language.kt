package com.screensaathi.sarvam

/**
 * The languages ScreenSaathi will speak, and the rules for choosing one.
 *
 * The list is the set currently exposed by the app and its planner contract.
 * Keep the planner schema, UI choices, and language reconciliation in sync
 * when adding a code.
 */
object Language {

    /** Product language for the Russian MVP. */
    const val APP_DEFAULT = "ru-RU"

    /**
     * Used when nothing has been detected yet, and for any text we only
     * authored in English. [Spoken] carries a language with its text so both
     * the planner and the speech instruction can stay explicit.
     */
    const val DEFAULT = "en-IN"

    /** Languages currently supported by the app's UI and planner contract. */
    val SUPPORTED = linkedSetOf(
        "ru-RU", "en-IN", "hi-IN", "bn-IN", "gu-IN", "kn-IN",
        "ml-IN", "mr-IN", "pa-IN", "ta-IN", "te-IN",
    )

    /** Endonyms — what a speaker calls their own language. Used in the UI. */
    private val NATIVE_NAME = mapOf(
        "ru-RU" to "Русский",
        "en-IN" to "English",
        "hi-IN" to "हिन्दी",
        "bn-IN" to "বাংলা",
        "gu-IN" to "ગુજરાતી",
        "kn-IN" to "ಕನ್ನಡ",
        "ml-IN" to "മലയാളം",
        "mr-IN" to "मराठी",
        "pa-IN" to "ਪੰਜਾਬੀ",
        "ta-IN" to "தமிழ்",
        "te-IN" to "తెలుగు",
    )

    /**
     * Coerce whatever STT or the planner hands us into a code we support.
     * Accepts a bare tag ("hi") as well as a full one ("hi-IN"), because
     * the planner is a language model and will occasionally shorten it.
     *
     * everything funnels through here before reaching the UI or speech layer.
     */
    fun normalize(code: String?): String {
        val raw = code?.trim()?.replace('_', '-') ?: return DEFAULT
        if (raw.isEmpty()) return DEFAULT
        SUPPORTED.firstOrNull { it.equals(raw, ignoreCase = true) }?.let { return it }
        val base = raw.substringBefore('-').lowercase()
        return SUPPORTED.firstOrNull { it.substringBefore('-') == base } ?: DEFAULT
    }

    fun isSupported(code: String?): Boolean =
        code != null && SUPPORTED.any { it.equals(code.trim(), ignoreCase = true) }

    /** For the debug panel and the language chip on the card. */
    fun nativeName(code: String): String = NATIVE_NAME[normalize(code)] ?: code

    /** Script each language is actually written in. Hindi and Marathi share one. */
    private val SCRIPT = mapOf(
        "ru-RU" to Character.UnicodeScript.CYRILLIC,
        "en-IN" to Character.UnicodeScript.LATIN,
        "hi-IN" to Character.UnicodeScript.DEVANAGARI,
        "mr-IN" to Character.UnicodeScript.DEVANAGARI,
        "bn-IN" to Character.UnicodeScript.BENGALI,
        "gu-IN" to Character.UnicodeScript.GUJARATI,
        "kn-IN" to Character.UnicodeScript.KANNADA,
        "ml-IN" to Character.UnicodeScript.MALAYALAM,
        "pa-IN" to Character.UnicodeScript.GURMUKHI,
        "ta-IN" to Character.UnicodeScript.TAMIL,
        "te-IN" to Character.UnicodeScript.TELUGU,
    )

    /** First supported code written in each script; the inverse of [SCRIPT]. */
    private val CODE_FOR_SCRIPT: Map<Character.UnicodeScript, String> =
        SCRIPT.entries.reversed().associate { (code, script) -> script to code }

    /**
     * Scripts present in [text], with a count of characters in each.
     *
     * Counts every character of the script, not just `isLetter` ones. Devanagari
     * vowel signs like "ा" and "ि" are combining marks, not letters, so a
     * letters-only count undercounts Devanagari badly — "amount यहाँ भरिए" comes
     * out 6 Latin to 5 Devanagari and gets called English.
     */
    private fun scriptsIn(text: String): Map<Character.UnicodeScript, Int> {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val script = runCatching { Character.UnicodeScript.of(cp) }.getOrNull() ?: continue
            if (script == Character.UnicodeScript.COMMON ||
                script == Character.UnicodeScript.UNKNOWN ||
                script == Character.UnicodeScript.INHERITED
            ) continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        return counts
    }

    /**
     * The language code that best matches [text]. The planner can sometimes label romanised
     * Hindi as `hi-IN`, or answer in English while claiming the user's language.
     *
     * The rule uses **presence, not majority**.
     *  - If the claimed language's script appears at all, keep the claim. This
     *    is what makes code-switching work — "amount यहाँ भरिए" stays `hi-IN`,
     *    and TTS reads the embedded English word naturally.
     *  - Otherwise fall to whichever Indic script is actually there, so
     *    Devanagari labelled `en-IN` is corrected rather than mispronounced.
     *  - Failing that, romanised or English text becomes `en-IN`.
     */
    fun reconcile(text: String, claimed: String?): String {
        val claim = normalize(claimed)
        val present = scriptsIn(text)
        if (present.isEmpty()) return claim // digits or punctuation only

        SCRIPT[claim]?.let { if (present.containsKey(it)) return claim }

        val nonLatin = present.entries
            .filter { it.key != Character.UnicodeScript.LATIN }
            .maxByOrNull { it.value }
            ?.key
        if (nonLatin != null) CODE_FOR_SCRIPT[nonLatin]?.let { return it }

        return DEFAULT
    }
}

/**
 * A piece of text together with the language it is actually written in.
 *
 * Language detection used to be propagated to TTS while the *text* stayed
 * English, so a Hindi speaker heard English words requested as `hi-IN`. Pairing
 * the two makes that mismatch unrepresentable: whatever produced the string —
 * the task DSL, the planner, or a UI phrase — says which language it wrote.
 */
data class Spoken(val text: String, val language: String = Language.DEFAULT)
