package com.screensaathi.screen

/** Explicit structural evidence only; generic modal containers are not dialogs. */
object DialogEvidence {
    private val dialogClasses = setOf("Dialog", "AlertDialog", "BottomSheetDialog")

    fun isDialogNode(
        className: String,
        resourceId: String,
        clickable: Boolean,
        descendantIds: Set<String>,
    ): Boolean = when {
        className in dialogClasses -> true
        resourceId == "parentPanel" ->
            "alertTitle" in descendantIds && "buttonPanel" in descendantIds
        resourceId == "dialog" || resourceId.endsWith("_dialog") ->
            clickable && descendantIds.isNotEmpty()
        else -> false
    }
}
