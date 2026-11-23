package work.localize.sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BundledPluralsTest {
    @Test
    fun aLanguageIsAskedForNumbersItCountsAsGeneral() {
        assertArrayEquals(intArrayOf(2, 3), BundledPlurals.probes("en"))
        assertArrayEquals(intArrayOf(2, 3), BundledPlurals.probes("fr"))
        assertArrayEquals(intArrayOf(4, 6), BundledPlurals.probes("fil"))
        assertArrayEquals(intArrayOf(100, 101), BundledPlurals.probes("ar"))
        assertArrayEquals(intArrayOf(2, 3), BundledPlurals.probes("ja"))
        assertArrayEquals(intArrayOf(2, 3), BundledPlurals.probes("pt-PT"))
        assertArrayEquals(intArrayOf(2, 3), BundledPlurals.probes("pt-BR"))
    }

    /** Older Android versions count 0, and in places 1, differently than CLDR 47 does. */
    @Test
    fun theNumbersAreOnesEveryAndroidVersionCountsAlike() {
        // Marathi gave 0 the singular form before, and gives it the general one now.
        assertEquals(PluralRules.OTHER, PluralRules.category("mr", 0))
        assertArrayEquals(intArrayOf(2, 3), BundledPlurals.probes("mr"))
        for (language in PluralRules.coveredLanguages()) {
            val general = (2..200).any { PluralRules.category(language, it.toLong()) == PluralRules.OTHER }
            if (general) assertEquals(language, true, BundledPlurals.probes(language).all { it >= 2 })
        }
    }

    @Test
    fun aLanguageWithoutSuchANumberIsAskedForOneOfEachForm() {
        for (locale in listOf("ru", "uk", "be", "pl")) {
            val probes = BundledPlurals.probes(locale)
            assertEquals(
                locale,
                setOf(PluralRules.ONE, PluralRules.FEW, PluralRules.MANY),
                probes.mapTo(HashSet()) { PluralRules.category(locale, it.toLong()) },
            )
            assertEquals(locale, 3, probes.size)
        }
    }

    @Test
    fun onlyFourLanguagesHaveNoSuchNumber() {
        val without = PluralRules.coveredLanguages().filter { language ->
            BundledPlurals.probes(language).none { PluralRules.category(language, it.toLong()) == PluralRules.OTHER }
        }
        assertEquals(setOf("ru", "uk", "be", "pl"), without.toSet())
    }
}
