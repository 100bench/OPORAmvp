package com.screensaathi.sarvam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageTest {

    @Test
    fun `Russian is the only supported MVP language and the default`() {
        assertEquals("ru-RU", Language.APP_DEFAULT)
        assertEquals(Language.APP_DEFAULT, Language.DEFAULT)
        assertEquals(linkedSetOf("ru-RU"), Language.SUPPORTED)
    }

    @Test
    fun `all incoming language labels normalize to Russian`() {
        for (code in listOf<String?>(null, "", "ru", "ru_RU", "en-IN", "hi-IN", "klingon")) {
            assertEquals("failed for $code", "ru-RU", Language.normalize(code))
        }
    }

    @Test
    fun `only the exact Russian locale is supported externally`() {
        assertTrue(Language.isSupported("ru-RU"))
        assertTrue(Language.isSupported(" RU-ru "))
        assertFalse(Language.isSupported("ru"))
        assertFalse(Language.isSupported("en-IN"))
        assertFalse(Language.isSupported(null))
    }

    @Test
    fun `speech reconciliation cannot switch the user interface away from Russian`() {
        assertEquals("ru-RU", Language.reconcile("Нажмите кнопку.", "ru-RU"))
        assertEquals("ru-RU", Language.reconcile("Tap the button.", "en-IN"))
        assertEquals("ru-RU", Language.reconcile("123…", "hi-IN"))
    }

    @Test
    fun `Russian prose validation rejects foreign only model output`() {
        assertTrue(Language.isRussianText("Нажмите Yandex Go.", listOf("Yandex Go")))
        assertTrue(Language.isRussianText("123…"))
        assertFalse(Language.isRussianText("Tap the button."))
        assertFalse(Language.isRussianText("Click «Настройки» to continue", listOf("Настройки")))
        assertFalse(Language.isRussianText("Нажмите Settings"))
        assertFalse(Language.isRussianText("इस बॉक्स में रकम भरिए।"))
    }

    @Test
    fun `the only native language name is Russian`() {
        assertEquals("Русский", Language.nativeName("ru-RU"))
        assertEquals("Русский", Language.nativeName("en-IN"))
    }
}
