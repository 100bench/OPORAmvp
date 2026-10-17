package com.screensaathi.screen

/**
 * Immutable, provider-safe projection of a live accessibility tree.
 *
 * It deliberately has no Android objects, coordinates, or editable values.
 * [ScreenSanitizer] is the only production constructor boundary.
 */
data class PlannerElement(
    val index: Int,
    val resourceId: String?,
    val label: String?,
    val description: String?,
    val role: String,
    val editable: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val scrollable: Boolean,
)

data class PlannerScreen(
    val packageName: String,
    val settled: Boolean,
    val elements: List<PlannerElement>,
) {
    /** The sole serialization format allowed in planner prompts. */
    fun toPromptText(): String {
        if (elements.isEmpty()) return "Screen: $packageName (no readable elements)"
        return buildString {
            append("Screen: ").append(packageName)
                .append(" settled=").append(settled)
                .append("\nElements:\n")
            elements.forEach { element ->
                append("[").append(element.index).append("] role=")
                    .append(quote(element.role))
                element.resourceId?.let { append(" id=").append(quote(it)) }
                element.label?.let { append(" label=").append(quote(it)) }
                element.description?.let { append(" description=").append(quote(it)) }
                append(" editable=").append(element.editable)
                    .append(" clickable=").append(element.clickable)
                    .append(" enabled=").append(element.enabled)
                    .append(" selected=").append(element.selected)
                    .append(" checked=").append(element.checked)
                    .append(" scrollable=").append(element.scrollable)
                    .append('\n')
            }
        }
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n', '\r' -> append("\\n")
                else -> append(char)
            }
        }
        append('"')
    }
}
