package work.localize.sdk

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which `string` resources count as written in the user's language
 * ([AndroidBundledStrings.findIn]), on a real Android. The resources are the
 * ones in `src/androidTest/res`: English in `values/`.
 *
 * Every case is checked twice: against what Android itself shows a user of
 * that language (the library must agree with the system it runs on), and
 * against what the README says of this Android version.
 */
@RunWith(AndroidJUnit4::class)
class OwnLanguageStringsDeviceTest {
    private val bundled = AndroidBundledStrings(Device.context)

    /** Android 7.0 reads another region's folder and keeps scripts apart; 5 and 6 do neither. */
    private val modern = Device.sdk >= 24

    private val defaults = mapOf(
        "s_title" to "Title",
        "s_same" to "OK",
        "s_default_only" to "Default only",
        "s_pt_br" to "Region (default)",
        "s_pt" to "Language (default)",
        "s_sr_latn" to "Serbian Latin (default)",
        "s_sr" to "Serbian (default)",
        "s_zh_tw" to "Chinese TW (default)",
        "s_zh_hant" to "Chinese Hant (default)",
        "s_zh" to "Chinese (default)",
        "s_en_gb" to "Color",
        "s_night" to "Day",
        "s_ldrtl" to "Left to right",
        "s_land" to "Portrait",
        "s_he_iw" to "Hebrew iw (default)",
        "s_he_he" to "Hebrew he (default)",
        "s_id_in" to "Indonesian in (default)",
        "s_id_id" to "Indonesian id (default)",
        "s_fil" to "Filipino fil (default)",
        "s_tl" to "Filipino tl (default)",
        "s_lang" to "default",
    )

    /**
     * One case. [expected] is the text the README, or Android's own rules,
     * promise a [user] for [name] on this version: `null` for "not in the
     * user's language". Pass [NOT_STATED] where neither says.
     */
    private fun Mismatches.case(user: String, name: String, expected: String?) {
        val android = Device.androidString(user, name)
        val library = bundled.findIn(user, name, null)
        Report.line("strings", "$user $name: Android shows <$android>, findIn gives <$library>")
        expect("$user $name: findIn against what Android itself shows", android.takeIf { it != defaults.getValue(name) }, library)
        if (expected !== NOT_STATED) expect("$user $name: findIn against the documented behaviour", expected, library)
    }

    @Test
    fun aStringInTheLanguagesFolderIsFound() {
        val m = Mismatches()
        m.case("et", "s_title", "Pealkiri")
        m.case("et-EE", "s_title", "Pealkiri")
        m.case("et", "s_lang", "et")
        m.case("ru", "s_lang", "ru")
        m.assertNone("a string translated in the language's folder")
    }

    @Test
    fun aStringInValuesAloneIsNotInTheLanguage() {
        val m = Mismatches()
        m.case("et", "s_default_only", null)
        m.case("fr", "s_title", null)
        m.case("en", "s_title", null)
        m.case("en-US", "s_title", null)
        m.case("sv", "s_lang", null)
        m.assertNone("a string the app has in values/ alone")
        assertNull(bundled.findIn("et", "s_missing", null))
        assertNull(bundled.findIn("und", "s_title", null))
    }

    /** README: a text that reads the same in both folders is not told apart. */
    @Test
    fun theSameTextInBothFoldersIsNotToldApart() {
        assertEquals("OK", Device.androidString("et", "s_same"))
        assertNull(bundled.findIn("et", "s_same", null))
    }

    /**
     * README: "`pt-BR` reads `values-pt-rBR`, then `values-pt`. On Android 7.0
     * and later it also reads another region's folder of the language, so
     * `pt-PT` reads `values-pt-rBR` when that is the only Portuguese the app
     * has [...] Android 5 and 6 do neither."
     */
    @Test
    fun anotherRegionsFolderIsReadFromAndroid7On() {
        val m = Mismatches()
        m.case("pt-BR", "s_pt_br", "Região (pt-BR)")
        m.case("pt-BR", "s_pt", "Idioma (pt)")
        m.case("pt-PT", "s_pt", "Idioma (pt)")
        m.case("pt-PT", "s_pt_br", if (modern) "Região (pt-BR)" else null)
        m.case("pt-AO", "s_pt_br", if (modern) "Região (pt-BR)" else null)
        // The README does not say what a user without a region gets.
        m.case("pt", "s_pt_br", NOT_STATED)
        // Nor this: Android 7 and later keep a US English user on values/.
        m.case("en-GB", "s_en_gb", "Colour")
        m.case("en-AU", "s_en_gb", NOT_STATED)
        m.case("en-US", "s_en_gb", NOT_STATED)
        m.case("en", "s_en_gb", NOT_STATED)
        m.assertNone("a folder of another region")
    }

