package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PluralRulesTest {
    private fun assertCategory(expected: String, locale: String, vararg counts: Long) {
        for (count in counts) {
            assertEquals("$locale $count", expected, PluralRules.category(locale, count))
        }
    }

    private fun assertCategory(expected: String, locale: String, counts: IntRange) =
        assertCategory(expected, locale, *counts.map(Int::toLong).toLongArray())

    @Test
    fun germanicAndFinnicLanguagesSeparateOnlyOne() {
        for (locale in listOf("en", "de", "et", "fi", "sv", "nl", "da", "nb")) {
            assertCategory("one", locale, 1)
            assertCategory("other", locale, 0, 2, 3, 5, 10, 11, 21, 100, 101, 1_000_000)
        }
    }

    @Test
    fun spanishAndItalianHaveAFormForMillions() {
        for (locale in listOf("es", "it")) {
            assertCategory("one", locale, 1)
            assertCategory("other", locale, 0, 2, 5, 11, 21, 100, 999_999, 1_000_001, 1_500_000)
            assertCategory("many", locale, 1_000_000, 2_000_000, 10_000_000)
        }
    }

    @Test
    fun frenchCountsZeroAsOneAndHasAFormForMillions() {
        assertCategory("one", "fr", 0, 1)
        assertCategory("other", "fr", 2, 3, 10, 21, 100, 1_000_001)
        assertCategory("many", "fr", 1_000_000, 3_000_000)
    }

    @Test
    fun portugueseOfBrazilAndOfPortugalDiffer() {
        assertCategory("one", "pt", 0, 1)
        assertCategory("one", "pt-BR", 0, 1)
        assertCategory("many", "pt", 1_000_000)
        assertCategory("other", "pt-PT", 0)
        assertCategory("one", "pt-PT", 1)
        assertCategory("many", "pt-PT", 1_000_000)
    }

    @Test
    fun russianAndUkrainian() {
        for (locale in listOf("ru", "uk")) {
            assertCategory("one", locale, 1, 21, 31, 101, 1001)
            assertCategory("few", locale, 2..4)
            assertCategory("few", locale, 22, 23, 24, 102)
            assertCategory("many", locale, 5..20)
            assertCategory("many", locale, 11..14)
            assertCategory("many", locale, 0, 25, 100, 111, 112, 1_000_000)
        }
    }

    @Test
    fun polish() {
        assertCategory("one", "pl", 1)
        assertCategory("few", "pl", 2..4)
        assertCategory("few", "pl", 22, 23, 24, 102)
        assertCategory("many", "pl", 0, 5, 12, 13, 14, 21, 25, 100, 101, 112)
    }

    @Test
    fun czechAndSlovak() {
        for (locale in listOf("cs", "sk")) {
            assertCategory("one", locale, 1)
            assertCategory("few", locale, 2..4)
            assertCategory("other", locale, 0, 5, 11, 21, 22, 100)
        }
    }

    @Test
    fun lithuanian() {
        assertCategory("one", "lt", 1, 21, 31, 101)
        assertCategory("few", "lt", 2..9)
        assertCategory("few", "lt", 22, 29)
        assertCategory("other", "lt", 11..19)
        assertCategory("other", "lt", 0, 10, 20, 30, 111)
    }

    @Test
    fun latvian() {
        assertCategory("zero", "lv", 0, 10, 11, 12, 19, 20, 100, 111)
        assertCategory("one", "lv", 1, 21, 31, 101)
        assertCategory("other", "lv", 2, 5, 9, 22, 29)
    }

    @Test
    fun romanian() {
        assertCategory("one", "ro", 1)
        assertCategory("few", "ro", 0)
        assertCategory("few", "ro", 2..19)
        assertCategory("few", "ro", 101, 119)
        assertCategory("other", "ro", 20, 21, 100, 120)
    }

    @Test
    fun slovenian() {
        assertCategory("one", "sl", 1, 101)
        assertCategory("two", "sl", 2, 102)
        assertCategory("few", "sl", 3, 4, 103, 104)
        assertCategory("other", "sl", 0, 5, 10, 11, 100, 105)
    }

    @Test
    fun arabic() {
        assertCategory("zero", "ar", 0)
        assertCategory("one", "ar", 1)
        assertCategory("two", "ar", 2)
        assertCategory("few", "ar", 3..10)
        assertCategory("few", "ar", 103, 110)
        assertCategory("many", "ar", 11..99)
        assertCategory("many", "ar", 111, 199)
        assertCategory("other", "ar", 100, 101, 102, 200)
    }

    @Test
    fun hebrew() {
        assertCategory("one", "he", 1)
        assertCategory("two", "he", 2)
        assertCategory("other", "he", 0, 3, 10, 11, 20, 100)
        // The code older Android versions report for Hebrew.
        assertCategory("two", "iw", 2)
    }

    @Test
    fun languagesWithoutPluralForms() {
        for (locale in listOf("ja", "zh", "ko", "vi", "th", "id", "in", "zh-Hant-TW", "ms")) {
            assertCategory("other", locale, 0, 1, 2, 5, 11, 21, 100, 1_000_000)
        }
    }

    /**
     * The rules are those of CLDR 47, the release the product and the iOS SDK
     * use. Later releases changed these two languages; a project's files are
     * published with the forms CLDR 47 names, so those are the ones to ask for.
     */
    @Test
    fun vietnameseAndGalicianFollowTheReleaseTheProductPublishesWith() {
        assertCategory("other", "vi", 0, 1, 2, 5)
        assertCategory("one", "gl", 1)
        assertCategory("other", "gl", 0, 2, 1_000_000, 2_000_000)
    }

    @Test
    fun anUnknownLanguageUsesOneForExactlyOne() {
        for (locale in listOf("tlh", "xx-YY", "", "-", "zz")) {
            assertFalse(locale, PluralRules.covers(locale))
            assertCategory("one", locale, 1)
            assertCategory("other", locale, 0, 2, 11, 21, 1_000_000)
        }
    }

    @Test
    fun theLanguageIsTakenFromAnyFormOfTheTag() {
        assertCategory("few", "ru-RU", 3)
        assertCategory("few", "RU_ru", 3)
        assertCategory("few", "sr-Latn-RS", 3)
        assertCategory("other", "en-u-nu-arab", 3)
        assertCategory("one", "hi-Latn", 0)
    }

    @Test
    fun aNegativeCountUsesItsAbsoluteValue() {
        assertCategory("one", "en", -1)
        assertCategory("few", "ru", -3)
        assertCategory("other", "en", Long.MIN_VALUE)
    }

    /**
     * Every language CLDR has plural rules for, on 1,700 whole numbers each.
     * The expected categories are generated from the CLDR data by
     * tools/generate-plural-vectors.mjs.
     */
    @Test
    fun everyLanguageMatchesTheCldrData() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/plural-vectors.txt")) { "plural-vectors.txt is missing" }
            .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val numbers = lines.first().removePrefix("numbers:").trim().split(' ').map(String::toLong)
        val letters = mapOf('z' to "zero", 'o' to "one", 't' to "two", 'f' to "few", 'm' to "many", 'x' to "other")
        val cldrLocales = HashSet<String>()
        var checked = 0

        for (line in lines.drop(1)) {
            val locales = line.substringBefore(':').trim().split(' ')
            val vector = line.substringAfter(':').trim()
            assertEquals(line.take(40), numbers.size, vector.length)
            for (locale in locales) {
                cldrLocales.add(locale.lowercase())
                assertTrue("$locale has no rules in the SDK", PluralRules.covers(locale))
                for ((index, number) in numbers.withIndex()) {
                    val expected = letters.getValue(vector[index])
                    val actual = PluralRules.category(locale, number)
                    if (expected != actual) fail("$locale $number: CLDR says $expected, the SDK says $actual")
                    checked++
                }
            }
        }

        assertTrue("only $checked checks ran", checked > 300_000)
        // The table and CLDR list the same languages: nothing missing, nothing invented.
        assertEquals(cldrLocales, PluralRules.coveredLanguages())
    }
}
