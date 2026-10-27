package com.screensaathi.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalActionPolicyTest {
    @Test fun `publishing is a final action but publication settings remain ordinary`() {
        listOf("Опубликовать", "Опубликовать объявление", "Publish listing").forEach {
            assertTrue(it, FinalActionPolicy.isFinalAction(it))
        }
        listOf("Нажмите «Опубликовать»", "Опубликуйте объявление", "Please tap Publish listing").forEach {
            assertTrue(it, FinalActionPolicy.containsFinalActionInstruction(it))
        }
        listOf("Настройки публикации", "Мои объявления", "Publication history").forEach {
            assertFalse(it, FinalActionPolicy.isFinalAction(it))
        }
    }
    @Test
    fun `final controls and consequential submissions are detected`() {
        listOf(
            "Оплатить",
            "Заказать за 500 ₽",
            "Оформить заказ",
            "Подтвердить заказ",
            "Подать заявление",
            "Отправить заявление",
            "Заказать такси",
            "Заказать еду",
            "confirm_order_button",
            "Submit application",
        ).forEach { value ->
            assertTrue(value, FinalActionPolicy.isFinalAction(value))
        }
    }

    @Test
    fun `benign history messaging and choice controls stay allowed`() {
        listOf(
            "История заказов",
            "purchase_history",
            "Purchase history",
            "Отправить сообщение",
            "send_message",
            "Подтвердить выбор",
            "confirm_selection",
        ).forEach { value ->
            assertFalse(value, FinalActionPolicy.isFinalAction(value))
        }
    }

    @Test
    fun `directives are detected after polite and explanatory lead ins`() {
        listOf(
            "Нажмите «Оплатить»",
            "Чтобы продолжить, нажмите «Оплатить»",
            "Пожалуйста, выберите «Подать заявление»",
            "When ready, tap Pay now",
            "Нажмите «Заказать такси».",
            "Выберите «Заказать еду».",
        ).forEach { value ->
            assertTrue(value, FinalActionPolicy.containsFinalActionInstruction(value))
        }

        listOf(
            "На экране есть кнопка «Оплатить».",
            "На экране есть кнопка «Заказать такси».",
            "Откройте пункт «Отправить сообщение».",
            "Выберите «Подтвердить выбор».",
        ).forEach { value ->
            assertFalse(value, FinalActionPolicy.containsFinalActionInstruction(value))
        }
    }
}