    /**
     * README: Android 7.0 and later "keeps the scripts of a language apart.
     * Android 5 and 6 do neither [...] they take a folder named for the
     * language, such as `values-zh`, for every way of writing it: a
     * `zh-Hant-HK` user is shown its Simplified Chinese text there."
     */
    @Test
    fun scriptsAreKeptApartFromAndroid7On() {
        val m = Mismatches()
        // Serbian is Cyrillic unless the tag says otherwise.
        m.case("sr", "s_sr", "Српски (sr)")
        m.case("sr-Cyrl", "s_sr", "Српски (sr)")
        m.case("sr-Latn", "s_sr_latn", "Srpski (sr-Latn)")
        m.case("sr-Latn-RS", "s_sr_latn", "Srpski (sr-Latn)")
        m.case("sr", "s_sr_latn", if (modern) null else "Srpski (sr-Latn)")
        m.case("sr-Cyrl", "s_sr_latn", if (modern) null else "Srpski (sr-Latn)")
        m.case("sr-RS", "s_sr_latn", if (modern) null else "Srpski (sr-Latn)")
        m.case("sr-Latn", "s_sr", if (modern) null else "Српски (sr)")

        m.case("zh", "s_zh", "简体 (zh)")
        m.case("zh-CN", "s_zh", "简体 (zh)")
        m.case("zh-Hans", "s_zh", "简体 (zh)")
        m.case("zh-TW", "s_zh_tw", "繁體 (zh-TW)")
        m.case("zh-Hant-TW", "s_zh_tw", "繁體 (zh-TW)")
        m.case("zh-TW", "s_zh_hant", "繁體 (zh-Hant)")
        m.case("zh-Hant", "s_zh_hant", "繁體 (zh-Hant)")
        m.case("zh-Hant-HK", "s_zh_hant", "繁體 (zh-Hant)")
        // A Simplified Chinese user and the Traditional Chinese folders.
        m.case("zh-CN", "s_zh_tw", null)
        m.case("zh-CN", "s_zh_hant", if (modern) null else "繁體 (zh-Hant)")
        m.case("zh", "s_zh_hant", if (modern) null else "繁體 (zh-Hant)")
        // A Traditional Chinese user and the folders of the other kind.
        m.case("zh-Hant-HK", "s_zh", if (modern) null else "简体 (zh)")
        m.case("zh-TW", "s_zh", if (modern) null else "简体 (zh)")
        m.case("zh-HK", "s_zh_tw", if (modern) "繁體 (zh-TW)" else null)
        m.case("zh-Hant-HK", "s_zh_tw", if (modern) "繁體 (zh-TW)" else null)
        m.assertNone("folders named for a script or a region of another script")
    }

    /**
     * Codes Android renamed: `iw`, `in` and `tl` are the folder names older
     * tools and devices know for Hebrew, Indonesian and Filipino. The library
     * is asked with the new code and with the old one. For Hebrew and
     * Indonesian the README says which folder is read: the one with the old
     * code, on every version. Of Filipino it says nothing; those cases only
     * have to agree with what Android itself shows, and the log says what
     * that is on this version.
     */
    @Test
    fun renamedLanguageCodes() {
        val m = Mismatches()
        for (user in listOf("he", "iw")) {
            m.case(user, "s_he_iw", "עברית (iw)")
            m.case(user, "s_he_he", null)
        }
        for (user in listOf("id", "in")) {
            m.case(user, "s_id_in", "Indonesia (in)")
            m.case(user, "s_id_id", null)
        }
        for (user in listOf("fil", "tl")) {
            m.case(user, "s_fil", NOT_STATED)
            m.case(user, "s_tl", NOT_STATED)
        }
        m.assertNone("renamed language codes")
    }

