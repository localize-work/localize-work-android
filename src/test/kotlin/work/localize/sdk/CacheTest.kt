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
}
