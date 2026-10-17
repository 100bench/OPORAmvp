package com.screensaathi.sarvam

/**
 * Language policy for the Russian MVP.
 *
 * Provider responses, old rehearsal intents and legacy task assets may still
 * contain another language label. They all cross this boundary before
 * reaching user-visible UI or speech, so the MVP remains Russian-only.
 */
object Language {
    const val APP_DEFAULT = "ru-RU"
    const val DEFAULT = APP_DEFAULT

    /** The planner schema and user-facing surface expose Russian only. */
    val SUPPORTED = linkedSetOf(APP_DEFAULT)

    fun normalize(code: String?): String = APP_DEFAULT

    fun isSupported(code: String?): Boolean =
        code?.trim()?.equals(APP_DEFAULT, ignoreCase = true) == true

    fun nativeName(code: String): String = "Русский"

    /**
     * True when all prose is Russian (or contains no letters).
     *
     * Latin text is accepted only when it is an exact label already grounded
     * in the current UI tree. This keeps labels such as "Yandex Go" usable,
     * without letting mixed model prose such as "Click … to continue" reach
     * the Russian interface.
     */
    fun isRussianText(text: String, groundedLabels: List<String> = emptyList()): Boolean {
        var prose = text
        groundedLabels.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)
            .forEach { label ->
                prose = Regex(Regex.escape(label), RegexOption.IGNORE_CASE).replace(prose, " ")
            }

        var hasLetters = false
        var hasCyrillic = false
        var index = 0
        while (index < prose.length) {
            val codePoint = prose.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            hasLetters = true
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.CYRILLIC) return false
            hasCyrillic = true
        }
        return !hasLetters || hasCyrillic
    }

    /** Speech output cannot switch language based on an untrusted label. */
    fun reconcile(text: String, claimed: String?): String = APP_DEFAULT
}

/** Text paired with the only language the MVP is allowed to present. */
data class Spoken(val text: String, val language: String = Language.DEFAULT)
