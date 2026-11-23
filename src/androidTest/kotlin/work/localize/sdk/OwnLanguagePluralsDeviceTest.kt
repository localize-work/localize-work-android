package work.localize.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which `plurals` resources count as written in the user's language, where
 * the device counts by the plural rules of its own Android version.
 *
 * `values/` is English (`one`, `other`). Each language folder has `p_items`
 * and `p_both_translated` with all six forms, every form's text naming the
 * language and the form, so a wrong form shows as such.
 */
@RunWith(AndroidJUnit4::class)
class OwnLanguagePluralsDeviceTest {
    private val bundled = AndroidBundledStrings(Device.context)

    private val numbers = (0..25).toList() + listOf(30, 40, 99, 100, 101, 102, 103, 111, 120, 1000, 1_000_000)

    /** The form the device itself gives [number]; the texts of the test resources are built from it. */
    private fun form(language: String, number: Int) = Device.pluralForm(language, number)

    /** Precondition of everything below: Android itself reads the language's folder for a user of that language. */
    @Test
    fun androidReadsEveryLanguageFolder() {
        val m = Mismatches()
        for (language in LANGUAGES) m.expect("what Android shows a $language user for s_lang", language, Device.androidString(language, "s_lang"))
        m.assertNone("Android's own choice of folder")
    }

    @Test
    fun aTranslatedPluralIsFoundInTheFormTheDeviceCounts() {
        val m = Mismatches()
        for (language in LANGUAGES) {
            val forms = LinkedHashSet<String>()
            for (number in numbers) {
                val form = form(language, number)
                forms.add(form)
                m.expect("$language p_items at $number", "$language:$form %1\$d", bundled.findIn(language, "p_items", number.toLong()))
            }
            Report.line("plurals", "$language: forms the device used: ${forms.joinToString(" ")}; probes ${BundledPlurals.probes(language).joinToString(",")}")
        }
        m.assertNone("a plural translated in the language's folder")
    }

    @Test
    fun aPluralInValuesAloneIsNotInTheLanguage() {
        val m = Mismatches()
        for (language in LANGUAGES) {
            for (number in numbers) {
                m.expect("$language p_default_only at $number", null, bundled.findIn(language, "p_default_only", number.toLong()))
            }
        }
        m.assertNone("a plural the app has in values/ alone")
    }

    /** The same for every language the library has rules for, also those no folder is for. */
    @Test
    fun aPluralInValuesAloneIsNotInAnyLanguage() {
        val m = Mismatches()
        for (language in PluralRules.coveredLanguages().sorted()) {
            if (language == "und") continue
            for (number in listOf(0, 1, 2, 5, 11, 21, 100)) {
                m.expect("$language p_default_only at $number", null, bundled.findIn(language, "p_default_only", number.toLong()))
            }
        }
        m.assertNone("a plural the app has in values/ alone, in every language of the rule table")
    }

    /**
     * README: when `values/` has the forms a language asks for, an
     * untranslated plural passes for a translated one in Russian, Ukrainian,
     * Belarusian and Polish. `p_all_forms` is in `values/` alone and has all
     * six forms. In every other language it must still be told as untranslated.
     */
    @Test
    fun aPluralInValuesAloneThatHasEveryForm() {
        val m = Mismatches()
        val passing = ArrayList<String>()
        for (language in PluralRules.coveredLanguages().sorted()) {
            if (language == "und") continue
            val found = listOf(1, 2, 5).map { bundled.findIn(language, "p_all_forms", it.toLong()) }
            if (language in WITHOUT_GENERAL_NUMBER) {
                // The documented limit: it is taken for a translation.
                m.expect("$language p_all_forms (the documented limit)", listOf(form(language, 1), form(language, 2), form(language, 5)), found)
            } else {
                if (found.any { it != null }) passing.add("$language at 1,2,5 -> $found, probes ${BundledPlurals.probes(language).joinToString(",")}")
            }
        }
        for (line in passing) m.add("an untranslated plural passes for a translated one: $line")
        m.assertNone("a plural in values/ alone that has every form")
    }

    /** A `plurals` resource the app has in `values/` alone leaves the name to a `string` it has in the language. */
    @Test
    fun aPluralInValuesAloneLeavesTheNameToAStringInTheLanguage() {
        val m = Mismatches()
        for (language in LANGUAGES) {
            for (number in listOf(0, 1, 2, 5, 21)) {
                m.expect("$language p_string_translated at $number", "$language string: %1\$d", bundled.findIn(language, "p_string_translated", number.toLong()))
            }
            m.expect("$language p_string_translated without a count", "$language string: %1\$d", bundled.findIn(language, "p_string_translated", null))
        }
        // In a language the app has neither in, nothing is found; the app's own choice is the plural.
        m.expect("sv p_string_translated", null, bundled.findIn("sv", "p_string_translated", 5))
        m.expect("find p_string_translated", "%1\$d things", bundled.find("p_string_translated", 5))
        m.assertNone("a string of the same name as an untranslated plural")
    }

