package com.screensaathi.overlay

/**
 * The only input the overlay renderer accepts (contracts/overlay.schema.json).
 * Everything above the overlay speaks to it exclusively through this. The
 * renderer never reasons — it only draws what it is told.
 */

enum class PillState { IDLE, LISTENING, THINKING, SPEAKING, GUIDING, ERROR }

enum class DecisionChoice { PRIMARY, SECONDARY }

data class DecisionButtons(
    val primaryLabel: String,
    val secondaryLabel: String,
) {
    init {
        require(primaryLabel.isNotBlank()) { "Primary decision label cannot be blank" }
        require(secondaryLabel.isNotBlank()) { "Secondary decision label cannot be blank" }
    }

    companion object {
        val VERIFY_OR_REPEAT = DecisionButtons("Верно", "Повторить")
        val START_OR_CANCEL = DecisionButtons("Начать", "Отмена")
        val RETRY_OR_STOP = DecisionButtons("Повторить", "Остановить")
        val CONTINUE_OR_STOP = DecisionButtons("Продолжить", "Остановить")
    }
}

data class HighlightBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val shape: String = "rect",
    val pulse: Boolean = true,
)

data class OverlayCommand(
    val pillState: PillState,
    val expanded: Boolean = false,
    val instruction: String? = null,
    val highlight: HighlightBounds? = null,
    /**
     * Language of [instruction], BCP-47. Optional addition to the v1 overlay
     * contract. The renderer uses it for the pill's own labels ("Listening…")
     * and the language chip, so the chrome speaks the user's language too
     * rather than staying English around a Hindi sentence.
     */
    val language: String = com.screensaathi.sarvam.Language.APP_DEFAULT,
    /**
     * Options for the user to pick from, e.g. the ride apps on this phone.
     * Empty for an ordinary guiding step. The renderer shows one button per
     * entry and reports back the index that was tapped.
     */
    val choices: List<String> = emptyList(),
    /** Optional two-way decision; concrete app cards remain in [choices]. */
    val decisionButtons: DecisionButtons? = null,
)
