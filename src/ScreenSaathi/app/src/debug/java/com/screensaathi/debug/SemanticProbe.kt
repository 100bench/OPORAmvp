package com.screensaathi.debug

import com.screensaathi.screen.ScreenElementEligibility
import com.screensaathi.screen.ScreenSnapshot
import com.screensaathi.screen.eligibility
import com.screensaathi.session.ScreenDisposition
import com.screensaathi.session.ScreenSafetyGate
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Debug-only fixed-vocabulary projection; never retains source labels or identifiers. */
internal object SemanticProbe {
    const val HANDOFF_TEXT = "Дальше требуется ваше личное подтверждение. Проверьте данные и продолжите самостоятельно."
    const val FINAL_ACTION_HANDOFF_TEXT = "Дальше возможно подтверждение заказа или оплаты. Проверьте всё и продолжите самостоятельно."
    val HANDOFF_TEXTS = setOf(HANDOFF_TEXT, FINAL_ACTION_HANDOFF_TEXT)
    private val vocabulary = mapOf(
        "SELL_ENTRY" to listOf("продать", "продать автомобиль", "продать машину", "добавить объявление", "разместить объявление"),
        "PLATE_FIELD" to listOf("госномер", "государственный номер", "номер автомобиля"),
        "VIN_FIELD" to listOf("vin", "vin-код", "vin код"),
        "SKIP_OPTION" to listOf("пропустить", "указать позже", "без госномера", "без номера", "добавить вручную", "заполнить вручную"),
        "PHONE_FIELD" to listOf("номер телефона", "ваш телефон", "телефон"),
        "SIGN_IN" to listOf("войти", "вход", "авторизация"),
        "PUBLIC_TEST_PLACE" to listOf("red square", "красная площадь", "парк победы", "park pobedy"),
        "DESTINATION_PROMPT" to listOf("куда поедем", "куда поедете", "куда едем", "куда", "where to"),
        "ORIGIN_PROMPT" to listOf("откуда", "откуда поедем", "откуда забрать"),
        "TAXI" to listOf("такси", "taxi"),
        "SEARCH" to listOf("поиск", "найти", "search"),
        "ADDRESS_INPUT" to listOf("введите адрес", "адрес назначения", "destination"),
        "CONTINUE" to listOf("продолжить", "далее", "готово", "continue", "done"),
        "BACK" to listOf("назад", "back"),
        "CLOSE" to listOf("закрыть", "close"),
        "ON_MAP" to listOf("на карте", "выбрать на карте"),
    )

    fun encode(snapshot: ScreenSnapshot, assistant: ScreenSnapshot? = null): String {
        // The diagnostic callback is intentionally silent: even package names stay local.
        val safety = ScreenSafetyGate.assess(snapshot, diagnosticSink = {})
        val matches = JSONArray()
        if (safety.disposition == ScreenDisposition.ALLOW) {
            val byIndex = snapshot.elements.associateBy { it.index }
            for (node in snapshot.elements) {
                if (node.eligibility(snapshot.viewportOrLegacy) != ScreenElementEligibility.Eligible) continue
                // Values and descriptions of editable controls are never inspected by this probe.
                val labels = if (node.editable) {
                    if (node.editableValueEmpty) listOf(node.hintText) else emptyList()
                } else listOf(node.text, node.contentDescription, node.hintText)
                val normalized = labels.map(::normalize).filter(String::isNotBlank)
                for ((token, phrases) in vocabulary) {
                    val exact = normalized.any { it in phrases }
                    val phrase = normalized.any { label -> phrases.any {
                        Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])").containsMatchIn(label)
                    } }
                    if (!exact && !phrase) continue
                    val action = if (node.clickable || node.editable) node else {
                        node.clickableAncestorIndex?.let(byIndex::get)?.takeIf {
                            it.clickable && it.eligibility(snapshot.viewportOrLegacy) == ScreenElementEligibility.Eligible
                        }
                    }
                    val bounds = (action ?: node).bounds
                    matches.put(JSONObject().put("index", node.index).put("token", token)
                        .put("match", if (exact) "EXACT" else "PHRASE")
                        .put("actionIndex", action?.index ?: JSONObject.NULL)
                        .put("clickable", action?.clickable ?: false).put("editable", node.editable)
                        .put("left", bounds.left).put("top", bounds.top)
                        .put("right", bounds.right).put("bottom", bounds.bottom))
                }
            }
        }
        if (safety.disposition == ScreenDisposition.ALLOW && assistant?.packageName == "com.screensaathi") {
            assistant.elements.filter {
                it.resourceId == "decision_primary_button" && it.text == "Начать" && it.clickable &&
                    !it.editable && it.eligibility(assistant.viewportOrLegacy) == ScreenElementEligibility.Eligible
            }.forEach {
                matches.put(JSONObject().put("token", "ASSISTANT_START").put("match", "EXACT")
                    .put("index", it.index).put("actionIndex", it.index).put("clickable", true).put("editable", false)
                    .put("left", it.bounds.left).put("top", it.bounds.top)
                    .put("right", it.bounds.right).put("bottom", it.bounds.bottom))
            }
        }
        val assistantHandoff = assistant?.packageName == "com.screensaathi" && assistant.elements.any {
            it.resourceId == "instruction_text" && it.text in HANDOFF_TEXTS && !it.editable &&
                it.eligibility(assistant.viewportOrLegacy) == ScreenElementEligibility.Eligible
        }
        return JSONObject().put("assistantState", if (assistantHandoff) "SAFE_HANDOFF" else "NONE").put("elements", snapshot.elements.size).put("settled", snapshot.settled)
            .put("disposition", safety.disposition.name).put("risk", safety.category.name)
            .put("matches", matches).toString()
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).trim()
        .trim('?', '!', '.', ':', '…').trim()
}
