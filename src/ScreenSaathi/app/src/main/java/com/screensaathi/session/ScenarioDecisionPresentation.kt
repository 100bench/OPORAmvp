package com.screensaathi.session

import com.screensaathi.overlay.DecisionButtons
import com.screensaathi.overlay.HighlightBounds
import com.screensaathi.overlay.OverlayCommand
import com.screensaathi.overlay.PillState

/** The actual command consumed by the overlay for a scenario decision. */
internal fun scenarioDecisionCommand(
    effect: ScenarioEffect.ShowDecision,
    language: String,
    usefulInstruction: String?,
    highlight: HighlightBounds?,
): OverlayCommand {
    val (message, buttons) = when (effect.notice) {
        ScenarioNotice.VERIFY_RECOGNITION -> {
            val heard = effect.recognizedGoal?.takeIf(String::isNotBlank)
                ?.let { "Я услышал: «$it». Всё верно?" }
                ?: "Не удалось уверенно разобрать цель. Повторить?"
            heard to if (effect.choices.firstOrNull() == ScenarioChoice.REPEAT_RECOGNITION) {
                DecisionButtons.RETRY_OR_STOP
            } else DecisionButtons.VERIFY_OR_REPEAT
        }
        ScenarioNotice.AUTHENTICATION ->
            "Введите пароль, код или биометрию самостоятельно, затем нажмите «Продолжить»." to
                DecisionButtons.CONTINUE_OR_STOP
        ScenarioNotice.NO_NETWORK ->
            "Нет связи с AI. Проверьте VPN и подключение, затем повторите." to DecisionButtons.RETRY_OR_STOP
        ScenarioNotice.TTS_UNAVAILABLE ->
            "Не удалось озвучить подсказку. Текст остаётся на экране." to DecisionButtons("Повторить", "Продолжить")
        ScenarioNotice.SAFE_HANDOFF ->
            "Дальше требуется ваше личное подтверждение." to DecisionButtons.CONTINUE_OR_STOP
    }
    return OverlayCommand(
        PillState.GUIDING,
        expanded = true,
        instruction = if (effect.notice == ScenarioNotice.TTS_UNAVAILABLE) {
            listOfNotNull(usefulInstruction?.takeIf(String::isNotBlank), message).joinToString("\n\n")
        } else message,
        language = language,
        highlight = if (effect.notice == ScenarioNotice.TTS_UNAVAILABLE) highlight else null,
        decisionButtons = buttons,
    )
}
