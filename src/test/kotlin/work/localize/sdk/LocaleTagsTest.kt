package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleTagsTest {
    @Test
    fun tagsAreWrittenTheWayTheProductWritesThem() {
        assertEquals("en", LocaleTags.canonicalize("en"))
        assertEquals("en", LocaleTags.canonicalize(" EN "))
        assertEquals("en-FI", LocaleTags.canonicalize("en-fi"))
        assertEquals("en-FI", LocaleTags.canonicalize("EN_FI"))
        assertEquals("zh-Hant-TW", LocaleTags.canonicalize("ZH-hANT-tw"))
        assertEquals("es-419", LocaleTags.canonicalize("es-419"))
        assertEquals("sr-Latn", LocaleTags.canonicalize("sr-latn"))
        assertEquals("ca-ES-valencia", LocaleTags.canonicalize("ca-es-VALENCIA"))
        assertEquals("fil", LocaleTags.canonicalize("FIL"))
        assertEquals("", LocaleTags.canonicalize(""))
        assertEquals("", LocaleTags.canonicalize("-_-"))
    }

    @Test
    fun extensionsADeviceAddsAreDropped() {
        assertEquals("en-US", LocaleTags.canonicalize("en-US-u-fw-mon-mu-celsius"))
        assertEquals("ar-EG", LocaleTags.canonicalize("ar-EG-u-nu-latn"))
        assertEquals("de", LocaleTags.canonicalize("de-x-private"))
    }

    @Test
    fun legacyLanguageCodesAreReplaced() {
        assertEquals("he-IL", LocaleTags.canonicalize("iw-IL"))
        assertEquals("id", LocaleTags.canonicalize("in"))
        assertEquals("yi", LocaleTags.canonicalize("ji"))
    }

    @Test
    fun theTurkishLocaleOfTheDeviceDoesNotChangeTheSpelling() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("fi-FI", LocaleTags.canonicalize("FI-fi"))
            assertEquals("id-ID", LocaleTags.canonicalize("ID-id"))
            assertEquals("one", PluralRules.category("FI", 1))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test
    fun theChainGoesFromTheLocaleToItsBaseLanguageToTheFallback() {
        assertEquals(listOf("en-FI", "en", "et"), LocaleTags.chain("en-FI", "et"))
        assertEquals(listOf("en-FI", "en"), LocaleTags.chain("en-FI", "en"))
        assertEquals(listOf("en-FI", "en"), LocaleTags.chain("en-FI", null))
        assertEquals(listOf("en"), LocaleTags.chain("en", "en"))
        assertEquals(listOf("et", "en"), LocaleTags.chain("et", "en"))
        assertEquals(listOf("zh-Hant-TW", "zh-Hant", "zh", "en"), LocaleTags.chain("zh-Hant-TW", "en"))
        assertEquals(listOf("et", "pt-BR", "pt"), LocaleTags.chain("et", "pt-BR"))
        assertEquals(listOf("en-FI", "en"), LocaleTags.chain("en_fi", "EN-fi"))
    }

    @Test
    fun onlyLettersDigitsAndSeparatorsMakeATag() {
        for (tag in listOf("en", "en-FI", "en_FI", "zh-Hant-TW", "es-419")) assertTrue(tag, LocaleTags.isWellFormed(tag))
        for (tag in listOf("", "-", "en-", "-en", "en--FI", "en/FI", "../en", "en FI", "en.FI", "é")) {
            assertFalse(tag, LocaleTags.isWellFormed(tag))
        }
    }
}
