package com.screensaathi.screen

/** Projects the raw, device-local snapshot into the only provider-safe DTO. */
object ScreenSanitizer {
    const val EMAIL_MARKER = "[EMAIL]"
    const val PHONE_MARKER = "[PHONE]"
    const val OTP_MARKER = "[OTP]"
    const val NUMBER_MARKER = "[NUMBER]"

    fun sanitize(snapshot: ScreenSnapshot): PlannerScreen = PlannerScreen(
        packageName = snapshot.packageName,
        settled = snapshot.settled,
        elements = snapshot.elements.asSequence()
            .filter { it.visibleToUser && !it.password }
            .map(::sanitizeElement)
            .toList(),
    )

    private fun sanitizeElement(raw: ScreenElement): PlannerElement {
        // Editable text is a user value (address, name, phone, etc.) and never
        // crosses this boundary. Its static hint/description is still useful.
        val labelSource = if (raw.editable) {
            raw.hintText.ifBlank { raw.contentDescription }
        } else {
            raw.text
        }
        val label = sanitizeValue(labelSource)
        val description = sanitizeValue(raw.contentDescription)
            ?.takeUnless { it == label }

        return PlannerElement(
            index = raw.index,
            resourceId = sanitizeValue(raw.resourceId),
            label = label,
            description = description,
            role = sanitizeValue(raw.className) ?: "View",
            editable = raw.editable,
            clickable = raw.clickable,
            enabled = raw.enabled,
            selected = raw.selected,
            checked = raw.checked,
            scrollable = raw.scrollable,
        )
    }

    internal fun sanitizeValue(value: String): String? {
        var safe = value.trim()
        if (safe.isEmpty()) return null

        safe = EMAIL.replace(safe, EMAIL_MARKER)
        safe = PHONE_CANDIDATE.replace(safe) { match ->
            val candidate = match.value
            val digitCount = candidate.count(Char::isDigit)
            val formatted = candidate.startsWith('+') || candidate.any { !it.isDigit() }
            if (formatted && digitCount in 10..15) PHONE_MARKER else candidate
        }
        safe = LONG_NUMBER.replace(safe, NUMBER_MARKER)
        safe = PROBABLE_OTP.replace(safe, OTP_MARKER)
        return safe.takeIf(String::isNotBlank)
    }

    private val EMAIL = Regex(
        pattern = "(?i)(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}(?![\\p{L}\\p{N}._%+-])",
    )
    private val PHONE_CANDIDATE = Regex(
        pattern = "(?<![\\p{L}\\p{N}])\\+?\\d[\\d()\\-\\s]{8,}\\d(?![\\p{L}\\p{N}])",
    )
    private val LONG_NUMBER = Regex("(?<!\\d)\\d{9,}(?!\\d)")
    private val PROBABLE_OTP = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
}
