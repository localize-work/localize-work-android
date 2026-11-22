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

        // v2 still waits, and the Estonian file comes from v1, the release on screen.
        assertEquals(Status.DOWNLOADED, results.single().status)
        assertEquals(listOf(fileUrl("v1", "et"), POINTER_URL, fileUrl("v2", "et")), h.transport.requests)
        assertEquals("v1", client.version)
        assertEquals("Tere v1", client.text("title"))
        assertEquals(listOf<String?>(null, "v1"), heard.toList())

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
