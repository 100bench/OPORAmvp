package com.screensaathi.overlay

/** The visible card state is independent of the most recent controller command. */
data class AssistantPresentation(
    val command: OverlayCommand = OverlayCommand(PillState.IDLE),
    private val choice: CardChoice = CardChoice.FOLLOW_COMMAND,
    val lastInstruction: String? = null,
    val removalPending: Boolean = false,
    private val beforeRemovalChoice: CardChoice? = null,
) {
    enum class CardChoice { FOLLOW_COMMAND, OPEN, CLOSED }

    val cardVisible: Boolean
        get() = when (choice) {
            CardChoice.OPEN -> true
            CardChoice.CLOSED -> false
            CardChoice.FOLLOW_COMMAND -> command.expanded
        }

    /** The WindowManager frame must match the visible hit area. */
    val windowWidthDp: Int
        get() = if (cardVisible) CARD_WIDTH_DP else CIRCLE_WIDTH_DP

    val canConfirmRemoval: Boolean get() = removalPending

    fun accept(next: OverlayCommand): AssistantPresentation = copy(
        command = next,
        choice = when {
            removalPending -> choice
            next.pillState in ACTIVE_VOICE_STATES -> CardChoice.FOLLOW_COMMAND
            !next.expanded && choice == CardChoice.OPEN -> CardChoice.FOLLOW_COMMAND
            else -> choice
        },
        lastInstruction = next.instruction ?: lastInstruction,
    )

    fun toggle(): AssistantPresentation = if (removalPending) cancelRemoval() else copy(
        choice = if (cardVisible) CardChoice.CLOSED else CardChoice.OPEN,
    )

    fun hide(): AssistantPresentation = cancelRemoval().copy(choice = CardChoice.CLOSED)

    fun requestRemoval(): AssistantPresentation = if (removalPending) this else copy(
        choice = CardChoice.OPEN,
        removalPending = true,
        beforeRemovalChoice = choice,
    )

    fun cancelRemoval(): AssistantPresentation = if (!removalPending) this else copy(
        choice = beforeRemovalChoice ?: CardChoice.FOLLOW_COMMAND,
        removalPending = false,
        beforeRemovalChoice = null,
    )

    companion object {
        const val CIRCLE_WIDTH_DP = 96
        const val CARD_WIDTH_DP = 346
        private val ACTIVE_VOICE_STATES = setOf(
            PillState.LISTENING,
            PillState.THINKING,
            PillState.SPEAKING,
        )
    }
}
