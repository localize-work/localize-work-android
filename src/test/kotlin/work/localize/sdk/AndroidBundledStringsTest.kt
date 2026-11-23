package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The reader of the app's own resources, against the resources in
 * `src/test/res`: English in `values/`, Estonian, Russian and Brazilian
 * Portuguese beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class AndroidBundledStringsTest {
    private val bundled = AndroidBundledStrings(RuntimeEnvironment.getApplication())

    @Test
    fun aTextTheAppHasInTheLanguageIsFound() {
        assertEquals("Pealkiri", bundled.findIn("et", "bundled_title", null))
        assertEquals("Pealkiri", bundled.findIn("et-EE", "bundled_title", null))
    }

    @Test
    fun aTextFromTheDefaultFolderIsNotInTheLanguage() {
        assertNull(bundled.findIn("et", "bundled_default_only", null))
        assertNull(bundled.findIn("fr", "bundled_title", null))
        assertNull(bundled.findIn("en", "bundled_title", null))
        assertNull(bundled.findIn("et", "bundled_missing", null))
        assertNull(bundled.findIn("und", "bundled_title", null))
        // A text that reads the same in both is not told apart, and nothing is lost.
        assertNull(bundled.findIn("et", "bundled_same", null))
    }

    @Test
    fun aRegionReadsItsOwnFolder() {
        assertEquals("Título", bundled.findIn("pt-BR", "bundled_title", null))
    }

    /** Android reads another region's folder from 7.0 on. What Robolectric does for Android 6 shows nothing of a device. */
    @Test
    @Config(sdk = [34])
    fun aRegionReadsTheLanguagesOtherRegion() {
        assertEquals("Título", bundled.findIn("pt-PT", "bundled_title", null))
    }

    @Test
    fun aPluralTheAppHasInTheLanguageIsFound() {
        assertEquals("%1\$d asi", bundled.findIn("et", "bundled_items", 1))
        assertEquals("%1\$d asja", bundled.findIn("et", "bundled_items", 5))
    }

    /** Not on Android 6, where Robolectric counts every language the way English does. */
    @Test
    @Config(sdk = [34])
    fun aPluralTheAppHasInTheLanguageCountsByItsRule() {
        assertEquals("%1\$d предмет", bundled.findIn("ru", "bundled_items", 21))
        assertEquals("%1\$d предмета", bundled.findIn("ru", "bundled_items", 3))
        assertEquals("%1\$d предметов", bundled.findIn("ru", "bundled_items", 5))
    }

    @Test
    fun aPluralFromTheDefaultFolderIsNotInTheLanguage() {
        // French and Russian choose "one" for 1, which the default folder has too.
        assertNull(bundled.findIn("fr", "bundled_items", 1))
        assertNull(bundled.findIn("fr", "bundled_items", 5))
        assertNull(bundled.findIn("ru", "bundled_untranslated", 1))
        assertNull(bundled.findIn("ru", "bundled_untranslated", 5))
        assertNull(bundled.findIn("et", "bundled_untranslated", 1))
        assertNull(bundled.findIn("ja", "bundled_items", 3))
    }

    /** Filipino gives the general form to few numbers: 4, 6, 9 and those ending in them. */
    @Test
    fun aPluralFromTheDefaultFolderIsToldByANumberTheLanguageCountsAsGeneral() {
        assertNull(bundled.findIn("fil", "bundled_items", 1))
        assertNull(bundled.findIn("fil", "bundled_items", 5))
        assertNull(bundled.findIn("fil", "bundled_items", 4))
    }

    /** Arabic is laid out the other way, which is not a language of the text. */
    /** A name can belong to a `plurals` and a `string` resource at once. */
    @Test
    fun aPluralFromTheDefaultFolderLeavesTheNameToAStringInTheLanguage() {
        assertEquals("Arv: %1\$d", bundled.findIn("et", "bundled_both", 5))
        assertEquals("Arv: %1\$d", bundled.findIn("et", "bundled_both", null))
        assertNull(bundled.findIn("fr", "bundled_both", 5))
        assertEquals("%1\$d things", bundled.find("bundled_both", 5))
    }

    @Test
    fun aTextForTheOtherLayoutDirectionIsNotInTheLanguage() {
        assertNull(bundled.findIn("ar", "bundled_title", null))
        assertNull(bundled.findIn("he", "bundled_title", null))
    }

    @Test
    fun theOtherQualifiersStayTheAppsOwn() {
        assertEquals("Pealkiri", bundled.findIn("et", "bundled_title", null))
        RuntimeEnvironment.setQualifiers("+night")
        assertEquals("Pealkiri (öö)", bundled.findIn("et", "bundled_title", null))
        assertEquals("Pealkiri (öö)", AndroidBundledStrings(RuntimeEnvironment.getApplication()).findIn("et", "bundled_title", null))
    }

    @Test
    fun theAppsOwnChoiceOfLanguageIsLeftAlone() {
        assertEquals("Title", bundled.find("bundled_title", null))
        assertEquals("%1\$d item", bundled.find("bundled_items", 1))
        assertEquals("%1\$d items", bundled.find("bundled_items", 2))
        assertNull(bundled.find("bundled_missing", null))
    }
}
