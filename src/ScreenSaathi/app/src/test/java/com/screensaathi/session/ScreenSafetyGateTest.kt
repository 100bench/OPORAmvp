package com.screensaathi.session

import android.graphics.Rect
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.ScreenViewport
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
    fun `visible route source pin beside address fields does not pause for authentication`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(
                element(text = "", resourceId = "address_input"),
                element(
                    text = "",
                    resourceId = "source_pin",
                    className = "ViewGroup",
                    editable = false,
                ),
                element(
                    text = "",
                    resourceId = "pin_slot",
                    className = "View",
                    editable = false,
                ),
            )
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
    }

    @Test
    fun `PIN slot without secret input context is weak evidence`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(element(
                text = "",
                resourceId = "pin_slot",
                className = "View",
                editable = false,
            ))
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
    }

    @Test
    fun `source input beside PIN slot is ordinary location chrome`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(
                element(text = "", resourceId = "source_input"),
                element(text = "", resourceId = "pin_slot", className = "View", editable = false),
            )
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
    }

    @Test
    fun `disabled noneditable address field needs no PIN exception`() {
        val assessment = ScreenSafetyGate.assess(
            snapshot(
                element(text = "", resourceId = "address_input", editable = false, enabled = false),
                element(text = "", resourceId = "source_pin", className = "ViewGroup", editable = false),
                element(text = "", resourceId = "pin_slot", className = "View", editable = false),
            )
        )

        assertEquals(ScreenDisposition.ALLOW, assessment.disposition)
    }

    @Test
    fun `button and text address controls allow weak PIN identifiers in every package`() {
        for (role in listOf("Button", "TextView", "EditText")) {
            for (id in listOf("pin", "pin_slot", "source_pin", "map_pin", "destination_pin")) {
                val assessment = ScreenSafetyGate.assess(snapshot(
                    element("Куда поедем?", "address_input", role, editable = role == "EditText"),
                    element("", id, "View", editable = false),
                    packageName = "unrelated.example.app",
                ))
                assertEquals("$role / $id", ScreenDisposition.ALLOW, assessment.disposition)
            }
        }
        assertEquals(ScreenDisposition.ALLOW, ScreenSafetyGate.assess(
            snapshot(element("", "pin")),
        ).disposition)
    }

    @Test
    fun `explicit secret language remains strong in visible labels hints and descriptions`() {
        listOf("Password", "SMS code", "Code from SMS", "One-time code", "Код из SMS", "ПИН-код").forEach { label ->
            listOf(
                element(label, editable = false),
                element("", editable = false).copy(hintText = label),
                element("", editable = false).copy(contentDescription = label),
            ).forEach { node ->
                assertEquals(label, ScreenDisposition.PAUSE_AUTH, ScreenSafetyGate.assess(snapshot(node)).disposition)
            }
        }
    }

    @Test
    fun `PIN heading needs visible editable or keypad context`() {
        val heading = element("PIN", "heading", "TextView", editable = false)
        assertEquals(ScreenDisposition.ALLOW, ScreenSafetyGate.assess(snapshot(heading)).disposition)
        listOf(
            element("", "code_entry"),
            element("", "numeric_keypad", "ViewGroup", editable = false),
        ).forEach { context ->
            assertEquals(ScreenDisposition.PAUSE_AUTH, ScreenSafetyGate.assess(snapshot(heading, context)).disposition)
            assertEquals(ScreenDisposition.ALLOW, ScreenSafetyGate.assess(snapshot(heading, context.copy(visibleToUser = false))).disposition)
        }
    }

    @Test
    fun `unlabelled PIN slots require a visible keypad to become authentication evidence`() {
        val slot = element("", "pin_slot", "View", editable = false)
        val keypad = element("", "numeric_keypad", "ViewGroup", editable = false)
        assertEquals(ScreenDisposition.PAUSE_AUTH, ScreenSafetyGate.assess(snapshot(slot, keypad)).disposition)
        assertEquals(ScreenDisposition.ALLOW, ScreenSafetyGate.assess(snapshot(slot, keypad.copy(visibleToUser = false))).disposition)
    }

    @Test
    fun `diagnostics retain evidence and safe roles without external ids classes or values`() {
        val diagnostics = mutableListOf<ScreenSafetyDiagnostic>()
        val secret = "synthetic-private-value"
        listOf(
            element(secret, resourceId = "external_$secret", className = "external.$secret", password = true) to AuthenticationEvidence.PASSWORD_NODE,
            element(secret, inputType = 0x81) to AuthenticationEvidence.PASSWORD_INPUT_TYPE,
            element("Enter OTP") to AuthenticationEvidence.OTP_OR_SMS_TEXT,
            element("Enter PIN") to AuthenticationEvidence.PIN_INPUT_CONTEXT,
            element("Password") to AuthenticationEvidence.PASSWORD_TEXT,
            element("Fingerprint") to AuthenticationEvidence.BIOMETRIC_TEXT,
        ).forEach { (node, expectedEvidence) ->
            val assessment = ScreenSafetyGate.assess(snapshot(node), diagnosticSink = diagnostics::add)
            assertEquals(expectedEvidence, assessment.authenticationEvidence)
            assertEquals(expectedEvidence, diagnostics.last().authenticationEvidence)
            assertEquals(ScreenRiskCategory.AUTHENTICATION, diagnostics.last().category)
            assertFalse(diagnostics.last().toString().contains(node.text))
            assertFalse(diagnostics.last().toString().contains(secret))
        }
        assertEquals("Other", diagnostics.first().role)
        assertEquals("EditText", diagnostics.last().role)
        ScreenSafetyGate.assess(snapshot(element("ordinary")), diagnosticSink = diagnostics::add)
        assertEquals(6, diagnostics.size)
    }

    @Test
    fun `only visible viewport evidence blocks and disabled password captions still block`() {
        val viewport = ScreenViewport(0, 0, 100, 100)
        listOf(
            element("", password = true, enabled = false),
            element("", inputType = 0x81, enabled = false),
            element("Enter OTP", editable = false, enabled = false),
            element("Оплатить", editable = false, enabled = false),
        ).forEach { sensitive ->
            val expected = if (sensitive.text == "Оплатить") ScreenDisposition.SAFE_HANDOFF else ScreenDisposition.PAUSE_AUTH
            assertEquals(expected, ScreenSafetyGate.assess(snapshot(sensitive).copy(viewport = viewport)).disposition)
            listOf(
                Rect().apply { left = 100; right = 200; bottom = 100 },
                Rect(),
                Rect().apply { left = 80; right = 180; bottom = 100 },
            ).forEach { bounds ->
                assertEquals(ScreenDisposition.ALLOW, ScreenSafetyGate.assess(
                    snapshot(sensitive.copy(bounds = bounds)).copy(viewport = viewport),
                ).disposition)
            }
        }
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
        listOf("Введите PIN", "Введите ПИН-код").forEach { label ->
            val assessment = ScreenSafetyGate.assess(
                snapshot(element(
                    text = label,
                    className = "TextView",
                    editable = false,
                ))
            )

            assertEquals(label, ScreenDisposition.PAUSE_AUTH, assessment.disposition)
        }
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
        enabled: Boolean = true,
        password: Boolean = false,
        inputType: Int = 0,
        visibleToUser: Boolean = true,
    ) = ScreenElement(
        index = 0,
        resourceId = resourceId,
        text = text,
        className = className,
        bounds = Rect().apply { left = 0; top = 0; right = 100; bottom = 100 },
        editable = editable,
        clickable = true,
        enabled = enabled,
        password = password,
        inputType = inputType,
        visibleToUser = visibleToUser,
    )
}
