package work.localize.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library's own plural rules (CLDR 47) beside the rules of the device.
 *
 * The device's form of a number is read the way an app's `plurals` resource
 * is ([Device.pluralForm]), which works on every Android version, and from
 * Android 7.0 on also from `android.icu.text.PluralRules`.
 */
@RunWith(AndroidJUnit4::class)
class PluralRulesDeviceTest {
    /** Every language of the rule table but `und`, which is no language a user has. */
    private val languages = PluralRules.coveredLanguages().filter { it != "und" }.sorted()

    private fun icuForm(language: String, number: Int): String? =
        if (Device.sdk >= 24) android.icu.text.PluralRules.forLocale(Locale.forLanguageTag(language)).select(number.toDouble()) else null

    /**
     * What [BundledPlurals.probes] takes for granted: the numbers it picks as
     * "general in this language" are general on this device too. For the four
     * languages without such a number, that the device gives each number the
     * form the library expects.
     */
    @Test
    fun theProbeNumbersAreWhatTheLibraryTakesThemFor() {
        val m = Mismatches()
        for (language in languages) {
            for (probe in BundledPlurals.probes(language)) {
                val expected = PluralRules.category(language, probe.toLong())
                val device = Device.pluralForm(language, probe)
                if (device != expected) m.add("$language at $probe: the library counts on <$expected>, a plurals resource on this device gives <$device>")
                val icu = icuForm(language, probe)
                if (icu != null && icu != expected) m.add("$language at $probe: the library counts on <$expected>, android.icu gives <$icu>")
            }
        }
        m.assertNone("the numbers plurals resources are compared at")
    }

    /** A plurals resource is read with the same rules `android.icu` gives; if not, the two columns above mean different things. */
    @Test
    fun aPluralsResourceCountsAsAndroidIcuDoes() {
        if (Device.sdk < 24) {
            Report.line("rules", "no android.icu before Android 7.0; the device's forms are read from a plurals resource only")
            return
        }
        val m = Mismatches()
        for (language in languages) {
            val differing = NUMBERS.filter { Device.pluralForm(language, it) != icuForm(language, it) }
            if (differing.isNotEmpty()) {
                m.add("$language: resource and android.icu differ at ${ranges(differing)}, e.g. ${differing[0]}: " +
                    "<${Device.pluralForm(language, differing[0])}> and <${icuForm(language, differing[0])}>")
            }
        }
        m.assertNone("plurals resources against android.icu")
    }

    /**
     * For information, never a failure: where the library's form of a number
     * differs from the device's. The library uses its own rules for the
     * release's texts and the device's for the app's bundled plurals.
     */
    @Test
    fun whereTheLibraryAndTheDeviceCountDifferently() {
        var differing = 0
        for (language in languages) {
            val groups = LinkedHashMap<Pair<String, String>, MutableList<Int>>()
            for (number in NUMBERS) {
                val library = PluralRules.category(language, number.toLong())
                val device = Device.pluralForm(language, number)
                if (library != device) groups.getOrPut(device to library) { ArrayList() }.add(number)
            }
            if (groups.isEmpty()) continue
            differing++
            val text = groups.entries.joinToString("; ") { (forms, numbers) ->
                "device ${forms.first} / library ${forms.second} at ${ranges(numbers)}"
            }
            Report.line("rules-diff", "$language: $text")
        }
        Report.line("rules-diff", "TOTAL $differing of ${languages.size} languages differ on 0..200, 1000, 1000000")
    }

    private fun ranges(numbers: List<Int>): String {
        val out = ArrayList<String>()
        var start = numbers[0]
        var previous = start
        for (number in numbers.drop(1) + listOf(Int.MIN_VALUE)) {
            if (number == previous + 1) {
                previous = number
                continue
            }
            out.add(if (start == previous) "$start" else "$start-$previous")
            start = number
            previous = number
        }
        return if (out.size > 14) out.take(14).joinToString(",") + ",… (${numbers.size} numbers)" else out.joinToString(",")
    }

    private companion object {
        val NUMBERS: List<Int> = (0..200) + listOf(1000, 1_000_000)
    }
}