    /** A text for the dark theme is not a text in another language, also when the device is in night mode. */
    @Test
    fun aTextForNightModeIsNotATranslation() {
        assertNull(bundled.findIn("et", "s_night", null))
        assertEquals("Pealkiri", bundled.findIn("et", "s_title", null))
        val m = Mismatches()
        Device.inNightMode { how ->
            Report.line("strings", "night mode entered with $how")
            // The reader made before the switch, and one made in night mode.
            for ((which, reader) in listOf("earlier" to bundled, "new" to AndroidBundledStrings(Device.context))) {
                m.expect("precondition: Android shows the night text ($which)", "Night", Device.androidString("et", "s_night"))
                m.expect("$which reader, et s_night in night mode", null, reader.findIn("et", "s_night", null))
                m.expect("$which reader, fr s_night in night mode", null, reader.findIn("fr", "s_night", null))
                // values-et-night is in the user's language.
                m.expect("$which reader, et s_title in night mode", "Pealkiri (öö)", reader.findIn("et", "s_title", null))
                m.expect("$which reader, fr s_title in night mode", null, reader.findIn("fr", "s_title", null))
                m.expect("$which reader, find s_night in night mode", "Night", reader.find("s_night", null))
            }
        }
        m.expect("et s_title after night mode", "Pealkiri", bundled.findIn("et", "s_title", null))
        m.expect("et s_night after night mode", null, bundled.findIn("et", "s_night", null))
        m.assertNone("texts for night mode")
    }

    /**
     * Nor is a text for a wide screen, with the screen really wider than
     * tall. A text the app has in the language for a wide screen is the one
     * found there, also by a reader that was first asked in portrait, and
     * the portrait one again once the screen is back.
     */
    @Test
    fun aTextForLandscapeIsNotATranslation() {
        assertNull(bundled.findIn("et", "s_land", null))
        assertEquals("Püsti", bundled.findIn("et", "s_turn", null))
        val m = Mismatches()
        Device.inLandscape {
            for ((which, reader) in listOf("earlier" to bundled, "new" to AndroidBundledStrings(Device.context))) {
                m.expect("precondition: Android shows the landscape text ($which)", "Landscape", Device.androidString("et", "s_land"))
                m.expect("$which reader, et s_land in landscape", null, reader.findIn("et", "s_land", null))
                m.expect("$which reader, et s_title in landscape", "Pealkiri", reader.findIn("et", "s_title", null))
                m.expect("$which reader, find s_land in landscape", "Landscape", reader.find("s_land", null))
                // values-et-land is in the user's language.
                m.expect("$which reader, et s_turn in landscape", "Pikali", reader.findIn("et", "s_turn", null))
                m.expect("$which reader, fr s_turn in landscape", null, reader.findIn("fr", "s_turn", null))
            }
        }
        m.expect("et s_turn after landscape", "Püsti", bundled.findIn("et", "s_turn", null))
        m.expect("et s_land after landscape", null, bundled.findIn("et", "s_land", null))
        m.assertNone("texts for landscape")
    }

    /**
     * The two sets of resources a text is compared in are not given a new
     * configuration in one step. A lookup on another thread, while the screen
     * turns, can read one of them before the turn and one after: the
     * landscape text then differs from the portrait one and is taken for a
     * translation. Here one thread keeps asking while the screen turns six
     * times; every answer should be "not in the language".
     */
    @Test
    fun aLookupWhileTheScreenTurns() {
        assertNull(bundled.findIn("et", "s_land", null))
        val stop = AtomicBoolean()
        val asked = AtomicInteger()
        val wrong = AtomicInteger()
        val reader = Thread {
            while (!stop.get()) {
                asked.incrementAndGet()
                if (bundled.findIn("et", "s_land", null) != null) wrong.incrementAndGet()
            }
        }
        reader.start()
        try {
            repeat(3) {
                Device.inLandscape { Thread.sleep(300) }
                Thread.sleep(300)
            }
        } finally {
            stop.set(true)
            reader.join(10_000)
        }
        Report.line("strings", "while the screen turned six times: ${wrong.get()} of ${asked.get()} lookups took the text of the other orientation for a translation")
        assertEquals("lookups that took the other orientation's text for a translation, of ${asked.get()}", 0, wrong.get())
    }

