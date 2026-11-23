package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LookupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun tree(json: String): NamespaceTree {
        @Suppress("UNCHECKED_CAST")
        return Json.parse(json) as NamespaceTree
    }

    @Test
    fun dotsWalkNestedObjects() {
        val file = tree("""{ "nav": { "home": "Home", "user": { "profile": "Profile" } }, "title": "Title" }""")

        assertEquals("Title", ResourceLookup.find(file, "title"))
        assertEquals("Home", ResourceLookup.find(file, "nav.home"))
        assertEquals("Profile", ResourceLookup.find(file, "nav.user.profile"))
        assertNull(ResourceLookup.find(file, "nav.missing"))
        assertNull(ResourceLookup.find(file, "missing"))
        assertNull(ResourceLookup.find(file, "title.deeper"))
        assertNull(ResourceLookup.find(file, ""))
    }

    @Test
    fun aGroupOfKeysIsNotAText() {
        val file = tree("""{ "nav": { "home": "Home" }, "count": 3, "on": true, "list": ["a"], "nothing": null }""")

        assertNull(ResourceLookup.find(file, "nav"))
        assertNull(ResourceLookup.find(file, "count"))
        assertNull(ResourceLookup.find(file, "on"))
        assertNull(ResourceLookup.find(file, "list"))
        assertNull(ResourceLookup.find(file, "list.0"))
        assertNull(ResourceLookup.find(file, "nothing"))
        assertNull(ResourceLookup.find(file, "nothing.deeper"))
    }

    @Test
    fun aDeeperKeyWrittenAtTheTopLevelUnderItsDottedNameIsFound() {
        // Keys "a" and "a.b": "a" keeps its place, "a.b" goes to the top level.
        val file = tree("""{ "a": "shallow", "a.b": "deep" }""")

        assertEquals("shallow", ResourceLookup.find(file, "a"))
        assertEquals("deep", ResourceLookup.find(file, "a.b"))
        assertNull(ResourceLookup.find(file, "a.c"))
    }

    @Test
    fun aDottedNameIsFoundInAFileThatLacksTheShallowText() {
        // "a" is a text in another locale, so this locale writes "a.b" flat too.
        val file = tree("""{ "a.b": "deep", "a.b.c": "deeper", "x": { "y.z": "nested flat" } }""")

        assertEquals("deep", ResourceLookup.find(file, "a.b"))
        assertEquals("deeper", ResourceLookup.find(file, "a.b.c"))
        assertEquals("nested flat", ResourceLookup.find(file, "x.y.z"))
        assertNull(ResourceLookup.find(file, "a"))
    }

    @Test
    fun anEmptyTextUnderATextIsFoundNextToThatText() {
        // An empty "a.b.c" under the text "a.b" is written beside it as "b.c".
        val file = tree("""{ "a": { "b": "text", "b.c": "" } }""")

        assertEquals("text", ResourceLookup.find(file, "a.b"))
        assertEquals("", ResourceLookup.find(file, "a.b.c"))
    }

    @Test
    fun aKeyWrittenAtTheTopLevelDoesNotAnswerLongerKeys() {
        val file = tree("""{ "b": "text", "b.b": "flat", "form.password": "Password" }""")

        assertEquals("flat", ResourceLookup.find(file, "b.b"))
        assertNull(ResourceLookup.find(file, "b.b.b"))
        assertNull(ResourceLookup.find(file, "form.password.0"))
        assertNull(ResourceLookup.find(file, "form.password.length"))
    }

    @Test
    fun emptySegmentsAreKeptLiterally() {
        val file = tree("""{ "": { "leading": "L" }, "trailing": { "": "T" }, "a": { "": { "b": "D" } } }""")

        assertEquals("L", ResourceLookup.find(file, ".leading"))
        assertEquals("T", ResourceLookup.find(file, "trailing."))
        assertEquals("D", ResourceLookup.find(file, "a..b"))
    }

    @Test
    fun anEmptyTextIsAValue() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "et" to """{ "subtitle": "", "nested": { "hint": "" } }""",
            "en" to """{ "subtitle": "English subtitle", "nested": { "hint": "English hint" } }""",
            configure = { fallbackLocale("en") },
        )
        h.bundledResources["translation_subtitle"] = "bundled"

        assertEquals("", client.text("subtitle"))
        assertEquals("", client.text("nested.hint"))
        assertEquals("", client.text("subtitle", fallback = "fallback"))
    }

    @Test
    fun aMissingKeyFallsThroughTheLocaleChain() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "en-FI" to """{ "only.variant": "variant", "all": "variant" }""",
            "en" to """{ "all": "base", "base": { "only": "base" }, "a": "base text" }""",
            "et" to """{ "all": "fallback", "base": { "only": "fallback" }, "fallback": { "only": "fallback" }, "a.b": "flat" }""",
            configure = { fallbackLocale("et") },
        )

        assertEquals(listOf("en-FI", "en", "et"), client.localeChain)
        assertEquals("variant", client.text("all"))
        assertEquals("variant", client.text("only.variant"))
        assertEquals("base", client.text("base.only"))
        assertEquals("fallback", client.text("fallback.only"))
        assertEquals("base text", client.text("a"))
        assertEquals("flat", client.text("a.b"))
        assertEquals("nowhere", client.text("nowhere"))
    }

    @Test
    fun aTextMissingEverywhereComesFromTheAppThenTheFallbackThenTheKey() {
        val h = Harness(folder.root)
        val client = h.launchWith("en" to """{ "present": "downloaded" }""")
        h.bundledResources["translation_present"] = "bundled"
        h.bundledResources["translation_nav_home"] = "Bundled home"
        h.bundledResources["translation_hello"] = "Hello, %1\$s!"

        assertEquals("downloaded", client.text("present", fallback = "fallback"))
        assertEquals("Bundled home", client.text("nav.home", fallback = "fallback"))
        assertEquals("Hello, Ann!", client.text("hello", mapOf("name" to "Ann")))
        assertEquals("Fallback for Ann", client.text("nav.away", mapOf("name" to "Ann"), fallback = "Fallback for {{name}}"))
        assertEquals("nav.away", client.text("nav.away"))
        assertEquals("{{not.interpolated}}", client.text("{{not.interpolated}}", mapOf("not.interpolated" to "x")))
    }

    @Test
    fun bundledResourcesAreUsedBeforeAnythingIsDownloaded() {
        val h = Harness(folder.root)
        h.bundledResources["translation_title"] = "Bundled title"
        h.bundledResources["translation_items"] = "plain"
        h.bundledPlurals["translation_items"] = "%1\$d items"
        val client = h.launch()

        assertEquals("Bundled title", client.text("title"))
        assertEquals("5 items", client.text("items", count = 5))
        assertEquals("plain", client.text("items"))
    }

    @Test
    fun aBundledTextInTheUsersLanguageComesBeforeTheFallbackLanguage() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "et" to """{ "both": "published et" }""",
            "en" to """{ "title": "Title", "both": "published en", "english": { "only": "English only" } }""",
            configure = { fallbackLocale("en") },
        )
        h.bundledIn["et"] = mutableMapOf(
            "translation_title" to "Pealkiri (bundled)",
            "translation_both" to "bundled et",
            "translation_hello" to "Tere, %1\$s!",
        )
        h.bundledResources["translation_title"] = "Title (bundled)"
        h.bundledResources["translation_english_only"] = "Bundled English only"

        assertEquals("Pealkiri (bundled)", client.text("title"))
        // The release still wins where it has the key in the user's language.
        assertEquals("published et", client.text("both"))
        // A key nobody has in the user's language comes from the fallback one.
        assertEquals("English only", client.text("english.only"))
        assertEquals("Tere, Ann!", client.text("hello", mapOf("name" to "Ann")))
    }

    @Test
    fun aBundledTextInAnotherLanguageStaysBehindTheRelease() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "et" to "{}",
            "en" to """{ "title": "Title" }""",
            configure = { fallbackLocale("en") },
        )
        h.bundledResources["translation_title"] = "Title (bundled)"
        h.bundledResources["translation_bundled_only"] = "Bundled only"

        assertEquals("Title", client.text("title"))
        assertEquals("Bundled only", client.text("bundled.only"))
    }

    @Test
    fun aBundledTextInTheUsersLanguageComesBeforeTheReleasesGeneralForm() {
        val h = Harness(folder.root)
        val client = h.launchWith("en" to """{ "items_other": "{{count}} published items" }""")
        h.bundledIn["en"] = mutableMapOf("translation_items" to "%1\$d item")

        // The general form stands in for a missing one only as a last resort.
        assertEquals("1 item", client.text("items", count = 1))
        assertEquals("6 published items", client.text("items", count = 6))
    }

    @Test
    fun theUsersLanguageIsTheLocaleInUse() {
        val h = Harness(folder.root)
        h.bundledIn["en-FI"] = mutableMapOf("translation_title" to "Finnish English")
        h.bundledIn["et"] = mutableMapOf("translation_title" to "Eesti")
        h.bundledResources["translation_title"] = "Default"
        val client = h.launch("en-FI")

        assertEquals("Finnish English", client.text("title"))
        client.setLocale("et", null)
        assertEquals("Eesti", client.text("title"))
        client.setLocale("de", null)
        assertEquals("Default", client.text("title"))
    }

    @Test
    fun aBundledResourceThatThrowsIsTreatedAsMissing() {
        val unreadable = object : BundledStrings {
            override fun find(resourceName: String, count: Long?): String? = "from the app"

            override fun findIn(locale: String, resourceName: String, count: Long?): String? =
                throw IllegalStateException("resources are gone")
        }
        val reading = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = FakeTransport(),
            bundled = unreadable,
            worker = { it.run() },
            callbacks = { it.run() },
        )
        assertEquals("from the app", reading.text("title", fallback = "fallback"))


        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = FakeTransport(),
            bundled = { _, _ -> throw IllegalStateException("resources are gone") },
            worker = { it.run() },
            callbacks = { it.run() },
        )

        assertEquals("fallback", client.text("title", fallback = "fallback"))
    }

    @Test
    fun namespacesAreLookedUpSeparately() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Common title" }""", "common")
        h.transport.file("v1", "en", """{ "title": "Checkout title", "pay": "Pay" }""", "checkout")
        val client = h.launch { namespaces("common", "checkout") }
        client.refreshNow()
        h.bundledResources["checkout_cancel"] = "Bundled cancel"
        h.bundledResources["other_title"] = "Bundled other"

        assertEquals("Common title", client.text("title"))
        assertEquals("Checkout title", client.text("title", namespace = "checkout"))
        assertEquals("pay", client.text("pay"))
        assertEquals("Pay", client.text("pay", namespace = "checkout"))
        assertEquals("Bundled cancel", client.text("cancel", namespace = "checkout"))
        // A namespace that was not configured is not downloaded; the app's resources still answer.
        assertEquals("Bundled other", client.text("title", namespace = "other"))
    }

    @Test
    fun theDefaultNamespaceCanBeChosen() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Common title" }""", "common")
        h.transport.file("v1", "en", """{ "title": "Checkout title" }""", "checkout")
        val client = h.launch {
            namespaces("common", "checkout")
            defaultNamespace("checkout")
        }
        client.refreshNow()

        assertEquals("Checkout title", client.text("title"))
        assertEquals("Common title", client.text("title", namespace = "common"))
    }

    @Test
    fun aCountSelectsThePluralFormOfTheFilesLanguage() {
        val file = tree(
            """{ "items_one": "{{count}} item", "items_other": "{{count}} items",
                 "docs": { "count_one": "one doc", "count_other": "many docs" } }""",
        )

        assertEquals("{{count}} item", ResourceLookup.find(file, "items", 1, "en"))
        assertEquals("{{count}} items", ResourceLookup.find(file, "items", 0, "en"))
        assertEquals("{{count}} items", ResourceLookup.find(file, "items", 2, "en-GB"))
        assertEquals("one doc", ResourceLookup.find(file, "docs.count", 1, "en"))
        assertEquals("many docs", ResourceLookup.find(file, "docs.count", 7, "en"))
        assertNull(ResourceLookup.find(file, "items", null, "en"))
        assertNull(ResourceLookup.find(file, "missing", 1, "en"))
    }

    @Test
    fun theZeroFormIsUsedForACountOfZeroWhenTheFileHasOne() {
        val file = tree("""{ "items_zero": "no items", "items_one": "one item", "items_other": "some items" }""")
        val withoutZero = tree("""{ "items_one": "one item", "items_other": "some items" }""")

        assertEquals("no items", ResourceLookup.find(file, "items", 0, "en"))
        assertEquals("one item", ResourceLookup.find(file, "items", 1, "en"))
        assertEquals("some items", ResourceLookup.find(withoutZero, "items", 0, "en"))
    }

    @Test
    fun aMissingCategoryLeavesAFileToItsPlainKeyNotToAnotherForm() {
        val onlyOther = tree("""{ "items_other": "other form", "items": "plain" }""")
        val onlyPlain = tree("""{ "items": "plain" }""")
        val otherAlone = tree("""{ "items_other": "other form" }""")

        // Russian asks for "few" for 3. As in i18next, no other form of the
        // same file stands in for it: the plain key does, or the next locale.
        assertEquals("plain", ResourceLookup.find(onlyOther, "items", 3, "ru"))
        assertEquals("plain", ResourceLookup.find(onlyOther, "items", 1, "en"))
        assertEquals("other form", ResourceLookup.find(onlyOther, "items", 5, "en"))
        assertEquals("plain", ResourceLookup.find(onlyPlain, "items", 3, "ru"))
        assertEquals("plain", ResourceLookup.find(onlyPlain, "items", 0, "en"))
        assertNull(ResourceLookup.find(otherAlone, "items", 1, "en"))
        assertEquals("other form", ResourceLookup.findOther(otherAlone, "items"))
    }

    @Test
    fun aVariantThatOverridesOnlyTheOtherFormInheritsTheRest() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "en-FI" to """{ "items_other": "{{count}} things (FI)" }""",
            "en" to """{ "items_one": "{{count}} item", "items_other": "{{count}} items" }""",
        )

        assertEquals("1 item", client.text("items", count = 1))
        assertEquals("2 things (FI)", client.text("items", count = 2))
    }

    @Test
    fun aMissingFormFallsToTheNextLanguageBeforeTheOtherForm() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "de" to """{ "items_other": "{{count}} Artikel", "solo_other": "{{count}} allein" }""",
            "en" to """{ "items_one": "{{count}} item", "items_other": "{{count}} items" }""",
        ) { fallbackLocale("en") }

        assertEquals("1 item", client.text("items", count = 1))
        assertEquals("3 Artikel", client.text("items", count = 3))
        // English has nothing for this key, so German's general form is the last resort.
        assertEquals("1 allein", client.text("solo", count = 1))
    }

    @Test
    fun eachFileInTheChainUsesItsOwnLanguagesPluralRules() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "ru" to """{ "files_one": "{{count}} файл", "files_few": "{{count}} файла", "files_many": "{{count}} файлов" }""",
            "en" to """{ "files_one": "{{count}} file", "files_other": "{{count}} files",
                         "apples_one": "{{count}} apple", "apples_other": "{{count}} apples" }""",
            configure = { fallbackLocale("en") },
        )

        assertEquals("1 файл", client.text("files", count = 1))
        assertEquals("3 файла", client.text("files", count = 3))
        assertEquals("5 файлов", client.text("files", count = 5))
        assertEquals("21 файл", client.text("files", count = 21))
        // Not in the Russian file: the English file answers with English rules,
        // where 21 is "other" although Russian calls it "one".
        assertEquals("21 apples", client.text("apples", count = 21))
        assertEquals("1 apple", client.text("apples", count = 1))
    }

    @Test
    fun aVariantThatOverridesOnePluralFormLeavesTheOthersToTheBaseLanguage() {
        val h = Harness(folder.root)
        val client = h.launchWith(
            "en-FI" to """{ "items_one": "{{count}} piece" }""",
            "en" to """{ "items_one": "{{count}} item", "items_other": "{{count}} items" }""",
        )

        assertEquals("1 piece", client.text("items", count = 1))
        assertEquals("4 items", client.text("items", count = 4))
    }

    @Test
    fun aPluralKeyNextToADeeperKeyIsStillFound() {
        // Next to the plural "items", the key "items.title" is written at the top level.
        val file = tree("""{ "items_one": "one", "items_other": "other", "items.title": "Items" }""")

        assertEquals("Items", ResourceLookup.find(file, "items.title"))
        assertEquals("other", ResourceLookup.find(file, "items", 2, "en"))
    }
}
