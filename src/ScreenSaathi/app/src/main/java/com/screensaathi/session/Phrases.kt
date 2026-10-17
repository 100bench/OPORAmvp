package com.screensaathi.session

import com.screensaathi.sarvam.Language
import com.screensaathi.sarvam.Spoken

/** All assistant-owned wording for the Russian-only MVP surface. */
object Phrases {

    enum class Key {
        LISTENING,
        THINKING,
        DIDNT_CATCH,
        HOLD_LONGER,
        MIC_OFF,
        ALL_DONE,
        STOPPED,
        RESUMED,
        NO_TASKS,
        TAP_MIC,
        CHOOSE_APP,
        OPENING_APP,
        APP_WONT_OPEN,
        NO_RIDE_APPS,
        CIRCLE_HINT,
        CIRCLE_FOUND,
        CIRCLE_SELECTED_TEXT,
        CIRCLE_NEEDS_VISION,
        CIRCLE_NOTHING_FOUND,
        CIRCLE_ASK,
        CIRCLE_CLARIFY,
    }

    private val RU = mapOf(
        Key.LISTENING to "Слушаю… когда закончите, нажмите на микрофон ещё раз.",
        Key.THINKING to "Секунду…",
        Key.DIDNT_CATCH to "Не удалось разобрать речь — давайте начнём отсюда.",
        Key.HOLD_LONGER to "Не удалось вас услышать — говорите немного дольше.",
        Key.MIC_OFF to "Нет доступа к микрофону. Я всё равно буду подсказывать по шагам.",
        Key.ALL_DONE to "Это был последний шаг — всё готово!",
        Key.STOPPED to "Остановлено. Нажмите на микрофон, когда захотите продолжить.",
        Key.RESUMED to "Продолжим с того места, где остановились.",
        Key.NO_TASKS to "Нет доступных сценариев.",
        Key.TAP_MIC to "Нажмите на микрофон и скажите, что нужно сделать.",
        Key.CHOOSE_APP to "Какое приложение вы хотите использовать?",
        Key.OPENING_APP to "Открываю %s…",
        Key.APP_WONT_OPEN to "Не удалось открыть это приложение. Выберите другое.",
        Key.NO_RIDE_APPS to "На этом телефоне нет приложений для заказа такси.",
        Key.CIRCLE_HINT to "Обведите нужный объект на экране",
        Key.CIRCLE_FOUND to "Я нашёл: «%s».",
        Key.CIRCLE_SELECTED_TEXT to "На экране написано: «%s».",
        Key.CIRCLE_NEEDS_VISION to "UI-дерево не содержит описания выбранной области. Я не буду угадывать её содержимое.",
        Key.CIRCLE_NOTHING_FOUND to "В этой области ничего не удалось прочитать. Попробуйте обвести кнопку или текст.",
        Key.CIRCLE_ASK to "Нажмите на микрофон и задайте вопрос об этом объекте.",
        Key.CIRCLE_CLARIFY to "Вы выбрали «%s». Рассказать об этом или помочь использовать?",
    )

    fun get(key: Key, language: String): Spoken =
        Spoken(RU.getValue(key), Language.APP_DEFAULT)

    val AUTHORED: Set<String> = setOf(Language.APP_DEFAULT)
}