    /** A user whose language is written right to left reads `values-ldrtl`; that is not their language. */
    @Test
    fun aTextForRightToLeftLayoutIsNotATranslation() {
        val m = Mismatches()
        for (user in listOf("ar", "he", "fa", "ur", "ar-EG")) {
            val shown = Device.androidString(user, "s_ldrtl")
            Report.line("strings", "$user s_ldrtl: Android shows <$shown>")
            m.expect("precondition: Android shows a $user user the right-to-left text", "Right to left", shown)
            m.expect("$user s_ldrtl", null, bundled.findIn(user, "s_ldrtl", null))
            m.expect("$user s_title", null, bundled.findIn(user, "s_title", null))
        }
        // A text that is in the language is found all the same.
        m.expect("ar s_lang", "ar", bundled.findIn("ar", "s_lang", null))
        m.expect("he s_lang", "he", bundled.findIn("he", "s_lang", null))
        m.expect("et s_ldrtl", null, bundled.findIn("et", "s_ldrtl", null))
        m.assertNone("texts for right-to-left layout")
    }

    /**
     * The app itself laid out right to left: its language set to Arabic
     * through Android 13's per-app languages. The user the library is asked
     * for may be another one.
     */
    @Test
    fun theAppItselfRightToLeft() {
        assumeTrue("per-app languages are Android 13 and later", Device.sdk >= 33)
        val context = Device.context
        val locales = context.getSystemService(LocaleManager::class.java)
        val isRtl = { context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL }
        val m = Mismatches()
        try {
            locales.applicationLocales = LocaleList.forLanguageTags("ar")
            check(Device.waitFor { isRtl() }) { "the app's language could not be set to Arabic" }
            for ((which, reader) in listOf("earlier" to bundled, "new" to AndroidBundledStrings(context))) {
                m.expect("$which: the app reads the right-to-left text", "Right to left", reader.find("s_ldrtl", null))
                m.expect("$which: the app reads its Arabic", "ar", reader.find("s_lang", null))
                m.expect("$which: ar s_ldrtl", null, reader.findIn("ar", "s_ldrtl", null))
                m.expect("$which: ar s_lang", "ar", reader.findIn("ar", "s_lang", null))
                m.expect("$which: et s_ldrtl", null, reader.findIn("et", "s_ldrtl", null))
                m.expect("$which: et s_title", "Pealkiri", reader.findIn("et", "s_title", null))
                m.expect("$which: fr s_title", null, reader.findIn("fr", "s_title", null))
            }
        } finally {
            locales.applicationLocales = LocaleList.getEmptyLocaleList()
            Device.waitFor { !isRtl() }
        }
        m.assertNone("the app laid out right to left")
    }

    /** The same through `Localize.string`: the app's own text in the user's language comes before the release's fallback language. */
    @Test
    fun throughThePublicApi() {
        val english = """{ "s_title": "Release title", "s_default_only": "Release default only",
            "s_same": "Release same", "s_pt_br": "Release region", "s_night": "Release night" }"""
        val transport = LocalizeTransport { url ->
            when {
                url.endsWith("/latest.json") -> LocalizeResponse(200, """{"version":"v1"}""")
                url.endsWith("/v1/en/strings.json") -> LocalizeResponse(200, english)
                else -> LocalizeResponse(404, "")
            }
        }
        val cache = File(Device.context.cacheDir, "lw-strings-${System.nanoTime()}")
        fun start(locale: String) {
            Localize.init(
                Device.context,
                LocalizeConfig("strings-demo") {
                    namespaces("strings")
                    locale(locale)
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
        }
        try {
            start("et")
            assertEquals("Pealkiri", Localize.string("s_title"))
            assertEquals("Release default only", Localize.string("s_default_only"))
            // Not told apart from values/, so the release's fallback language is shown.
            assertEquals("Release same", Localize.string("s_same"))
            assertEquals("Release night", Localize.string("s_night"))
            assertEquals("et", Localize.string("s_lang"))

            start("pt-PT")
            assertEquals(if (modern) "Região (pt-BR)" else "Release region", Localize.string("s_pt_br"))
            assertEquals("Idioma (pt)", Localize.string("s_pt"))

            start("fr")
            assertEquals("Release title", Localize.string("s_title"))
            assertEquals("fr", Localize.string("s_lang"))
        } finally {
            cache.deleteRecursively()
        }
    }

    private companion object {
        /** Marks a case neither the README nor the code promises anything about. */
        val NOT_STATED: String = String(charArrayOf('?'))
    }
}
