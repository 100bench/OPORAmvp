package com.screensaathi.task

import com.screensaathi.sarvam.Language
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy task assets cannot escape the Russian-only MVP surface. */
class RussianTaskTest {

    private val json = """
        {
          "version": 1, "id": "pay_bill", "title": "Оплата счёта",
          "utterances": ["помоги оплатить счёт"],
          "steps": [
            { "id": "amount", "resource_id": "amount_field",
              "instruction": "Enter the bill amount in this box.",
              "instructions": {
                "ru-RU": "Введите сумму счёта в это поле.",
                "hi-IN": "इस बॉक्स में बिल की रकम भरिए।",
                "fr-FR": "Entrez le montant."
              } }
          ]
        }
    """.trimIndent()

    private fun step() = TaskRepository.parse(JSONObject(json)).steps[0]

    @Test
    fun `Russian task wording is used regardless of an incoming language label`() {
        for (requested in listOf("ru-RU", "en-IN", "hi-IN", "klingon")) {
            val spoken = step().spokenFor(requested)
            assertEquals(Language.APP_DEFAULT, spoken.language)
            assertEquals("Введите сумму счёта в это поле.", spoken.text)
        }
    }

    @Test
    fun `foreign instruction variants are dropped at parse time`() {
        assertEquals(setOf("ru-RU"), step().instructions.keys)
        assertNotNull(step().instructions["ru-RU"])
        assertNull(step().instructions["hi-IN"])
        assertNull(step().instructions["fr-FR"])
    }

    @Test
    fun `legacy foreign base text without Russian wording degrades locally in Russian`() {
        val j = """
            { "version": 1, "id": "t", "title": "T", "steps":
              [ { "id": "a", "resource_id": "a_field", "instruction": "go" } ] }
        """.trimIndent()
        val spoken = TaskRepository.parse(JSONObject(j)).steps[0].spokenFor("en-IN")

        assertEquals(Language.APP_DEFAULT, spoken.language)
        assertEquals("Следуйте подсказке на экране.", spoken.text)
    }

    @Test
    fun `Russian base text works without an instructions block`() {
        val j = """
            { "version": 1, "id": "t", "title": "T", "steps":
              [ { "id": "a", "resource_id": "a_field", "instruction": "Нажмите кнопку." } ] }
        """.trimIndent()
        val step = TaskRepository.parse(JSONObject(j)).steps[0]

        assertTrue(step.instructions.isEmpty())
        assertEquals("Нажмите кнопку.", step.spokenFor("ru-RU").text)
    }

    @Test
    fun `a Cyrillic Russian utterance still matches the task`() {
        val repo = TaskRepository.of(listOf(TaskRepository.parse(JSONObject(json))))
        assertEquals("pay_bill", repo.matchByUtterance("помоги оплатить счёт")?.id)
    }

    @Test
    fun `an unrelated Russian transcript matches nothing`() {
        val repo = TaskRepository.of(listOf(TaskRepository.parse(JSONObject(json))))
        assertNull(repo.matchByUtterance("какая завтра погода"))
    }
}
