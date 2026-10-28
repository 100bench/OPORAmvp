package com.screensaathi.overlay

data class TaxiOverlayVisibility(
    val instruction: Boolean,
    val voice: Boolean,
    val stop: Boolean,
    val textInput: Boolean,
    val language: Boolean,
    val next: Boolean,
    val choices: Boolean,
    val circleSelection: Boolean,
    val removalPrompt: Boolean,
)

/** User-facing surface for the dedicated taxi experiment branch. */
object TaxiOverlayPolicy {
    const val offerResume = false
    const val speakVoiceErrors = false

    fun visibility(removalPending: Boolean) = TaxiOverlayVisibility(
        instruction = !removalPending,
        voice = !removalPending,
        stop = !removalPending,
        textInput = false,
        language = false,
        next = false,
        choices = false,
        circleSelection = false,
        removalPrompt = removalPending,
    )

    fun failureInstruction(message: String): String =
        "$message Нажмите микрофон и повторите цель."
}
