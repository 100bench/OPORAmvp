package com.screensaathi.session

import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenSnapshot
import java.util.Locale

enum class ScreenDisposition {
    ALLOW,
    PAUSE_AUTH,
    SAFE_HANDOFF,
}

enum class ScreenRiskCategory {
    NONE,
    AUTHENTICATION,
    FINANCIAL,
    FINAL_ACTION,
    BANKING,
}

/** A classification only: it can never retain or serialize the source tree. */
data class ScreenSafetyAssessment(
    val disposition: ScreenDisposition,
    val category: ScreenRiskCategory,
    val explanation: String,
)

/**
 * Local, deterministic safety boundary that runs on the raw snapshot before
 * sanitization or any network request. It contains risk vocabulary only; no
 * screen sequence or guidance logic belongs here.
 */
object ScreenSafetyGate {
    fun assess(snapshot: ScreenSnapshot, appLabel: String? = null): ScreenSafetyAssessment {
        if (isKnownBank(snapshot.packageName, appLabel)) {
            return safeHandoff(
                ScreenRiskCategory.BANKING,
                "Это банковское приложение. Продолжите самостоятельно; данные экрана не отправляются.",
            )
        }

        val visibleElements = snapshot.elements.filter(ScreenElement::visibleToUser)
        val addressEntryVisible = visibleElements.any {
            it.editable && it.enabled && ADDRESS_ENTRY_ID.containsMatchIn(it.resourceId)
        }
        val locationMarkerVisible = visibleElements.any {
            !it.editable && LOCATION_PIN.containsMatchIn(it.resourceId)
        }
        if (visibleElements.any { it.password || isPasswordInputType(it.inputType) }) {
            return pauseAuth()
        }

        for (element in visibleElements) {
            for ((source, value) in rawSafetyValues(element)) {
                val normalized = value.lowercase(Locale.ROOT)
                if (isAuthenticationSignal(normalized, element, source, addressEntryVisible, locationMarkerVisible)) {
                    return pauseAuth()
                }
                if (FINANCIAL.containsMatchIn(normalized)) {
                    return safeHandoff(
                        ScreenRiskCategory.FINANCIAL,
                        "На экране запрашиваются платёжные данные. Продолжите самостоятельно.",
                    )
                }
                if (FinalActionPolicy.isFinalAction(normalized)) {
                    return safeHandoff(
                        ScreenRiskCategory.FINAL_ACTION,
                        "Дальше возможно подтверждение заказа или оплаты. Проверьте всё и продолжите самостоятельно.",
                    )
                }
            }
        }

        return ScreenSafetyAssessment(
            disposition = ScreenDisposition.ALLOW,
            category = ScreenRiskCategory.NONE,
            explanation = "Экран разрешён для безопасного анализа.",
        )
    }

    private fun rawSafetyValues(element: ScreenElement): Sequence<Pair<String, String>> = sequenceOf(
        "text" to element.text,
        "description" to element.contentDescription,
        "hint" to element.hintText,
        "resource_id" to element.resourceId,
    ).filter { it.second.isNotBlank() }

    private fun isAuthenticationSignal(
        value: String,
        element: ScreenElement,
        source: String,
        addressEntryVisible: Boolean,
        locationMarkerVisible: Boolean,
    ): Boolean {
        if (AUTHENTICATION.containsMatchIn(value)) return true
        if (!PIN_TOKEN.containsMatchIn(value)) return false
        // "Map pin" is a location marker, not a secret code. Keep a plain PIN
        // label blocked, including on a non-editable heading or keypad.
        if (element.editable) return true
        if (LOCATION_PIN.containsMatchIn(value)) return false
        // Some map widgets expose an unlabelled marker as `pin_slot`. Treat it
        // as location chrome only while a real address-entry field is visible.
        if (source == "resource_id" && value == "pin_slot" && addressEntryVisible && locationMarkerVisible) {
            return false
        }
        return true
    }

    private fun pauseAuth() = ScreenSafetyAssessment(
        disposition = ScreenDisposition.PAUSE_AUTH,
        category = ScreenRiskCategory.AUTHENTICATION,
        explanation = "Обнаружен этап авторизации. Введите секретные данные самостоятельно и затем продолжите.",
    )

    private fun safeHandoff(category: ScreenRiskCategory, explanation: String) =
        ScreenSafetyAssessment(ScreenDisposition.SAFE_HANDOFF, category, explanation)

    private fun isPasswordInputType(inputType: Int): Boolean {
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return (inputClass == TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS) ||
            (inputClass == TYPE_CLASS_NUMBER && variation == TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun isKnownBank(packageName: String, appLabel: String?): Boolean {
        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        if (BANK_PACKAGES.any { normalizedPackage == it || normalizedPackage.startsWith("$it.") }) {
            return true
        }
        val normalizedLabel = appLabel?.lowercase(Locale.ROOT)?.trim().orEmpty()
        return BANK_LABELS.any { normalizedLabel == it || normalizedLabel.startsWith("$it ") }
    }

    private val AUTHENTICATION = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:otp|парол(?:ь|я|ем)?|смс[- ]?код|код из смс|код подтверждения|биометри(?:я|и)|отпечат(?:ок|ка|ку|ком|ке) пальц(?:а|ем|е)|face ?id|fingerprint)(?:$|[^\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val PIN_TOKEN = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:pin|пин)(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val LOCATION_PIN = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:map|location|marker|source|origin|destination|pickup|dropoff|waypoint|route|карта|карты|адрес|точка)[_\\s-]+pin(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESS_ENTRY_ID = Regex(
        "(?:^|[_-])(?:address|destination|pickup|dropoff)(?:[_-]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val FINANCIAL = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:cvv|cvc|номер карты|срок действия карты|данные карты|card number)(?:$|[^\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val BANK_PACKAGES = setOf(
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "com.openbank",
        "ru.raiffeisennews",
        "ru.gazprombank.android.mobilebank.app",
    )
    private val BANK_LABELS = setOf(
        "сбербанк",
        "сбербанк онлайн",
        "т-банк",
        "тинькофф",
        "втб",
        "альфа-банк",
        "газпромбанк",
        "райффайзен банк",
        "озон банк",
    )

    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010
    private val TEXT_PASSWORD_VARIATIONS = setOf(0x00000080, 0x00000090, 0x000000e0)
}