    @Test
    fun aTranslatedPluralComesBeforeAStringOfTheSameName() {
        val m = Mismatches()
        for (language in LANGUAGES) {
            for (number in listOf(0, 1, 2, 5, 21)) {
                m.expect(
                    "$language p_both_translated at $number",
                    "$language both:${form(language, number)} %1\$d",
                    bundled.findIn(language, "p_both_translated", number.toLong()),
                )
            }
            m.expect("$language p_both_translated without a count", "$language both string: %1\$d", bundled.findIn(language, "p_both_translated", null))
            // A plural is not read without a count.
            m.expect("$language p_items without a count", null, bundled.findIn(language, "p_items", null))
        }
        m.assertNone("a plural and a string of the same name")
    }

    /**
     * README: "A translation whose `other` form reads the same as the one in
     * `values/` is taken for the one in `values/`, whatever its other forms
     * say", and Russian, Ukrainian, Belarusian and Polish are looked at by
     * "a form `values/` lacks instead".
     */
    @Test
    fun aTranslationWithTheGeneralFormOfValues() {
        val m = Mismatches()
        m.expect("precondition: Android shows the Estonian form", "%1\$d minut", Device.resourcesFor("et").getQuantityText(Device.id("p_same_other", "plurals"), 1).toString())
        m.expect("et p_same_other at 1 (the documented limit)", null, bundled.findIn("et", "p_same_other", 1))
        m.expect("et p_same_other at 5 (the documented limit)", null, bundled.findIn("et", "p_same_other", 5))
        m.expect("ru p_same_other at 1", "%1\$d минута", bundled.findIn("ru", "p_same_other", 1))
        m.expect("ru p_same_other at 3", "%1\$d минуты", bundled.findIn("ru", "p_same_other", 3))
        m.expect("ru p_same_other at 5", "%1\$d минут", bundled.findIn("ru", "p_same_other", 5))
        m.assertNone("a translation whose general form is the one of values/")
    }

    /** Through `Localize.string`, with a count filled into the app's own text. */
    @Test
    fun throughThePublicApi() {
        val english = """{ "p_items_one": "release one {{count}}", "p_items_other": "release other {{count}}",
            "p_default_only_one": "release file {{count}}", "p_default_only_other": "release files {{count}}" }"""
        val transport = LocalizeTransport { url ->
            when {
                url.endsWith("/latest.json") -> LocalizeResponse(200, """{"version":"v1"}""")
                url.endsWith("/v1/en/strings.json") -> LocalizeResponse(200, english)
                else -> LocalizeResponse(404, "")
            }
        }
        val cache = File(Device.context.cacheDir, "lw-plurals-${System.nanoTime()}")
        val m = Mismatches()
        try {
            for (language in LANGUAGES) {
                Localize.init(
                    Device.context,
                    LocalizeConfig("plurals-demo") {
                        namespaces("strings")
                        locale(language)
                        fallbackLocale("en")
                        transport(transport)
                        cacheDirectory(cache)
                        refreshOnInit(false)
                    },
                )
                var result: RefreshResult? = null
                Localize.refresh { result = it }
                check(Device.waitFor { result != null }) { "the refresh did not report" }
                assertEquals("v1", Localize.version)
                for (number in listOf(0, 1, 2, 5, 21, 100)) {
                    m.expect("$language p_items with count $number", "$language:${form(language, number)} $number", Localize.string("p_items", count = number))
                    val release = if (number == 1) "release file 1" else "release files $number"
                    m.expect("$language p_default_only with count $number", release, Localize.string("p_default_only", count = number))
                }
            }
        } finally {
            cache.deleteRecursively()
        }
        m.assertNone("plurals through Localize.string")
    }

    internal companion object {
        /** The languages the test app has a folder for; `he` is in `values-iw`. */
        val LANGUAGES = listOf(
            "et", "de", "ru", "uk", "be", "pl", "cs", "lt", "lv", "ar", "he", "cy", "fil", "mr", "hi", "fr", "pt",
            "pt-PT", "es", "it", "ja", "zh", "sl", "ro", "ga",
        )

        /** The languages that give the general form to no whole number. */
        val WITHOUT_GENERAL_NUMBER = setOf("ru", "uk", "be", "pl")
    }
}
