package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class BundledTextTest {
    @Test
    fun aKeyBecomesTheResourceNameTheAndroidExportWrites() {
        assertEquals("translation_nav_home", ResourceNames.forKey("translation", "nav.home"))
        assertEquals("checkout_title", ResourceNames.forKey("checkout", "title"))
        // A namespace called "strings" is Android's own file: no prefix.
        assertEquals("nav_home", ResourceNames.forKey("strings", "nav.home"))
        assertEquals("Strings_title", ResourceNames.forKey("Strings", "title"))
        // Everything but letters, digits and underscores becomes an underscore.
        assertEquals("app_a_b_c_d_e_f", ResourceNames.forKey("app", "a-b c/d:e.f"))
        assertEquals("my_app_items_count", ResourceNames.forKey("my-app", "items.count"))
        assertEquals("app_Keep_CASE_and_123", ResourceNames.forKey("app", "Keep_CASE_and_123"))
        assertEquals("app_caf__", ResourceNames.forKey("app", "café!"))
        // A character outside the basic plane is two UTF-16 units, as in the export.
        assertEquals("app_a__b", ResourceNames.forKey("app", "a😀b"))
        // A name may not start with a digit.
        assertEquals("_404_title", ResourceNames.forKey("strings", "404.title"))
        assertEquals("_2fa_code", ResourceNames.forKey("2fa", "code"))
        assertEquals("app_404", ResourceNames.forKey("app", "404"))
        assertEquals("app_", ResourceNames.forKey("app", ""))
        assertEquals("", ResourceNames.forKey("strings", ""))
    }

    @Test
    fun numberedConversionsTakeTheArgumentsInOrder() {
        assertEquals("Hello, Ann!", BundledText.format("Hello, %1\$s!", mapOf("name" to "Ann"), null))
        assertEquals(
            "Bob wrote to Ann",
            BundledText.format("%2\$s wrote to %1\$s", linkedMapOf("to" to "Ann", "from" to "Bob"), null),
        )
        assertEquals("Ann, meet Ann", BundledText.format("%1\$s, meet %1\$s", mapOf("name" to "Ann"), null))
    }

    @Test
    fun theCountOfAPluralTakesThePositionOfItsConversion() {
        assertEquals("3 items", BundledText.format("%1\$d items", emptyMap(), 3))
        assertEquals("3 items in Inbox", BundledText.format("%1\$d items in %2\$s", mapOf("folder" to "Inbox"), 3))
        assertEquals("Ann has 3 items", BundledText.format("%1\$s has %2\$d items", mapOf("name" to "Ann"), 3))
        assertEquals(
            "Ann has 3 items in Inbox",
            BundledText.format("%1\$s has %2\$d items in %3\$s", linkedMapOf("name" to "Ann", "folder" to "Inbox"), 3),
        )
        // The caller may also pass the count in its place among the arguments.
        assertEquals(
            "Ann has 3 items",
            BundledText.format("%1\$s has %2\$d items", linkedMapOf("name" to "Ann", "count" to 3), 3),
        )
    }

    @Test
    fun unnumberedConversionsAreTakenInTurn() {
        assertEquals("Ann and Bob", BundledText.format("%s and %s", linkedMapOf("a" to "Ann", "b" to "Bob"), null))
        assertEquals("5 new", BundledText.format("%d new", emptyMap(), 5))
    }

    @Test
    fun aDoubledPercentIsOneInAFormattedText() {
        assertEquals("100% of Ann", BundledText.format("100%% of %1\$s", mapOf("name" to "Ann"), null))
        assertEquals("100% done", BundledText.format("100%% done", mapOf("name" to "Ann"), null))
        assertEquals("3 at 50%", BundledText.format("%1\$d at 50%%", emptyMap(), 3))
    }

    @Test
    fun aTextTheAppDoesNotFormatIsLeftAlone() {
        assertEquals("100% done", BundledText.format("100% done", emptyMap(), null))
        assertEquals("50%% literal", BundledText.format("50%% literal", emptyMap(), null))
        assertEquals("Plain", BundledText.format("Plain", emptyMap(), null))
    }

    @Test
    fun aConversionWithoutAnArgumentIsLeftAsWritten() {
        assertEquals("Hello, %1\$s!", BundledText.format("Hello, %1\$s!", emptyMap(), null))
        assertEquals("Ann and %2\$s", BundledText.format("%1\$s and %2\$s", mapOf("a" to "Ann"), null))
        assertEquals("%0\$s %5d %x 100% %", BundledText.format("%0\$s %5d %x 100% %", mapOf("a" to "Ann"), null))
        assertEquals("%99999\$s", BundledText.format("%99999\$s", mapOf("a" to "Ann"), null))
    }

    @Test
    fun i18nextPlaceholdersInABundledTextAreFilledToo() {
        assertEquals("Hello, Ann!", BundledText.format("Hello, {{name}}!", mapOf("name" to "Ann"), null))
        assertEquals("3 items", BundledText.format("{{count}} items", emptyMap(), 3))
        assertEquals("Ann: %1\$s", BundledText.format("%1\$s: {{note}}", linkedMapOf("name" to "Ann", "note" to "%1\$s"), null))
    }
}
