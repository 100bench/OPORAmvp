package com.screensaathi.overlay

/** Russian-only status labels for the MVP overlay. */
object PillLabels {
    private val RU = mapOf(
        PillState.IDLE to "Опора",
        PillState.LISTENING to "Слушаю…",
        PillState.THINKING to "Думаю…",
        PillState.SPEAKING to "Говорю…",
        PillState.GUIDING to "Подсказываю",
        PillState.ERROR to "Попробуем ещё раз",
    )

    fun forState(state: PillState, language: String): String = RU.getValue(state)
}
