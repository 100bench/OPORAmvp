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

        if (snapshot.elements.any { it.password || isPasswordInputType(it.inputType) }) {
            return pauseAuth()
        }

        val rawLabels = snapshot.elements.asSequence().flatMap(::rawSafetyValues)
        for (value in rawLabels) {
            val normalized = value.lowercase(Locale.ROOT)
            if (AUTHENTICATION.containsMatchIn(normalized)) return pauseAuth()
            if (FINANCIAL.containsMatchIn(normalized)) {
                return safeHandoff(
                    ScreenRiskCategory.FINANCIAL,
                    "На экране запрашиваются платёжные данные. Продолжите самостоятельно.",
                )
            }
            if (FINAL_ACTION.containsMatchIn(normalized)) {
                return safeHandoff(
                    ScreenRiskCategory.FINAL_ACTION,
                    "Дальше возможно подтверждение заказа или оплаты. Проверьте всё и продолжите самостоятельно.",
                )
            }
        }

        return ScreenSafetyAssessment(
            disposition = ScreenDisposition.ALLOW,
            category = ScreenRiskCategory.NONE,
            explanation = "Экран разрешён для безопасного анализа.",
        )
    }

    private fun rawSafetyValues(element: ScreenElement): Sequence<String> = sequenceOf(
        element.text,
        element.contentDescription,
        element.hintText,
        element.resourceId,
    ).filter(String::isNotBlank)

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
        "(?:^|[^\\p{L}\\p{N}])(?:otp|pin|парол(?:ь|я|ем)?|смс[- ]?код|код из смс|код подтверждения|биометри(?:я|и)|отпечат(?:ок|ка|ку|ком|ке) пальц(?:а|ем|е)|face ?id|fingerprint)(?:$|[^\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val FINANCIAL = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:cvv|cvc|номер карты|срок действия карты|данные карты|card number)(?:$|[^\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val FINAL_ACTION = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:оплатить|оплата|подтвердить оплату|оформить заказ|подтвердить заказ|place order|pay now|checkout|confirm purchase)(?:$|[^\\p{L}\\p{N}])",
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
