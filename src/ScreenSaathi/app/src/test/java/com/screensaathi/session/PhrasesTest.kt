package com.screensaathi.session

import com.screensaathi.sarvam.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {

    @Test
    fun `every assistant phrase is authored in Russian`() {
        assertEquals(setOf("ru-RU"), Phrases.AUTHORED)
        for (key in Phrases.Key.values()) {
            val phrase = Phrases.get(key, "ru-RU")
            assertEquals("$key language", Language.APP_DEFAULT, phrase.language)
            assertTrue("$key is blank", phrase.text.isNotBlank())
        }
    }

    @Test
    fun `foreign or unknown language requests cannot escape Russian surface`() {
        val russian = Phrases.get(Phrases.Key.LISTENING, "ru-RU")
        for (requested in listOf("en-IN", "hi-IN", "ta-IN", "klingon", "")) {
            assertEquals(russian, Phrases.get(Phrases.Key.LISTENING, requested))
        }
    }

    @Test
    fun `tree limitation explains that the UI tree has no description`() {
        val phrase = Phrases.get(Phrases.Key.CIRCLE_NEEDS_VISION, "ru-RU")
        assertTrue(phrase.text.contains("UI-дерево"))
        assertTrue(!phrase.text.contains("скриншот", ignoreCase = true))
    }
}
