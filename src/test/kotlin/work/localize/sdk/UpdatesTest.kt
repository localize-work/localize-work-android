package work.localize.sdk

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import work.localize.sdk.RefreshResult.Status

class UpdatesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val heard = CopyOnWriteArrayList<String?>()

    private fun Harness.listeningClient(
        deviceLocale: String = "en",
        configure: LocalizeConfig.Builder.() -> Unit = {},
    ): LocalizeClient {
        val listeners = CopyOnWriteArrayList<LocalizeListener>()
        listeners.add(LocalizeListener { version -> heard.add(version) })
        return LocalizeClient(
            config = LocalizeConfig(SLUG) {
                cdnBaseUrl(CDN)
                configure()
            },
            cacheRoot = folder.root,
            deviceLocale = deviceLocale,
            transport = transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { it.run() },
            listeners = listeners,
            sleeper = { },
        )
    }

    @Test
    fun aListenerIsToldWhenAReleaseTakesEffect() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.listeningClient()

        client.refreshNow()
        assertEquals(listOf<String?>("v1"), heard.toList())

        // Nothing new: nothing to tell.
        client.refreshNow()
        assertEquals(listOf<String?>("v1"), heard.toList())

        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        client.refreshNow()
        assertEquals(listOf<String?>("v1", "v2"), heard.toList())
    }

    @Test
    fun aListenerIsNotToldAboutAFailedDownload() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.status(fileUrl("v1", "en"), 500)
        val client = h.listeningClient()

        assertEquals(Status.FAILED, client.refreshNow().status)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun aListenerIsNotToldAboutAReleaseThatWaitsForTheNextLaunch() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.listeningClient { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }

        assertEquals(Status.DOWNLOADED, client.refreshNow().status)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun aListenerThatThrowsDoesNotStopTheOthers() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val listeners = CopyOnWriteArrayList<LocalizeListener>()
        listeners.add(LocalizeListener { throw IllegalStateException("listener bug") })
        listeners.add(LocalizeListener { version -> heard.add(version) })
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { it.run() },
            listeners = listeners,
        )

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals(listOf<String?>("v1"), heard.toList())
    }

    @Test
    fun switchingTheLocaleUsesWhatIsThereAndDownloadsTheRest() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello", "only": "English only" }""")
        h.transport.file("v1", "et", """{ "title": "Tere" }""")
        val client = h.listeningClient { fallbackLocale("en") }
        client.refreshNow()
        heard.clear()
        h.transport.requests.clear()
        val results = ArrayList<RefreshResult>()

        client.setLocale("et", results::add)

        assertEquals("et", client.locale)
        assertEquals(listOf("et", "en"), client.localeChain)
        // Only the file the device did not have is downloaded, from the
        // release it holds; the pointer then confirms that release.
        assertEquals(listOf(fileUrl("v1", "et"), POINTER_URL), h.transport.requests)
        assertEquals(Status.APPLIED, results.single().status)
        assertEquals("Tere", client.text("title"))
        assertEquals("English only", client.text("only"))
        // Told once for the switch, when no release could be read for the
        // new locale yet, and once when its file had arrived.
        assertEquals(listOf<String?>(null, "v1"), heard.toList())

        // The files of both locales are on the device now.
        h.transport.unreachable(POINTER_URL)
        val offline = h.listeningClient(deviceLocale = "et") { fallbackLocale("en") }
        assertEquals("Tere", offline.text("title"))
        assertEquals("English only", offline.text("only"))
    }

    @Test
    fun switchingTheLocaleOfflineFallsBackUntilTheFilesArrive() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        h.transport.file("v1", "et", """{ "title": "Tere" }""")
        val client = h.listeningClient { fallbackLocale("en") }
        client.refreshNow()
        h.transport.unreachable(POINTER_URL)
        h.transport.unreachable(fileUrl("v1", "et"))
        val results = ArrayList<RefreshResult>()

        client.setLocale("et", results::add)

        assertEquals(Status.FAILED, results.single().status)
        assertEquals("et", client.locale)
        // The release has no Estonian file, so it is not read: the bundled
        // strings are in Estonian, the release's English text is not.
        assertNull(client.version)
        assertEquals("title", client.text("title"))

        h.transport.pointer("v1")
        h.transport.file("v1", "et", """{ "title": "Tere" }""")
        assertEquals(Status.APPLIED, client.refreshNow().status)
        assertEquals("Tere", client.text("title"))
    }

    /**
     * A change of locale needs only the release the app already holds. While
     * the pointer cannot be read the refresh fails, but the new locale's
     * texts of that release are shown all the same.
     */
    @Test
    fun aChangeOfLocaleDoesNotNeedThePointer() {
        for (policy in UpdatePolicy.values()) {
            val root = folder.newFolder()
            val h = Harness(root)
            h.transport.file("v1", "et", """{ "title": "Tere" }""")
            h.launchWith("en" to """{ "title": "Hello" }""")
            val configure: LocalizeConfig.Builder.() -> Unit = {
                updatePolicy(policy)
                fallbackLocale("en")
            }
            val client = h.launch(configure = configure)
            assertEquals("Hello", client.text("title"))
            h.transport.status(POINTER_URL, 503)
            h.transport.requests.clear()
            val results = ArrayList<RefreshResult>()

            client.setLocale("et", results::add)

            assertEquals(policy.name, Status.FAILED, results.single().status)
            assertEquals(policy.name, fileUrl("v1", "et"), h.transport.requests.first())
            assertEquals(policy.name, "v1", client.version)
            assertEquals(policy.name, "Tere", client.text("title"))
            // The file reached the device too: the next launch starts whole, offline.
            h.transport.unreachable(POINTER_URL)
            h.transport.unreachable(fileUrl("v1", "et"))
            assertEquals(policy.name, "Tere", h.launch(deviceLocale = "et", configure = configure).text("title"))
        }
    }

    /**
     * A newer release that cannot be downloaded is reported, and does not
     * keep the release in use from getting the new locale's files.
     */
    @Test
    fun inNextLaunchModeTheReleaseInUseIsCompletedWhenTheNewerOneCannotBeDownloaded() {
        val h = Harness(folder.root)
        h.transport.file("v1", "et", """{ "title": "Tere v1" }""")
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val client = h.launch {
            updatePolicy(UpdatePolicy.NEXT_LAUNCH)
            fallbackLocale("en")
        }
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        h.transport.status(fileUrl("v2", "et"), 503)
        val results = ArrayList<RefreshResult>()

        client.setLocale("et", results::add)

        assertEquals(Status.FAILED, results.single().status)
        assertEquals("v1", client.version)
        assertEquals("Tere v1", client.text("title"))
    }

    @Test
    fun inNextLaunchModeFilesOfTheReleaseInUseAreAddedAtOnce() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        h.transport.file("v1", "et", """{ "title": "Tere" }""")
        h.launchWith("en" to """{ "title": "Hello" }""")
        val client = h.listeningClient { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        assertEquals("v1", client.version)
        val results = ArrayList<RefreshResult>()

        client.setLocale("et", results::add)

        // No text the app already shows can change, so nothing has to wait.
        assertEquals(Status.APPLIED, results.single().status)
        assertEquals("Tere", client.text("title"))
    }

    @Test
    fun inNextLaunchModeANewLocaleIsCompletedFromTheReleaseInUse() {
        val h = Harness(folder.root)
        for (version in listOf("v1", "v2")) {
            h.transport.file(version, "en", """{ "title": "Hello $version" }""")
            h.transport.file(version, "et", """{ "title": "Tere $version" }""")
        }
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = {
            updatePolicy(UpdatePolicy.NEXT_LAUNCH)
            fallbackLocale("en")
        }
        val client = h.listeningClient(configure = nextLaunch)
        h.transport.pointer("v2")
        assertEquals(Status.DOWNLOADED, client.refreshNow().status)
        heard.clear()
        h.transport.requests.clear()
        val results = ArrayList<RefreshResult>()

        client.setLocale("et", results::add)

        // v2 still waits, and the Estonian file comes from v1, the release on
        // screen. The texts changed, so that is what the result says, with
        // the release now read; the one that waits is told of next time.
        assertEquals(Status.APPLIED, results.single().status)
        assertEquals("v1", results.single().version)
        assertEquals(listOf(fileUrl("v1", "et"), POINTER_URL, fileUrl("v2", "et")), h.transport.requests)
        assertEquals("v1", client.version)
        assertEquals("Tere v1", client.text("title"))
        assertEquals(listOf<String?>(null, "v1"), heard.toList())
        val after = client.refreshNow()
        assertEquals(Status.DOWNLOADED, after.status)
        assertEquals("v2", after.version)
        assertEquals("Tere v1", client.text("title"))

        // The next launch gets v2 whole, offline.
        h.transport.unreachable(POINTER_URL)
        val next = h.listeningClient(deviceLocale = "et", configure = nextLaunch)
        assertEquals("v2", next.version)
        assertEquals("Tere v2", next.text("title"))
    }

    /**
     * A regional locale without overrides of its own has no file in a
     * release. The release in use, which holds its base language, is whole
     * for it all the same.
     */
    @Test
    fun inNextLaunchModeARegionalLocaleWithoutOverridesReadsTheReleaseInUse() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        val client = h.listeningClient(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        assertEquals(Status.DOWNLOADED, client.refreshNow().status)
        h.transport.requests.clear()

        client.setLocale("en-FI", null)

        assertEquals(listOf(fileUrl("v1", "en-FI"), POINTER_URL, fileUrl("v2", "en-FI")), h.transport.requests)
        assertEquals("v1", client.version)
        assertEquals("Hello v1", client.text("title"))
        // The next launch reads v2, offline.
        h.transport.unreachable(POINTER_URL)
        assertEquals("Hello v2", h.listeningClient(deviceLocale = "en-FI", configure = nextLaunch).text("title"))
    }

    @Test
    fun inNextLaunchModeAReleaseGoneFromTheCdnIsNotCompletedWithEmptyFiles() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        // v1 has been removed from the CDN since; only v2 is there.
        h.transport.status(fileUrl("v1", "en"), 404)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        h.transport.file("v2", "et", """{ "title": "Tere v2" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = {
            updatePolicy(UpdatePolicy.NEXT_LAUNCH)
            fallbackLocale("en")
        }
        val client = h.listeningClient(configure = nextLaunch)

        client.setLocale("et", null)

        // v1 cannot be made whole for Estonian, so it is not read: the app's
        // bundled strings are shown for the rest of this run.
        assertNull(client.version)
        assertEquals("title", client.text("title"))
        assertEquals("Tere v2", h.listeningClient(deviceLocale = "et", configure = nextLaunch).text("title"))
    }

    /**
     * The locale is switched while a new release is on its way. What was
     * fetched for the earlier locale is not a whole release for the new one,
     * so the same refresh fetches the rest before anything is put in use.
     */
    @Test
    fun aLocaleSwitchedDuringADownloadIsFollowedBeforeTheReleaseIsUsed() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""", "et" to """{ "title": "Tere v1" }""") { fallbackLocale("et") }
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        h.transport.file("v2", "et", """{ "title": "Tere v2" }""")
        val queued = ArrayList<Runnable>()
        val listeners = CopyOnWriteArrayList<LocalizeListener>()
        listeners.add(LocalizeListener { version -> heard.add(version) })
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { queued.add(it) },
            callbacks = { it.run() },
            listeners = listeners,
            sleeper = { },
        )
        assertEquals("Hello v1", client.text("title"))
        val seenDuringDownload = ArrayList<String>()
        h.transport.onRequest = { url ->
            if (url == fileUrl("v2", "en")) {
                h.transport.onRequest = { seenDuringDownload.add(client.text("title")) }
                client.setLocale("et", null)
                // v1 has the Estonian file, so the switch shows it at once.
                seenDuringDownload.add(client.text("title"))
            }
        }

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals(listOf("Tere v1", "Tere v1"), seenDuringDownload)
        assertEquals("v2", client.version)
        assertEquals("Tere v2", client.text("title"))
        assertEquals(
            listOf(POINTER_URL, fileUrl("v2", "en"), fileUrl("v2", "et")),
            h.transport.requests.takeLast(3),
        )
        // The refresh the switch asked for finds nothing left to do.
        h.transport.onRequest = {}
        queued.single().run()
        assertEquals("v2", client.version)
        // What is stored is whole for Estonian.
        h.transport.unreachable(POINTER_URL)
        assertEquals("Tere v2", h.listeningClient(deviceLocale = "et").text("title"))
    }

    /**
     * The locale is switched while a newer release is on its way, to one the
     * release on screen has on the CDN but not on the device. As at the start
     * of any refresh the release held comes first, so its own texts in the
     * new locale are shown while the rest of the newer one is fetched.
     */
    @Test
    fun aLocaleSwitchedDuringADownloadIsFirstCompletedFromTheReleaseHeld() {
        for (policy in UpdatePolicy.values()) {
            val root = folder.newFolder()
            val h = Harness(root)
            h.transport.file("v1", "et", """{ "title": "Tere v1" }""")
            h.launchWith("en" to """{ "title": "Hello v1" }""")
            h.transport.pointer("v2")
            h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
            h.transport.file("v2", "et", """{ "title": "Tere v2" }""")
            val queued = ArrayList<Runnable>()
            val client = LocalizeClient(
                config = LocalizeConfig(SLUG) {
                    cdnBaseUrl(CDN)
                    fallbackLocale("en")
                    updatePolicy(policy)
                },
                cacheRoot = root,
                deviceLocale = "en",
                transport = h.transport,
                bundled = { _, _ -> null },
                worker = { queued.add(it) },
                callbacks = { it.run() },
                sleeper = { },
            )
            assertEquals(policy.name, "Hello v1", client.text("title"))
            h.transport.requests.clear()
            val seen = LinkedHashMap<String, String>()
            h.transport.onRequest = { url ->
                if (url == fileUrl("v2", "en")) client.setLocale("et", null)
                seen[url] = client.text("title", fallback = "bundled")
            }

            val result = client.refreshNow()

            h.transport.onRequest = {}
            assertEquals(
                policy.name,
                listOf(POINTER_URL, fileUrl("v2", "en"), fileUrl("v1", "et"), fileUrl("v2", "et")),
                h.transport.requests.toList(),
            )
            // Bundled strings only until the release held has its Estonian file.
            assertEquals(policy.name, "bundled", seen[fileUrl("v1", "et")])
            assertEquals(policy.name, "Tere v1", seen[fileUrl("v2", "et")])
            if (policy == UpdatePolicy.IMMEDIATE) {
                assertEquals(Status.APPLIED, result.status)
                assertEquals("Tere v2", client.text("title"))
            } else {
                // v2 waits; what changed on screen is v1 in Estonian.
                assertEquals(Status.APPLIED, result.status)
                assertEquals("v1", result.version)
                assertEquals("v1", client.version)
                assertEquals("Tere v1", client.text("title"))
                assertEquals(Status.DOWNLOADED, client.refreshNow().status)
            }
        }
    }

    /**
     * A release that has no file at all for the new locale. Asked for
     * directly, its "not found" answers could also mean the release is gone
     * from the CDN, so the app's bundled strings stay. Once the pointer has
     * named that version it is known to be there: the missing files are
     * empty ones, and the texts come from the fallback locale, as they would
     * after a fresh start in that locale.
     */
    @Test
    fun aReleaseWithoutTheNewLocaleIsReadOnceThePointerNamesIt() {
        for (policy in UpdatePolicy.values()) {
            val h = Harness(folder.newFolder())
            h.launchWith("en" to """{ "title": "Hello" }""")
            val configure: LocalizeConfig.Builder.() -> Unit = {
                updatePolicy(policy)
                fallbackLocale("en")
            }
            val client = h.launch(configure = configure)
            h.transport.status(POINTER_URL, 503)
            val results = ArrayList<RefreshResult>()

            client.setLocale("fr", results::add)

            assertEquals(policy.name, Status.FAILED, results.single().status)
            assertNull(policy.name, client.version)
            assertEquals(policy.name, "Bonjour (bundled)", client.text("title", fallback = "Bonjour (bundled)"))

            h.transport.pointer("v1")
            assertEquals(policy.name, Status.APPLIED, client.refreshNow().status)
            assertEquals(policy.name, "v1", client.version)
            assertEquals(policy.name, "Hello", client.text("title", fallback = "Bonjour (bundled)"))
            // A start in French reads the same, offline.
            h.transport.unreachable(POINTER_URL)
            val next = h.launch(deviceLocale = "fr", configure = configure)
            assertEquals(policy.name, "Hello", next.text("title", fallback = "Bonjour (bundled)"))
        }
    }

    /**
     * A file of the fallback locale says nothing about the locale asked for.
     * A release that answers "not found" for every Italian file is not read
     * in German in place of the app's bundled Italian strings, until the
     * pointer has named it.
     */
    @Test
    fun aFallbackLocaleFileDoesNotVouchForAReleaseWithoutTheNewLocale() {
        for (policy in UpdatePolicy.values()) {
            val h = Harness(folder.newFolder())
            h.transport.file("v1", "de", """{ "title": "Hallo" }""")
            val first = h.launchWith("en" to """{ "title": "Hello" }""")
            h.transport.status(POINTER_URL, 503)

            val client = h.launch(deviceLocale = "it", continuing = first) {
                updatePolicy(policy)
                fallbackLocale("de")
            }

            assertEquals(policy.name, Status.FAILED, client.refreshNow().status)
            assertNull(policy.name, client.version)
            assertEquals(policy.name, "Ciao (bundled)", client.text("title", fallback = "Ciao (bundled)"))

            h.transport.pointer("v1")
            assertEquals(policy.name, Status.APPLIED, client.refreshNow().status)
            assertEquals(policy.name, "Hallo", client.text("title", fallback = "Ciao (bundled)"))
        }
    }

    /**
     * The release already has texts of the locale; a namespace it has no file
     * for in that locale is an empty file, as for a regional locale.
     */
    @Test
    fun aReleaseWithTextsOfTheLocaleIsCompletedForANamespaceItLacks() {
        val h = Harness(folder.root)
        h.launchWith("it" to """{ "title": "Ciao" }""")
        h.transport.status(POINTER_URL, 503)

        val client = h.launch(deviceLocale = "it") { namespaces("translation", "extra") }

        assertNull(client.version)
        assertEquals(Status.FAILED, client.refreshNow().status)
        assertEquals("v1", client.version)
        assertEquals("Ciao", client.text("title"))
    }

    /**
     * The release has texts of the regional locale and none of its base
     * language. A namespace added by an app update has no file in either:
     * the release is still read, since it has the language.
     */
    @Test
    fun aReleaseWithTextsOfARegionalLocaleOnlyIsCompletedForANamespaceItLacks() {
        val h = Harness(folder.root)
        h.launchWith("fr-CA" to """{ "title": "Allô" }""")
        h.transport.status(POINTER_URL, 503)

        val client = h.launch(deviceLocale = "fr-CA") { namespaces("translation", "extra") }

        assertNull(client.version)
        assertEquals(Status.FAILED, client.refreshNow().status)
        assertEquals("v1", client.version)
        assertEquals("Allô", client.text("title", fallback = "Bonjour (bundled)"))
    }

    /**
     * Texts of the language in a namespace the app no longer asks for say
     * little about the namespaces it does ask for: they do not make a release
     * nothing has named readable in the fallback locale.
     */
    @Test
    fun textsInANamespaceThatIsNotConfiguredDoNotVouchForARelease() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "fr", """{ "title": "Bienvenue" }""", namespace = "onboarding")
        h.transport.file("v1", "en", """{ "title": "Welcome" }""", namespace = "onboarding")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        val earlier = h.launch(deviceLocale = "fr") {
            fallbackLocale("en")
            namespaces("onboarding")
        }
        assertEquals(Status.APPLIED, earlier.refreshNow().status)
        h.transport.status(POINTER_URL, 503)

        val client = h.launch(deviceLocale = "fr") { fallbackLocale("en") }

        assertEquals(Status.FAILED, client.refreshNow().status)
        assertNull(client.version)
        assertEquals("Bonjour (bundled)", client.text("title", fallback = "Bonjour (bundled)"))
    }

    /**
     * The locale is switched while the files of the earlier one are on their
     * way. A file of the earlier locale shows that the release has that
     * locale, not the one the app is in now: the release is not read in the
     * fallback locale for it, and the refresh the switch asked for agrees.
     */
    @Test
    fun aFileOfTheEarlierLocaleDoesNotVouchForTheLocaleSwitchedTo() {
        val h = Harness(folder.root)
        h.transport.file("v1", "fr", """{ "title": "Bonjour" }""")
        h.transport.file("v1", "en-GB", """{ "title": "Hello (en-GB)" }""")
        h.launchWith("de" to """{ "title": "Hallo" }""")
        h.transport.status(POINTER_URL, 503)
        val queued = ArrayList<Runnable>()
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) {
                cdnBaseUrl(CDN)
                fallbackLocale("en-GB")
            },
            cacheRoot = folder.root,
            deviceLocale = "fr",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { queued.add(it) },
            callbacks = { it.run() },
            sleeper = { },
        )
        h.transport.onRequest = { url ->
            if (url == fileUrl("v1", "fr")) {
                h.transport.onRequest = {}
                client.setLocale("en", null)
            }
        }

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertNull(client.version)
        assertEquals("Hello (bundled)", client.text("title", fallback = "Hello (bundled)"))
        queued.single().run()
        assertNull(client.version)
        assertEquals("Hello (bundled)", client.text("title", fallback = "Hello (bundled)"))
        // French is what the release does have, and switching back reads it.
        client.setLocale("fr", null)
        queued.last().run()
        assertEquals("Bonjour", client.text("title"))
    }

    /**
     * A pinned release, nothing on the device yet, and the locale switched
     * while the first file is on its way. The release has no file of the
     * earlier locale and one of the new: it is published, and the refresh
     * goes on to the locale the app is in.
     */
    @Test
    fun aPinnedReleaseIsNotTakenForUnpublishedOverTheLocaleTheAppHasLeft() {
        val h = Harness(folder.root)
        h.transport.file("v1", "fr", """{ "title": "Bonjour" }""")
        val queued = ArrayList<Runnable>()
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) {
                cdnBaseUrl(CDN)
                version("v1")
            },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { queued.add(it) },
            callbacks = { it.run() },
            sleeper = { },
        )
        h.transport.onRequest = { url ->
            if (url == fileUrl("v1", "en")) {
                h.transport.onRequest = {}
                client.setLocale("fr", null)
            }
        }

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("Bonjour", client.text("title"))
        assertEquals(listOf(fileUrl("v1", "en"), fileUrl("v1", "fr")), h.transport.requests.toList())
    }

    /**
     * A pinned version is named by the configuration, as `latest.json` names
     * the latest one: a locale it has no file for reads the fallback locale,
     * without the pointer.
     */
    @Test
    fun aPinnedVersionWithoutTheNewLocaleIsReadInTheFallbackLocale() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello" }""")
        h.transport.status(POINTER_URL, 503)
        val client = h.launch {
            version("v1")
            fallbackLocale("en")
        }
        val results = ArrayList<RefreshResult>()

        client.setLocale("fr", results::add)

        assertEquals(Status.APPLIED, results.single().status)
        assertEquals("Hello", client.text("title", fallback = "Bonjour (bundled)"))
        assertEquals(1, h.transport.pointerRequests)
    }

    /**
     * A pinned release, nothing on the device yet, and the locale switched
     * while the first file is on its way. That file is there but empty: the
     * release is published, and is not taken for one that is not.
     */
    @Test
    fun aPinnedReleaseWithAnEmptyFileIsNotTakenForUnpublishedAfterALocaleSwitch() {
        val h = Harness(folder.root)
        h.transport.file("v1", "en", "{}")
        val queued = ArrayList<Runnable>()
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) {
                cdnBaseUrl(CDN)
                version("v1")
                fallbackLocale("en")
            },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { queued.add(it) },
            callbacks = { it.run() },
            sleeper = { },
        )
        h.transport.onRequest = { url ->
            if (url == fileUrl("v1", "en")) {
                h.transport.onRequest = {}
                client.setLocale("et", null)
            }
        }

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("v1", client.version)
        assertEquals(listOf(fileUrl("v1", "en"), fileUrl("v1", "et")), h.transport.requests.toList())
    }

    /**
     * The release could not be stored, so it is in use from memory only.
     * Setting the SDK up again for another locale is still the same run: the
     * new client goes on from that release and needs only the new locale's
     * files, not the pointer.
     */
    @Test
    fun aReleaseThatCouldNotBeStoredIsKeptWhenTheSdkIsSetUpAgain() {
        val h = Harness(folder.newFile("blocked"))
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        h.transport.file("v1", "et", """{ "title": "Tere" }""")
        val first = h.launch()
        assertEquals(Status.APPLIED, first.refreshNow().status)
        assertEquals("Hello", first.text("title"))

        h.transport.status(POINTER_URL, 503)
        val second = h.launch(deviceLocale = "et", continuing = first) { fallbackLocale("en") }
        assertNull(second.version)
        assertEquals(Status.FAILED, second.refreshNow().status)
        assertEquals("v1", second.version)
        assertEquals("Tere", second.text("title"))
        assertEquals(1, h.transport.requests.count { it == fileUrl("v1", "en") })

        // The same locale again: nothing is missing, so it is read at once.
        val third = h.launch(deviceLocale = "et", continuing = second) { fallbackLocale("en") }
        assertEquals("Tere", third.text("title"))
        // A real start has nothing: the release never reached the device.
        assertNull(h.launch(deviceLocale = "et").version)
    }

    @Test
    fun aLocaleThatIsNotALanguageTagIsRefused() {
        val client = Harness(folder.root).listeningClient()
        for (locale in listOf("", "../en", "en/FI")) {
            try {
                client.setLocale(locale, null)
                fail("\"$locale\" was accepted")
            } catch (expected: IllegalArgumentException) {
                assertEquals("en", client.locale)
            }
        }
    }

    @Test
    fun theDeviceLocaleIsUsedUnlessTheConfigurationNamesOne() {
        val h = Harness(folder.root)

        assertEquals("fi-FI", h.launch("fi-FI").locale)
        assertEquals(listOf("fi-FI", "fi"), h.launch("fi-FI").localeChain)
        assertEquals("en-US", h.launch("en-US-u-fw-mon").locale)
        assertEquals("he-IL", h.launch("iw-IL").locale)
        assertEquals("et", h.launch("fi-FI") { locale("et") }.locale)
        assertEquals("und", h.launch("").locale)
    }

    /**
     * Setting the SDK up again is still the same run of the app: with the
     * next-launch policy the release on screen stays, and the one that waits
     * keeps waiting for a real launch.
     */
    @Test
    fun inNextLaunchModeSettingUpAgainKeepsTheReleaseTheRunStartedWith() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        val first = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        assertEquals(Status.DOWNLOADED, first.refreshNow().status)

        val again = h.launch(continuing = first, configure = nextLaunch)
        assertEquals("v1", again.version)
        assertEquals("Hello v1", again.text("title"))
        assertEquals(Status.DOWNLOADED, again.refreshNow().status)
        assertEquals("Hello v1", again.text("title"))

        // With the other policy the release that waited is read at once.
        assertEquals("Hello v2", h.launch(continuing = again).text("title"))
    }

    /** A run that started with no release shows bundled strings to its end. */
    @Test
    fun inNextLaunchModeARunThatStartedWithNothingShowsNothingItDownloaded() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        h.transport.file("v1", "et", """{ "title": "Tere" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        val first = h.launch(configure = nextLaunch)
        assertEquals(Status.DOWNLOADED, first.refreshNow().status)
        assertNull(first.version)

        // The same locale again, and another one: still the first run.
        var previous = first
        for (locale in listOf("en", "et")) {
            val again = h.launch(deviceLocale = locale, continuing = previous, configure = nextLaunch)
            assertNull(locale, again.version)
            assertEquals(locale, "title", again.text("title"))
            assertEquals(locale, Status.DOWNLOADED, again.refreshNow().status)
            assertNull(locale, again.version)
            previous = again
        }
        previous.setLocale("en", null)
        assertNull(previous.version)

        // The next launch reads it, offline.
        h.transport.unreachable(POINTER_URL)
        assertEquals("Tere", h.launch(deviceLocale = "et", configure = nextLaunch).text("title"))
    }

    /**
     * The SDK is set up again while the earlier client is downloading. That
     * client stores nothing more, so what the new one reads from the device
     * stays whole: the release in use is still there for an offline launch.
     */
    @Test
    fun aClientReplacedWhileItDownloadsLeavesTheDeviceAsItWas() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val old = h.launch()
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        var replacement: LocalizeClient? = null
        h.transport.onRequest = { url ->
            if (url == fileUrl("v2", "en") && replacement == null) replacement = h.launch(continuing = old)
        }

        val result = old.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(LocalizeException.Reason.NOT_INITIALIZED, result.error?.reason)
        assertEquals("Hello v1", replacement!!.text("title"))
        h.transport.onRequest = {}
        h.transport.unreachable(POINTER_URL)
        assertEquals("Hello v1", h.launch().text("title"))
        // The new client is the one that brings v2 in.
        h.transport.pointer("v2")
        assertEquals(Status.APPLIED, replacement!!.refreshNow().status)
        assertEquals("Hello v2", h.launch().text("title"))
    }

    /**
     * Set up again while the earlier client is fetching the new locale's
     * files of the release it holds: when they arrive, the earlier client
     * puts nothing in use, tells no listener, and reports that it was replaced.
     */
    @Test
    fun aClientReplacedWhileItCompletesTheReleaseHeldPutsNothingInUse() {
        val h = Harness(folder.root)
        h.transport.file("v1", "fr", """{ "title": "Bonjour" }""")
        h.launchWith("en" to """{ "title": "Hello" }""")
        h.transport.status(POINTER_URL, 503)
        val old = h.listeningClient(deviceLocale = "fr") { fallbackLocale("en") }
        h.transport.onRequest = { url ->
            if (url == fileUrl("v1", "fr")) old.close()
        }

        val result = old.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(LocalizeException.Reason.NOT_INITIALIZED, result.error?.reason)
        assertNull(old.version)
        assertTrue(heard.isEmpty())
    }

    /**
     * Replaced late in a refresh: the pointer has failed already, the locale
     * was switched meanwhile, and the client is fetching the new locale's
     * files of the release it holds. Its caller still hears "replaced", not
     * the pointer's error.
     */
    @Test
    fun aClientReplacedAfterThePointerFailedReportsThatItWasReplaced() {
        val h = Harness(folder.root)
        h.transport.file("v1", "fr", """{ "title": "Bonjour" }""")
        h.launchWith("en" to """{ "title": "Hello" }""")
        h.transport.status(POINTER_URL, 503)
        val queued = ArrayList<Runnable>()
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) {
                cdnBaseUrl(CDN)
                fallbackLocale("en")
            },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { queued.add(it) },
            callbacks = { it.run() },
            sleeper = { },
        )
        var switched = false
        h.transport.onRequest = { url ->
            if (url == POINTER_URL && !switched) {
                switched = true
                client.setLocale("fr", null)
            }
            if (url == fileUrl("v1", "fr")) client.close()
        }

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(LocalizeException.Reason.NOT_INITIALIZED, result.error?.reason)
        assertNull(client.version)
    }

    /**
     * The app leaves a locale the release has no file for while the refresh
     * is still under way, for one the release does have. What was asked for
     * the locale it left stays what the CDN answered, and is no part of the
     * release: back in that locale the app shows its bundled strings, not the
     * release's fallback locale, and so does the next launch.
     */
    @Test
    fun aLocaleLeftDuringARefreshIsNotAddedToTheReleaseAsEmptyFiles() {
        for (policy in UpdatePolicy.values()) {
            val root = folder.newFolder()
            val h = Harness(root)
            h.transport.file("v1", "de", """{ "title": "Hallo" }""")
            h.launchWith("en" to """{ "title": "Hello" }""")
            h.transport.status(POINTER_URL, 503)
            val queued = ArrayList<Runnable>()
            val client = LocalizeClient(
                config = LocalizeConfig(SLUG) {
                    cdnBaseUrl(CDN)
                    fallbackLocale("en")
                    updatePolicy(policy)
                },
                cacheRoot = root,
                deviceLocale = "fr",
                transport = h.transport,
                bundled = { _, _ -> null },
                worker = { queued.add(it) },
                callbacks = { it.run() },
                sleeper = { },
            )
            h.transport.onRequest = { url ->
                if (url == POINTER_URL) {
                    h.transport.onRequest = {}
                    client.setLocale("de", null)
                }
            }

            client.refreshNow()
            assertEquals(policy.name, "Hallo", client.text("title"))

            client.setLocale("fr", null)
            assertNull(policy.name, client.version)
            assertEquals(policy.name, "Bonjour (bundled)", client.text("title", fallback = "Bonjour (bundled)"))
            queued.forEach { it.run() }
            assertNull(policy.name, client.version)

            val relaunched = h.launch(deviceLocale = "fr") {
                fallbackLocale("en")
                updatePolicy(policy)
            }
            assertNull(policy.name, relaunched.version)
            assertEquals(policy.name, "Bonjour (bundled)", relaunched.text("title", fallback = "Bonjour (bundled)"))
        }
    }

    /**
     * A file is asked for once in a refresh. One that could not be had is
     * not tried again when the same refresh comes to it a second time, and
     * one that was fetched is not fetched again.
     */
    @Test
    fun aFileIsAskedForOnceInARefreshAlsoWhenItFailed() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello" }""")
        h.transport.file("v1", "de-AT", """{ "title": "Servus" }""")
        h.transport.status(fileUrl("v1", "de"), 503)
        val client = h.launch(deviceLocale = "de-AT")
        h.transport.requests.clear()
        h.sleeps.clear()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(LocalizeException.Reason.SERVER_ERROR, result.error?.reason)
        assertEquals(1, h.transport.requests.count { it == fileUrl("v1", "de-AT") })
        assertEquals(3, h.transport.requests.count { it == fileUrl("v1", "de") })
        assertEquals(listOf(400L, 1200L), h.sleeps.toList())
        assertNull(client.version)
    }

    /**
     * A run that started with nothing stored for a project shows bundled
     * strings to its end, also when the app was set up for another project
     * in between and then for this one again.
     */
    @Test
    fun inNextLaunchModeAReleaseDownloadedInThisRunIsNotShownAfterAnotherProjectWasSetUp() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        val first = h.launch(configure = nextLaunch)
        assertEquals(Status.DOWNLOADED, first.refreshNow().status)

        val other = h.launch(continuing = first, slug = "other", configure = nextLaunch)
        val back = h.launch(continuing = other, configure = nextLaunch)

        assertNull(back.version)
        assertEquals("title", back.text("title"))
        assertEquals(Status.DOWNLOADED, back.refreshNow().status)
        assertNull(back.version)
        // The next launch reads it, offline.
        h.transport.unreachable(POINTER_URL)
        assertEquals("Hello", h.launch(configure = nextLaunch).text("title"))
    }

    /** The same for a run that started with a release: it is the one shown after the way there and back. */
    @Test
    fun inNextLaunchModeTheReleaseTheRunStartedWithIsShownAfterAnotherProjectWasSetUp() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        val first = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        assertEquals(Status.DOWNLOADED, first.refreshNow().status)

        val other = h.launch(continuing = first, slug = "other", configure = nextLaunch)
        val back = h.launch(continuing = other, configure = nextLaunch)

        assertEquals("Hello v1", back.text("title"))
        assertEquals(Status.DOWNLOADED, back.refreshNow().status)
        assertEquals("Hello v1", back.text("title"))
        h.transport.unreachable(POINTER_URL)
        assertEquals("Hello v2", h.launch(configure = nextLaunch).text("title"))
    }

    /**
     * The SDK is set up again at the very moment the earlier client puts a
     * release in use. Either that happens wholly before the client is
     * replaced, and the release is on the device, or not at all: the new
     * client never goes on from a release the device does not name.
     */
    @Test
    fun aClientIsReplacedBeforeOrAfterItPutsAReleaseInUseNeverInBetween() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "Hello v1" }""")
        val old = h.launch()
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "Hello v2" }""")
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        // The lock every write to the device takes. While the test holds it,
        // the refresh stands still where it stores the release it has just
        // decided to put in use.
        val diskLock = ReleaseStore::class.java.getDeclaredField("DISK_LOCK").apply { isAccessible = true }.get(null)!!
        var replacement: LocalizeClient? = null
        val refreshing = Thread { old.refreshNow() }
        val replacing = Thread { replacement = h.launch(continuing = old, configure = nextLaunch) }
        synchronized(diskLock) {
            refreshing.start()
            awaitBlocked(refreshing)
            replacing.start()
            awaitBlocked(replacing)
        }
        refreshing.join(10_000)
        replacing.join(10_000)

        h.transport.unreachable(POINTER_URL)
        val onDevice = h.launch(configure = nextLaunch).version
        assertEquals(onDevice, replacement!!.version)
    }

    private fun awaitBlocked(thread: Thread) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (thread.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < deadline) { "the thread never came to wait: ${thread.state}" }
            Thread.sleep(1)
        }
    }

    /**
     * A result waits its turn on the main thread. When the SDK was set up
     * again before it got there, it tells of a client that is no longer the
     * one in use, and says only that.
     */
    @Test
    fun aResultDeliveredAfterTheClientWasReplacedSaysItWasReplaced() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val waiting = ArrayList<Runnable>()
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { waiting.add(it) },
            sleeper = { },
        )
        val results = ArrayList<RefreshResult>()

        client.refresh(results::add)
        client.close()
        waiting.forEach { it.run() }

        assertEquals(Status.FAILED, results.single().status)
        assertEquals(LocalizeException.Reason.NOT_INITIALIZED, results.single().error?.reason)
    }

    /**
     * The same for listeners: a change that waits its turn on the main
     * thread is of a client that was replaced by the time it gets there, and
     * says nothing of the texts the app shows now.
     */
    @Test
    fun aChangeHeardAfterTheClientWasReplacedIsNotPassedOn() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val waiting = ArrayList<Runnable>()
        val listeners = CopyOnWriteArrayList<LocalizeListener>()
        listeners.add(LocalizeListener { version -> heard.add(version) })
        val client = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { waiting.add(it) },
            listeners = listeners,
            sleeper = { },
        )
        assertEquals(Status.APPLIED, client.refreshNow().status)

        client.close()
        waiting.forEach { it.run() }

        assertTrue(heard.isEmpty())
    }

    /**
     * A listener may set the SDK up again as it hears of a change. The
     * listeners after it are then told nothing of the client that was
     * replaced: its version is not the one of the texts the app shows now.
     */
    @Test
    fun aListenerThatSetsTheSdkUpAgainEndsTheTellingOfTheOldClient() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val listeners = CopyOnWriteArrayList<LocalizeListener>()
        lateinit var client: LocalizeClient
        listeners.add(LocalizeListener { client.close() })
        listeners.add(LocalizeListener { version -> heard.add(version) })
        client = LocalizeClient(
            config = LocalizeConfig(SLUG) { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { it.run() },
            listeners = listeners,
            sleeper = { },
        )

        client.refreshNow()

        assertTrue(heard.isEmpty())
    }

    @Test
    fun aReplacedClientChangesNothingFurther() {
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.listeningClient()
        client.close()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(LocalizeException.Reason.NOT_INITIALIZED, result.error?.reason)
        assertTrue(h.transport.requests.isEmpty())
        assertTrue(heard.isEmpty())
    }
}
