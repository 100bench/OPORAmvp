package com.screensaathi.session

import java.util.Locale

/** Shared fail-closed vocabulary for controls that complete consequential work. */
object FinalActionPolicy {
    /** Screen labels/resource ids that mean the user has reached a final action surface. */
    fun isFinalAction(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return false
        val plain = normalized.trim('«', '»', '"', '\'', '.', ',', ':', ';', '!', '?')
        return plain in EXACT_FINAL_LABELS ||
            RUSSIAN_FINAL_LABEL.containsMatchIn(normalized) ||
            RUSSIAN_CONTEXTUAL_SUBMISSION.containsMatchIn(normalized) ||
            ENGLISH_FINAL_CONTROL.containsMatchIn(normalized) ||
            FINAL_SCREEN_CONTEXT.containsMatchIn(normalized) ||
            containsFinalActionInstruction(normalized)
    }

    /** Natural guidance text that directs the user to complete a consequential action. */
    fun containsFinalActionInstruction(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return false
        val plain = normalized.trim('«', '»', '"', '\'', '.', ',', ':', ';', '!', '?')
        return plain in EXACT_FINAL_LABELS ||
            LEADING_FINAL_IMPERATIVE.containsMatchIn(normalized) ||
            DIRECTIVE_TO_FINAL_ACTION.containsMatchIn(normalized) ||
            DIRECTIVE_TO_PURCHASE.containsMatchIn(normalized)
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[_/\\-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val EXACT_FINAL_LABELS = setOf(
        "оплатить",
        "заказать",
        "оформить",
        "купить",
        "подтвердить",
        "отправить",
        "pay",
        "checkout",
        "submit",
        "confirm",
        "purchase",
    )

    private val RUSSIAN_FINAL_LABEL = Regex(
        "^(?:оплатить|заказать|оформить|купить)(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val RUSSIAN_CONTEXTUAL_SUBMISSION = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:подать|отправить|подтвердить)\\s+(?:заказ|заказа|заявление|заявления|заявку|форму|документы|данные|обращение|оплату|покупку|отправку)(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val ENGLISH_FINAL_CONTROL = Regex(
        "^(?:pay(?: now)?|checkout|buy now|place order|submit application|confirm (?:order|payment|purchase)|purchase)(?: button)?$",
        RegexOption.IGNORE_CASE,
    )
    private val FINAL_SCREEN_CONTEXT = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:оплата|оформление заказа|подтверждение оплаты|подтверждение заказа|завершение заказа|перейти к оплате|продолжить к оплате|payment confirmation|order confirmation)(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )

    private val LEADING_FINAL_IMPERATIVE = Regex(
        "^[«\"']?(?:оплатить|оплатите|заказать|закажите|оформить|оформите|купить|купите|подать\\s+(?:заявление|заявку)|подайте\\s+(?:заявление|заявку)|отправить\\s+(?:заказ|заявление|заявку|форму|документы|данные|обращение)|отправьте\\s+(?:заказ|заявление|заявку|форму|документы|данные|обращение)|подтвердить\\s+(?:заказ|оплату|покупку|заявление|заявку)|подтвердите\\s+(?:заказ|оплату|покупку|заявление|заявку)|pay|checkout|submit|confirm (?:order|payment|purchase)|buy now|place order)(?:$|[^\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val DIRECTIVE_TO_FINAL_ACTION = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:нажмите|выберите|коснитесь|тапните|перейдите|продолжите|tap|press|select|choose|click)(?:$|[^\\p{L}\\p{N}]).{0,120}(?:оплатить|заказать|оформить|купить|подать\\s+(?:заявление|заявку)|отправить\\s+(?:заказ|заявление|заявку|форму|документы|данные|обращение)|подтвердить\\s+(?:заказ|оплату|покупку|заявление|заявку)|pay(?: now)?|checkout|submit(?: application)?|confirm (?:order|payment|purchase)|buy now|place order)(?:$|[^\\p{L}\\p{N}])",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val DIRECTIVE_TO_PURCHASE = Regex(
        "(?:^|[^\\p{L}\\p{N}])(?:tap|press|select|choose|click)(?:$|[^\\p{L}\\p{N}]).{0,120}purchase(?: now| button)?[.!?]?$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
