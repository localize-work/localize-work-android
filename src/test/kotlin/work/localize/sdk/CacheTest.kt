package work.localize.sdk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import work.localize.sdk.LocalizeException.Reason
import work.localize.sdk.RefreshResult.Status

class CacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun projectFolder() = File(folder.root, SLUG)

    private fun offline(h: Harness) {
        h.transport.unreachable(POINTER_URL)
        h.transport.requests.clear()
    }

    @Test
    fun theLastReleaseIsAvailableAtStartWithoutANetwork() {
        val h = Harness(folder.root)
        h.launchWith(
            "en-FI" to """{ "title": "Väri" }""",
            "en" to """{ "title": "Colour", "items_one": "{{count}} item", "items_other": "{{count}} items" }""",
        )
        offline(h)

        val nextLaunch = h.launch("en-FI")

        // Before any request: the texts come from the device.
        assertTrue(h.transport.requests.isEmpty())
        assertEquals("v1", nextLaunch.version)
        assertEquals("Väri", nextLaunch.text("title"))
        assertEquals("3 items", nextLaunch.text("items", count = 3))
        // And a refresh that cannot reach the CDN leaves them in place.
        assertEquals(Status.FAILED, nextLaunch.refreshNow().status)
        assertEquals("Väri", nextLaunch.text("title"))
    }

    @Test
    fun aCorruptReleaseFileIsDiscardedInsteadOfCrashing() {
        for (garbage in listOf("", "{", "not json at all", "[]", "{}", """{ "format": 1, "version": "v1" }""",
            """{ "format": 1, "version": "v2", "files": {} }""", """{ "format": 2, "version": "v1", "files": {} }""",
            """{ "format": 1, "version": "v1", "files": { "en": "text" } }""",
            """{ "format": 1, "version": "v1", "files": { "en": { "translation": [] } } }""",
            "\u0000\u0001\u0002", "[".repeat(100_000))) {
            val root = folder.newFolder()
            val h = Harness(root)
            h.launchWith("en" to """{ "title": "one" }""")
            File(File(root, SLUG), "v1.json").writeText(garbage)

            val client = h.launch()

            assertNull(garbage.take(40), client.version)
            assertEquals("title", client.text("title"))
            assertFalse(File(root, SLUG).exists())
            // The next download repairs it.
            assertEquals(Status.APPLIED, client.refreshNow().status)
            assertEquals("one", client.text("title"))
        }
    }

    @Test
    fun aCorruptStateFileIsDiscardedInsteadOfCrashing() {
        for (garbage in listOf("", "{", "null", "[]", "{}", """{ "format": 1, "cdnBaseUrl": "$CDN", "active": "../v1" }""",
            """{ "format": 1, "cdnBaseUrl": "$CDN", "active": 1 }""",
            """{ "format": 1, "cdnBaseUrl": "$CDN", "active": "v7" }""",
            """{ "format": 1, "cdnBaseUrl": "$CDN", "active": null, "pending": null }""",
            """{ "format": 1, "cdnBaseUrl": "$CDN", "active": "v1", "pending": {} }""")) {
            val root = folder.newFolder()
            val h = Harness(root)
            h.launchWith("en" to """{ "title": "one" }""")
            File(File(root, SLUG), ReleaseStore.STATE_FILE).writeText(garbage)

            val client = h.launch()

            assertNull(garbage, client.version)
            assertEquals("title", client.text("title"))
            assertFalse(garbage, File(root, SLUG).exists())
        }
    }

    @Test
    fun aCacheFolderThatIsNotAFolderDoesNotCrash() {
        File(folder.root, SLUG).writeText("a file where the folder should be")
        val h = Harness(folder.root)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")

        val client = h.launch()

        assertNull(client.version)
        assertEquals(Status.APPLIED, client.refreshNow().status)
        assertEquals("one", client.text("title"))
    }

    @Test
    fun anUnwritableCacheStillAppliesTheReleaseForThisRun() {
        val notAFolder = folder.newFile("blocked")
        val h = Harness(notAFolder)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.launch()

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals("one", client.text("title"))
        assertNull(h.launch().version)
    }

    /**
     * The device could not take the release, so it is in use from memory.
     * The next refresh finds nothing newer, and stores what the app holds:
     * the next launch has it without a connection.
     */
    @Test
    fun aReleaseThatCouldNotBeStoredIsStoredByTheNextRefresh() {
        val blocked = folder.newFile("blocked")
        val h = Harness(blocked)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.launch()
        assertEquals(Status.APPLIED, client.refreshNow().status)
        assertNull(h.launch().version)

        assertTrue(blocked.delete())
        assertEquals(Status.UP_TO_DATE, client.refreshNow().status)

        h.transport.unreachable(POINTER_URL)
        assertEquals("one", h.launch().text("title"))
        assertEquals(1, h.transport.requests.count { it == fileUrl("v1", "en") })
    }

    @Test
    fun oldVersionsAreRemovedOnceANewOneIsInUse() {
        val h = Harness(folder.root)
        val client = h.launchWith("en" to """{ "title": "one" }""")
        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())
        File(projectFolder(), "v0.json.tmp").writeText("left over from a crash")
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals(setOf("state.json", "v2.json"), projectFolder().list()!!.toSet())
        assertEquals("two", h.launch().text("title"))
    }

    @Test
    fun aRollbackOnTheServerIsFollowed() {
        val h = Harness(folder.root)
        val client = h.launchWith("en" to """{ "title": "one" }""")
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals("v2", client.refreshNow().version)

        h.transport.pointer("v1")
        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("v1", client.version)
        assertEquals("one", client.text("title"))
    }

    @Test
    fun aCacheFromAnotherCdnIsNotUsed() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "production" }""")

        val staging = h.launch { cdnBaseUrl("https://staging.test") }

        assertNull(staging.version)
        assertEquals("title", staging.text("title"))
    }

    @Test
    fun projectsDoNotShareACache() {
        val h = Harness(folder.root)
        h.launchWith("en" to """{ "title": "demo" }""")
        val other = LocalizeClient(
            config = LocalizeConfig("other") { cdnBaseUrl(CDN) },
            cacheRoot = folder.root,
            deviceLocale = "en",
            transport = h.transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { it.run() },
        )

        assertNull(other.version)
        assertEquals("v1", h.launch().version)
    }

    @Test
    fun aSlugCannotLeaveTheCacheFolder() {
        assertEquals("my-app_2", ReleaseStore.directoryName("my-app_2"))
        assertEquals("%2E%2E%2F%2E%2E%2Fescape", ReleaseStore.directoryName("../../escape"))
        assertEquals("C%3A%5Cdrive", ReleaseStore.directoryName("C:\\drive"))
        assertEquals("%2E", ReleaseStore.directoryName("."))
        assertEquals("caf%C3%A9%00", ReleaseStore.directoryName("café\u0000"))

        val root = folder.newFolder("cache")
        val transport = FakeTransport()
        val slug = "../outside"
        transport.on("$CDN/..%2Foutside/latest.json") { LocalizeResponse(200, """{ "version": "v1" }""") }
        transport.on("$CDN/..%2Foutside/v1/en/translation.json") { LocalizeResponse(200, """{ "a": "b" }""") }
        val client = LocalizeClient(
            config = LocalizeConfig(slug) { cdnBaseUrl(CDN) },
            cacheRoot = root,
            deviceLocale = "en",
            transport = transport,
            bundled = { _, _ -> null },
            worker = { it.run() },
            callbacks = { it.run() },
        )

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals(listOf("%2E%2E%2Foutside"), root.list()!!.toList())
        assertEquals(listOf("cache"), folder.root.list()!!.toList())
    }

    @Test
    fun nextLaunchModeStoresTheReleaseAndUsesItFromTheNextStart() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")

        // First run ever: the download is stored, the screen keeps what it shows.
        val firstRun = h.launch(configure = nextLaunch)
        val first = firstRun.refreshNow()
        assertEquals(Status.DOWNLOADED, first.status)
        assertEquals("v1", first.version)
        assertNull(firstRun.version)
        assertEquals("title", firstRun.text("title"))

        // Second run: v1 is in use from the start; v2 arrives and waits.
        val secondRun = h.launch(configure = nextLaunch)
        assertEquals("v1", secondRun.version)
        assertEquals("one", secondRun.text("title"))
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, secondRun.refreshNow().status)
        assertEquals("v1", secondRun.version)
        assertEquals("one", secondRun.text("title"))
        assertEquals(setOf("state.json", "v1.json", "v2.json"), projectFolder().list()!!.toSet())

        // Asking again in the same run downloads nothing more.
        h.transport.requests.clear()
        assertEquals(Status.DOWNLOADED, secondRun.refreshNow().status)
        assertEquals(listOf(POINTER_URL), h.transport.requests)

        // Third run, offline: v2 is in use and v1 is gone.
        offline(h)
        val thirdRun = h.launch(configure = nextLaunch)
        assertEquals("v2", thirdRun.version)
        assertEquals("two", thirdRun.text("title"))
        assertEquals(setOf("state.json", "v2.json"), projectFolder().list()!!.toSet())
    }

    @Test
    fun nextLaunchModeForgetsAWaitingReleaseThatWasRolledBack() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, run.refreshNow().status)

        h.transport.pointer("v1")
        assertEquals(Status.UP_TO_DATE, run.refreshNow().status)

        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())
        assertEquals("one", h.launch(configure = nextLaunch).text("title"))
    }

    @Test
    fun aRollbackThatCouldNotBeWrittenIsWrittenByTheNextRefresh() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, run.refreshNow().status)

        // The release is rolled back while the device cannot be written to.
        h.transport.pointer("v1")
        val blocker = File(projectFolder(), "state.json.tmp")
        assertTrue(blocker.mkdir())
        assertEquals(Status.UP_TO_DATE, run.refreshNow().status)
        assertEquals(setOf("state.json", "v1.json", "v2.json"), projectFolder().list()!!.toSet() - "state.json.tmp")
        // The device can be written again; the next refresh makes up for it.
        blocker.delete()
        assertEquals(Status.UP_TO_DATE, run.refreshNow().status)

        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())

        offline(h)
        val next = h.launch(configure = nextLaunch)
        assertEquals("v1", next.version)
        assertEquals("one", next.text("title"))
    }

    @Test
    fun aStoredReleaseWithoutTheCurrentLocaleIsNotReadUntilItIsWhole() {
        val h = Harness(folder.root)
        h.transport.file("v1", "de", """{ "title": "Hallo" }""")
        h.launchWith("en" to """{ "title": "Hello" }""")
        h.bundledResources["translation_title"] = "Hallo (gebündelt)"

        // The device language is German now, and there is no connection.
        offline(h)
        h.transport.unreachable(fileUrl("v1", "de"))
        val german = h.launch(deviceLocale = "de") { fallbackLocale("en") }
        assertNull(german.version)
        assertEquals("Hallo (gebündelt)", german.text("title"))
        assertEquals(Status.FAILED, german.refreshNow().status)
        assertEquals("Hallo (gebündelt)", german.text("title"))

        // Back online, the missing file completes the release that is there.
        h.transport.pointer("v1")
        h.transport.file("v1", "de", """{ "title": "Hallo" }""")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        h.transport.requests.clear()
        assertEquals(Status.APPLIED, german.refreshNow().status)
        // The release that is there is completed first, from its own version;
        // the pointer then confirms it is still the published one.
        assertEquals(listOf(fileUrl("v1", "de"), POINTER_URL), h.transport.requests)
        assertEquals("Hallo", german.text("title"))
        // An English launch still reads it.
        assertEquals("Hello", h.launch().text("title"))
    }

    @Test
    fun aStoredReleaseOfAnotherVersionIsNotUsedUnderAPin() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, run.refreshNow().status)
        offline(h)

        // Pinned to a release the device does not have: nothing is read.
        val elsewhere = h.launch { version("v7") }
        assertNull(elsewhere.version)
        assertEquals("title", elsewhere.text("title"))
        // Pinned to the one in use, with a newer one waiting: the pin wins.
        val pinnedToFirst = h.launch(configure = { nextLaunch(); version("v1") })
        assertEquals("v1", pinnedToFirst.version)
        assertEquals("one", pinnedToFirst.text("title"))
    }

    @Test
    fun aPinToTheWaitingReleaseTakesIt() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, run.refreshNow().status)
        offline(h)

        val pinned = h.launch { version("v2") }
        assertEquals("v2", pinned.version)
        assertEquals("two", pinned.text("title"))
    }

    /**
     * A release waits for the next launch. The app is then set up pinned to
     * the version in use, which shares the folder and leaves only that
     * version there, and after that as before. What the device holds decides
     * whether a release waits: it is stored again, and reported as
     * downloaded only then.
     */
    @Test
    fun aWaitingReleaseTheDeviceNoLongerHoldsIsStoredAgain() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, run.refreshNow().status)

        val preview = h.launch(continuing = run) { nextLaunch(); version("v1") }
        assertEquals("one", preview.text("title"))
        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())
        val back = h.launch(continuing = preview, configure = nextLaunch)
        assertEquals("one", back.text("title"))
        h.transport.requests.clear()

        assertEquals(Status.DOWNLOADED, back.refreshNow().status)

        assertEquals("one", back.text("title"))
        assertEquals(setOf("state.json", "v1.json", "v2.json"), projectFolder().list()!!.toSet())
        // From memory: nothing but the pointer is asked for.
        assertEquals(listOf(POINTER_URL), h.transport.requests)
        offline(h)
        assertEquals("two", h.launch(configure = nextLaunch).text("title"))
    }

    /**
     * The same for the release in use: a preview pinned to another version
     * left that one on the device. The run still reads its own release, and
     * an up-to-date refresh puts it back for the next launch.
     */
    @Test
    fun theReleaseInUseTheDeviceNoLongerHoldsIsStoredAgain() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.file("v3", "en", """{ "title": "three" }""")
        val preview = h.launch(continuing = run) { version("v3") }
        assertEquals(Status.APPLIED, preview.refreshNow().status)
        assertEquals(setOf("state.json", "v3.json"), projectFolder().list()!!.toSet())

        val back = h.launch(continuing = preview, configure = nextLaunch)
        assertEquals("one", back.text("title"))
        assertEquals(Status.UP_TO_DATE, back.refreshNow().status)

        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())
        offline(h)
        assertEquals("one", h.launch(configure = nextLaunch).text("title"))
    }

    /**
     * Files of a version never change, so a write keeps the ones the device
     * already has of it: those another configuration of the app stored, for
     * another locale say.
     */
    @Test
    fun filesAlreadyStoredOfAReleaseAreKeptWhenMoreAreWritten() {
        fun release(locale: String) =
            Release("v1", mapOf(locale to mapOf("translation" to mapOf<String, Any?>("title" to locale))))
        assertTrue(ReleaseStore(folder.root, SLUG, CDN).save("v1", null, release("en")))

        assertTrue(ReleaseStore(folder.root, SLUG, CDN).save("v1", null, release("de")))

        val stored = ReleaseStore(folder.root, SLUG, CDN).load()!!
        assertTrue(stored.has("en", "translation"))
        assertTrue(stored.has("de", "translation"))
    }

    /**
     * The stored files are there but cannot be read when the app starts.
     * They are left alone, and the run has not started with nothing: it
     * could not look. Set up again once they can be read, it starts from the
     * stored release.
     */
    @Test
    fun aStoredReleaseThatCouldNotBeReadAtFirstIsTheOneTheRunStartsWith() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val state = File(projectFolder(), ReleaseStore.STATE_FILE)
        assertTrue(state.setReadable(false))
        offline(h)

        val locked = h.launch(configure = nextLaunch)
        assertNull(locked.version)
        assertEquals("title", locked.text("title"))
        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())

        assertTrue(state.setReadable(true))
        val unlocked = h.launch(continuing = locked, configure = nextLaunch)

        assertEquals("v1", unlocked.version)
        assertEquals("one", unlocked.text("title"))
    }

    /**
     * The same without setting the SDK up again: the next refresh reads the
     * device once more, and what it finds is the release of this run. A
     * newer one still waits for the next launch.
     */
    @Test
    fun aRefreshStartsFromAStoredReleaseThatCouldNotBeReadAtFirst() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val state = File(projectFolder(), ReleaseStore.STATE_FILE)
        assertTrue(state.setReadable(false))
        val client = h.launch(configure = nextLaunch)
        assertNull(client.version)

        assertTrue(state.setReadable(true))
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, client.refreshNow().status)

        assertEquals("v1", client.version)
        assertEquals("one", client.text("title"))
        // It is the run's release for a client that follows, too.
        assertEquals("one", h.launch(continuing = client, configure = nextLaunch).text("title"))
        offline(h)
        assertEquals("two", h.launch(configure = nextLaunch).text("title"))
    }

    /**
     * A run that stored a release over files it could never read has nothing
     * of the device to start from: what it stored waits for the next launch.
     */
    @Test
    fun aReleaseStoredOverFilesThatCouldNotBeReadWaitsForTheNextLaunch() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        assertTrue(File(projectFolder(), ReleaseStore.STATE_FILE).setReadable(false))
        val client = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, client.refreshNow().status)
        assertEquals(setOf("state.json", "v2.json"), projectFolder().list()!!.toSet())

        assertEquals(Status.DOWNLOADED, client.refreshNow().status)

        assertNull(client.version)
        assertEquals("title", client.text("title"))
        offline(h)
        assertEquals("two", h.launch(configure = nextLaunch).text("title"))
    }

    /**
     * Files of the release the run already reads change nothing on screen,
     * so they are used although the device cannot take them now. The next
     * refresh stores them.
     */
    @Test
    fun nextLaunchModeUsesFilesOfTheReleaseInUseThatCouldNotBeStored() {
        val h = Harness(folder.root)
        val french: LocalizeConfig.Builder.() -> Unit = {
            updatePolicy(UpdatePolicy.NEXT_LAUNCH)
            locale("fr")
            fallbackLocale("en")
        }
        h.launchWith("en" to """{ "title": "one" }""")
        val client = h.launch(configure = french)
        assertNull(client.version)
        assertTrue(projectFolder().setWritable(false))
        try {
            assertEquals(Status.APPLIED, client.refreshNow().status)
            assertEquals("v1", client.version)
            assertEquals("one", client.text("title"))
        } finally {
            assertTrue(projectFolder().setWritable(true))
        }

        assertEquals(Status.UP_TO_DATE, client.refreshNow().status)
        offline(h)
        val next = h.launch(configure = french)
        assertEquals("v1", next.version)
        assertEquals("one", next.text("title"))
    }

    @Test
    fun nextLaunchModeReportsAReleaseItCouldNotStore() {
        val notAFolder = folder.newFile("blocked")
        val h = Harness(notAFolder)
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.launch { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(Reason.STORAGE, result.error?.reason)
        assertNull(client.version)
    }

    @Test
    fun aWaitingReleaseThatCannotBeReadLeavesTheOneInUse() {
        val h = Harness(folder.root)
        val nextLaunch: LocalizeConfig.Builder.() -> Unit = { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        h.launchWith("en" to """{ "title": "one" }""")
        val run = h.launch(configure = nextLaunch)
        h.transport.pointer("v2")
        h.transport.file("v2", "en", """{ "title": "two" }""")
        assertEquals(Status.DOWNLOADED, run.refreshNow().status)
        File(projectFolder(), "v2.json").writeText("{ truncated")

        val next = h.launch(configure = nextLaunch)

        assertEquals("v1", next.version)
        assertEquals("one", next.text("title"))
        assertEquals(setOf("state.json", "v1.json"), projectFolder().list()!!.toSet())
    }

    /** A store that was closed writes nothing, and what is on the device stays. */
    @Test
    fun aClosedStoreWritesNothing() {
        val files = mapOf("en" to mapOf("translation" to mapOf<String, Any?>("title" to "one")))
        val first = ReleaseStore(folder.root, SLUG, CDN)
        assertTrue(first.save("v1", null, Release("v1", files)))
        first.close()

        assertFalse(first.save("v2", null, Release("v2", files)))

        val second = ReleaseStore(folder.root, SLUG, CDN)
        assertEquals("v1", second.load()?.version)
        assertEquals(listOf("state.json", "v1.json"), projectFolder().list()!!.sorted())
    }

    @Test
    fun aStoredReleaseSurvivesBeingWrittenAndReadBack() {
        val h = Harness(folder.root)
        val json = """{ "quote": "say \"hi\"\n\ttab \\ slash \u00e9 \ud83d\ude00 \u0001", "a": { "b": "", "c.d": "deep" } }"""
        h.launchWith("en" to json)

        val client = h.launch()

        assertEquals("say \"hi\"\n\ttab \\ slash é 😀 \u0001", client.text("quote"))
        assertEquals("", client.text("a.b"))
        assertEquals("deep", client.text("a.c.d"))
    }

    /** A file nested as deeply as the parser takes from the CDN is still there after a restart. */
    @Test
    fun aFileAtTheNestingLimitIsReadBackFromTheDevice() {
        val h = Harness(folder.root)
        val levels = Json.MAX_DEPTH
        val key = List(levels) { "a" }.joinToString(".")
        val json = "{ \"a\": ".repeat(levels) + "\"deep\"" + " }".repeat(levels)
        h.launchWith("en" to json)
        assertEquals("deep", h.launch().text(key))
        offline(h)

        val nextLaunch = h.launch()

        assertEquals("v1", nextLaunch.version)
        assertEquals("deep", nextLaunch.text(key))
    }
}
