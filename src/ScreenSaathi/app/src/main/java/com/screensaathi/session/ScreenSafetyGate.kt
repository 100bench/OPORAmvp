package com.screensaathi.session

import android.util.Log
import com.screensaathi.screen.ScreenElement
import com.screensaathi.screen.ScreenElementEligibility
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.visibilityEligibility
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

enum class AuthenticationEvidence {
    PASSWORD_NODE, PASSWORD_INPUT_TYPE, OTP_OR_SMS_TEXT, PIN_INPUT_CONTEXT,
    PASSWORD_TEXT, BIOMETRIC_TEXT,
}

/** Only fixed structural roles are retained; external IDs/classes may contain personal data. */
data class ScreenSafetyDiagnostic(
    val category: ScreenRiskCategory,
    val authenticationEvidence: AuthenticationEvidence?,
    val packageName: String,
    val role: String,
)

/** A classification only: it can never retain or serialize the source tree. */
data class ScreenSafetyAssessment(
    val disposition: ScreenDisposition,
    val category: ScreenRiskCategory,
    val explanation: String,
    val authenticationEvidence: AuthenticationEvidence? = null,
)

/**
 * Local, deterministic safety boundary that runs on the raw snapshot before
 * sanitization or any network request. It contains risk vocabulary only; no
 * screen sequence or guidance logic belongs here.
 */
object ScreenSafetyGate {
    fun assess(
        snapshot: ScreenSnapshot,
        appLabel: String? = null,
        diagnosticSink: (ScreenSafetyDiagnostic) -> Unit = { Log.d("ScreenSafetyGate", it.toString()) },
    ): ScreenSafetyAssessment {
        fun blocked(assessment: ScreenSafetyAssessment, node: ScreenElement? = null): ScreenSafetyAssessment {
            diagnosticSink(ScreenSafetyDiagnostic(
                assessment.category, assessment.authenticationEvidence, snapshot.packageName,
                node?.className?.let { name -> SAFE_ROLES.firstOrNull { name == it || name == "android.widget.$it" } } ?: "Other",
            ))
            return assessment
        }
        if (isKnownBank(snapshot.packageName, appLabel)) {
            return blocked(safeHandoff(
                ScreenRiskCategory.BANKING,
                "Это банковское приложение. Продолжите самостоятельно; данные экрана не отправляются.",
            ))
        }

        val visibleElements = snapshot.elements.filter {
            it.visibilityEligibility(snapshot.viewportOrLegacy) == ScreenElementEligibility.Eligible
        }
        // Authentication wins over financial/final labels elsewhere on the screen.
        val keypadVisible = visibleElements.any { KEYPAD_CONTEXT.containsMatchIn(it.resourceId) }
        val pinInputContext = keypadVisible || visibleElements.any { it.editable }
        for (element in visibleElements) {
            authenticationEvidence(element, pinInputContext, keypadVisible)?.let {
                return blocked(pauseAuth(it), element)
            }
        }
        for (element in visibleElements) {
            for (value in rawSafetyValues(element)) {
                val normalized = value.lowercase(Locale.ROOT)
                if (FINANCIAL.containsMatchIn(normalized)) {
                    return blocked(safeHandoff(
                        ScreenRiskCategory.FINANCIAL,
                        "На экране запрашиваются платёжные данные. Продолжите самостоятельно.",
                    ), element)
                }
                if (FinalActionPolicy.isFinalAction(normalized)) {
                    return blocked(safeHandoff(
                        ScreenRiskCategory.FINAL_ACTION,
                        "Дальше возможно подтверждение заказа или оплаты. Проверьте всё и продолжите самостоятельно.",
                    ), element)
                }
            }
        }

        return ScreenSafetyAssessment(
            disposition = ScreenDisposition.ALLOW,
            category = ScreenRiskCategory.NONE,
            explanation = "Экран разрешён для безопасного анализа.",
        )
    }

    private fun rawSafetyValues(element: ScreenElement): Sequence<String> =
        visibleLabels(element) + sequenceOf(element.resourceId)

    private fun visibleLabels(element: ScreenElement): Sequence<String> = sequenceOf(
        element.text, element.contentDescription, element.hintText,
    ).filter(String::isNotBlank)

    private fun authenticationEvidence(
        element: ScreenElement,
        pinInputContext: Boolean,
        keypadVisible: Boolean,
    ): AuthenticationEvidence? {
        if (element.password) return AuthenticationEvidence.PASSWORD_NODE
        if (isPasswordInputType(element.inputType)) return AuthenticationEvidence.PASSWORD_INPUT_TYPE
        for (value in visibleLabels(element)) {
            if (PASSWORD_TEXT.containsMatchIn(value)) return AuthenticationEvidence.PASSWORD_TEXT
            if (OTP_OR_SMS.containsMatchIn(value)) return AuthenticationEvidence.OTP_OR_SMS_TEXT
            if (BIOMETRIC_TEXT.containsMatchIn(value)) return AuthenticationEvidence.BIOMETRIC_TEXT
            if (PIN_TOKEN.containsMatchIn(value) && !LOCATION_PIN.containsMatchIn(value) &&
                (PIN_SECRET_CONTEXT.containsMatchIn(value) || pinInputContext)
            ) return AuthenticationEvidence.PIN_INPUT_CONTEXT
        }
        // Resource tokens alone are weak. Require an actual editable PIN input
        // or explicit PIN keypad structure, never a generic map marker/slot.
        if (PIN_TOKEN.containsMatchIn(element.resourceId) &&
            !LOCATION_PIN.containsMatchIn(element.resourceId) &&
            ((element.editable && INPUT_CONTEXT.containsMatchIn(element.resourceId)) || keypadVisible)
        ) return AuthenticationEvidence.PIN_INPUT_CONTEXT
        return null
    }

    private fun pauseAuth(evidence: AuthenticationEvidence) = ScreenSafetyAssessment(
        disposition = ScreenDisposition.PAUSE_AUTH,
        category = ScreenRiskCategory.AUTHENTICATION,
        explanation = "Обнаружен этап авторизации. Введите секретные данные самостоятельно и затем продолжите.",
        authenticationEvidence = evidence,
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

    private fun tokens(pattern: String) = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:$pattern)(?:$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE,
    )
    private val PASSWORD_TEXT = tokens("password|парол(?:ь|я|ем)?")
    private val OTP_OR_SMS = tokens("otp|(?:sms|смс)[- ]?код|(?:sms|смс)[- ]code|код (?:из (?:смс|sms)|подтверждения)|code from sms|one[- ]time (?:code|password)|verification code")
    private val BIOMETRIC_TEXT = tokens("биометри(?:я|и)|отпечат(?:ок|ка|ку|ком|ке) пальц(?:а|ем|е)|face ?id|fingerprint")
    private val PIN_SECRET_CONTEXT = tokens("enter|введите|ввод|код|code|secret|секретный")
    private val INPUT_CONTEXT = tokens("input|entry|field|edit")
    private val KEYPAD_CONTEXT = tokens("keypad|numpad")
    private val SAFE_ROLES = setOf("EditText", "TextView", "Button", "ImageButton", "ImageView", "View", "ViewGroup")
    private val PIN_TOKEN = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:pin|пин)(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val LOCATION_PIN = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:map|location|marker|source|origin|destination|pickup|dropoff|waypoint|route|карта|карты|адрес|точка)[_\\s-]+pin(?:$|[^\\p{L}\\p{N}])",
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
