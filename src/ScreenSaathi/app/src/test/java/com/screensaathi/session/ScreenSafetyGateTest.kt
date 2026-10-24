package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSafetyGateTest {

    @Test
    fun `password flag and password input types pause before serialization`() {
        val cases = listOf(
            element(text = "raw-password", password = true),
            element(text = "raw-text-password", inputType = 0x00000081),
            element(text = "raw-number-password", inputType = 0x00000012),
        )

        cases.forEach { sensitive ->
            val assessment = ScreenSafetyGate.assess(snapshot(sensitive))
            assertEquals(ScreenDisposition.PAUSE_AUTH, assessment.disposition)
            assertEquals(ScreenRiskCategory.AUTHENTICATION, assessment.category)
            assertFalse(assessment.toString().contains(sensitive.text))
        }
    }

    @Test
    fun `authentication vocabulary pauses on raw local labels`() {
        listOf(
            "Код из СМС 482913",
            "Введите PIN",
            "Пароль",
            "Подтвердите по отпечатку пальца",
            "Enter OTP",
        ).forEach { raw ->
            val assessment = ScreenSafetyGate.assess(snapshot(element(text = raw)))
            assertEquals(raw, ScreenDisposition.PAUSE_AUTH, assessment.disposition)
            assertFalse(assessment.toString().contains(raw))
        }
    }

    @Test
    fun `hidden auth controls do not pause a visible address field`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(
                element(text = "Куда поедем?", resourceId = "destination_input"),
                element(
                    text = "Пароль",
                    resourceId = "password_field",
                    password = true,
                    inputType = 0x00000081,
                    visibleToUser = false,
                ),
            )
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
    }

    @Test
    fun `visible map pin is not treated as a PIN code field`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(
                element(text = "Куда поедем?", resourceId = "destination_input"),
                element(
                    text = "Map pin",
                    resourceId = "map_pin",
                    className = "ImageView",
                    editable = false,
                ),
            )
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
    }

    @Test
    fun `unlabelled final action button remains a local handoff`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(element(
                text = "",
                resourceId = "pay_now_button",
                className = "ImageButton",
                editable = false,
            ))
        )

        assertEquals(ScreenDisposition.SAFE_HANDOFF, assessment.disposition)
        assertEquals(ScreenRiskCategory.FINAL_ACTION, assessment.category)
    }

    @Test
    fun `visible PIN input ID still pauses without a label`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(element(text = "", resourceId = "pin_input"))
        )

        assertEquals(ScreenDisposition.PAUSE_AUTH, assessment.disposition)
    }

    @Test
    fun `plain visible PIN heading still pauses`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(element(
                text = "Введите PIN",
                className = "TextView",
                editable = false,
            ))
        )

        assertEquals(ScreenDisposition.PAUSE_AUTH, assessment.disposition)
    }

    @Test
    fun `card and irreversible payment or order actions use safe handoff`() {
        listOf(
            "Номер карты",
            "CVV",
            "Оплатить 1 290 ₽",
            "Оформить заказ",
            "Заказать",
            "Оформить",
            "Подтвердить",
            "Купить",
            "Отправить заявление",
            "Подать заявление",
            "Заказать такси",
            "Заказать еду",
            "Оформление заказа",
            "Подтверждение оплаты",
            "Place order",
            "Submit application",
        ).forEach { raw ->
            val assessment = ScreenSafetyGate.assess(snapshot(element(text = raw)))
            assertEquals(raw, ScreenDisposition.SAFE_HANDOFF, assessment.disposition)
            assertFalse(assessment.toString().contains(raw))
        }
    }

    @Test
    fun `known bank packages and app labels are blocked locally`() {
        listOf(
            snapshot(element(text = "Главная"), packageName = "ru.sberbankmobile") to null,
            snapshot(element(text = "Главная"), packageName = "com.idamob.tinkoff.android") to null,
            snapshot(element(text = "Главная"), packageName = "ru.example.shell") to "Альфа-Банк",
        ).forEach { (screen, appLabel) ->
            val assessment = ScreenSafetyGate.assess(screen, appLabel)
            assertEquals(ScreenDisposition.SAFE_HANDOFF, assessment.disposition)
            assertEquals(ScreenRiskCategory.BANKING, assessment.category)
        }
    }

    @Test
    fun `ordinary screen is allowed and assessment cannot carry a tree`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(
                element(text = "История заказов"),
                element(text = "Purchase history", resourceId = "purchase_history"),
                element(text = "Отправить сообщение", resourceId = "send_message"),
                element(text = "Подтвердить выбор", resourceId = "confirm_selection"),
            )
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
        assertEquals(ScreenRiskCategory.NONE, assessment.category)
        val fieldNames = assessment.javaClass.declaredFields.map { it.name.lowercase() }
        assertTrue(fieldNames.none { it.contains("screen") || it.contains("tree") || it.contains("element") })
    }

    private fun snapshot(
        vararg elements: ScreenElement,
        packageName: String = "ru.example.app",
    ) = ScreenSnapshot(packageName, settled = true, elements = elements.toList())

    private fun element(
        text: String,
        resourceId: String = "field",
        className: String = "EditText",
        editable: Boolean = true,
        password: Boolean = false,
        inputType: Int = 0,
        visibleToUser: Boolean = true,
    ) = ScreenElement(
        index = 0,
        resourceId = resourceId,
        text = text,
        className = className,
        bounds = Rect(0, 0, 100, 100),
        editable = editable,
        clickable = true,
        password = password,
        inputType = inputType,
        visibleToUser = visibleToUser,
    )
}
