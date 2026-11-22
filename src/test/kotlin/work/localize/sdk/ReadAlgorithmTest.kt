package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import work.localize.sdk.LocalizeException.Reason
import work.localize.sdk.RefreshResult.Status

class ReadAlgorithmTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun harness() = Harness(folder.root)

    @Test
    fun resolvesThePointerOnceAndReadsEveryFileFromThatVersion() {
        val h = harness()
        h.transport.pointer("v12")
        for (locale in listOf("en-FI", "en", "et")) {
            for (namespace in listOf("common", "checkout")) {
                h.transport.file("v12", locale, """{ "id": "$locale/$namespace" }""", namespace)
            }
        }
        val client = h.launch("en-FI") {
            namespaces("common", "checkout")
            fallbackLocale("et")
        }

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("v12", result.version)
        assertEquals(1, h.transport.pointerRequests)
        assertEquals(
            listOf(
                fileUrl("v12", "en-FI", "common"), fileUrl("v12", "en-FI", "checkout"),
                fileUrl("v12", "en", "common"), fileUrl("v12", "en", "checkout"),
                fileUrl("v12", "et", "common"), fileUrl("v12", "et", "checkout"),
            ),
            h.transport.fileRequests,
        )
        assertEquals("v12", client.version)
        assertEquals("en-FI/checkout", client.text("id", namespace = "checkout"))
    }

    @Test
    fun aPublishLandingMidRefreshDoesNotMixVersions() {
        val h = harness()
        h.transport.pointer("v1")
        for (version in listOf("v1", "v2")) {
            for (locale in listOf("en-FI", "en")) {
                h.transport.file(version, locale, """{ "title": "$version", "$locale": "$version" }""")
            }
        }
        // The pointer moves to v2 as soon as the first file has been asked for.
        h.transport.onRequest = { url -> if (url != POINTER_URL) h.transport.pointer("v2") }
        val client = h.launch("en-FI")

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("v1", result.version)
        assertEquals(1, h.transport.pointerRequests)
        assertEquals(listOf(fileUrl("v1", "en-FI"), fileUrl("v1", "en")), h.transport.fileRequests)
        assertEquals("v1", client.text("title"))
        assertEquals("v1", client.text("en"))

        // The next refresh sees the new pointer and moves to v2 as a whole.
        assertEquals("v2", client.refreshNow().version)
        assertEquals("v2", client.text("title"))
        assertEquals("v2", client.text("en"))
    }

    @Test
    fun anInvalidPointerIsAConfigurationErrorAndIsNotRetried() {
        val bodies = listOf(
            "not json", "", "[]", "\"v3\"", "{}", """{ "version": 3 }""", """{ "version": null }""",
            """{ "version": "latest" }""", """{ "version": "3" }""", """{ "version": "v0" }""",
            """{ "version": "v03" }""", """{ "version": "v3 " }""", """{ "version": "v3\n" }""",
            """{ "version": "V3" }""", """{ "version": "v3/../v2" }""", """{ "version": "v-1" }""",
            """{ "version": "v1.5" }""", """{ "version": "v٣" }""",
        )
        for (body in bodies) {
            val h = Harness(folder.newFolder())
            h.transport.pointerBody(body)
            val client = h.launch()

            val result = client.refreshNow()

            assertEquals(body, Status.FAILED, result.status)
            assertEquals(body, Reason.INVALID_POINTER, result.error?.reason)
            assertFalse(body, result.error!!.isRetryable)
            assertEquals(body, listOf(POINTER_URL), h.transport.requests)
            assertTrue(body, h.sleeps.isEmpty())
            assertNull(body, client.version)
        }
    }

    @Test
    fun aMissingPointerMeansNothingIsPublishedAndIsNotRetried() {
        val h = harness()
        val client = h.launch()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(Reason.HTTP_ERROR, result.error?.reason)
        assertEquals(404, result.error?.status)
        assertEquals(POINTER_URL, result.error?.url)
        assertFalse(result.error!!.isRetryable)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun aPinnedVersionSkipsThePointer() {
        val h = harness()
        h.transport.pointer("v9")
        h.transport.file("v4", "en", """{ "title": "four" }""")
        h.transport.file("v9", "en", """{ "title": "nine" }""")
        val client = h.launch { version("v4") }

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("v4", result.version)
        assertEquals(listOf(fileUrl("v4", "en")), h.transport.requests)
        assertEquals("four", client.text("title"))
    }

    @Test
    fun aPinnedVersionThatIsNotPublishedYetIsNotStoredAsEmpty() {
        val h = harness()
        val client = h.launch("en-FI") { version("v5") }

        val early = client.refreshNow()

        assertEquals(Status.FAILED, early.status)
        assertEquals("v5", early.version)
        assertEquals(Reason.HTTP_ERROR, early.error?.reason)
        assertEquals(404, early.error?.status)
        assertFalse(early.error!!.isRetryable)
        assertNull(client.version)
        assertNull(h.launch("en-FI") { version("v5") }.version)

        // Once the release exists it is picked up, variant 404 and all.
        h.transport.file("v5", "en", """{ "title": "five" }""")
        assertEquals(Status.APPLIED, client.refreshNow().status)
        assertEquals("five", client.text("title"))
    }

    @Test
    fun aLatestReleaseWithoutFilesForTheLocaleIsAnEmptyRelease() {
        // The pointer proves the release exists; it just has nothing in Japanese.
        val h = harness()
        h.transport.pointer("v1")
        val client = h.launch("ja")

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals("v1", client.version)
        assertEquals("title", client.text("title"))
        h.transport.requests.clear()
        assertEquals(Status.UP_TO_DATE, client.refreshNow().status)
        assertEquals(listOf(POINTER_URL), h.transport.requests)
    }

    @Test
    fun aMissingVariantFileIsEmptyAndTheBaseLanguageSuppliesTheText() {
        val h = harness()
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Colour", "body": "Base" }""")
        // No en-FI file at all: the variant overrides nothing in this namespace.
        val client = h.launch("en-FI")

        val result = client.refreshNow()

        assertEquals(Status.APPLIED, result.status)
        assertEquals("Colour", client.text("title"))
        assertTrue(h.sleeps.isEmpty())
        // The empty file is remembered, so the 404 is not asked for again.
        h.transport.requests.clear()
        assertEquals(Status.UP_TO_DATE, client.refreshNow().status)
        assertEquals(listOf(POINTER_URL), h.transport.requests)
    }

    @Test
    fun aVariantFileOverridesOnlyWhatItContains() {
        val h = harness()
        val client = h.launchWith(
            "en-FI" to """{ "title": "Väri" }""",
            "en" to """{ "title": "Colour", "body": "Base" }""",
        )

        assertEquals("Väri", client.text("title"))
        assertEquals("Base", client.text("body"))
    }

    @Test
    fun aLocaleTheProjectDoesNotHaveFallsBackToTheFallbackLocale() {
        val h = harness()
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "Hello" }""")
        val client = h.launch("ja-JP") { fallbackLocale("en") }

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals(listOf(fileUrl("v1", "ja-JP"), fileUrl("v1", "ja"), fileUrl("v1", "en")), h.transport.fileRequests)
        assertEquals("Hello", client.text("title"))
    }

    @Test
    fun aServerErrorOnOneFileFailsTheRefreshAndKeepsTheReleaseInUse() {
        val h = harness()
        val client = h.launchWith(
            "en-FI" to """{ "title": "one" }""",
            "en" to """{ "title": "one-base", "body": "one" }""",
        )
        h.transport.pointer("v2")
        h.transport.file("v2", "en-FI", """{ "title": "two" }""")
        h.transport.status(fileUrl("v2", "en"), 503)
        h.transport.requests.clear()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals("v2", result.version)
        assertEquals(Reason.SERVER_ERROR, result.error?.reason)
        assertEquals(503, result.error?.status)
        assertTrue(result.error!!.isRetryable)
        // Asked three times in all, with a wait before each repeat.
        assertEquals(3, h.transport.requests.count { it == fileUrl("v2", "en") })
        assertEquals(ReleaseLoader.RETRY_DELAYS_MILLIS.toList(), h.sleeps.toList())
        // All or nothing: the v2 file that did arrive is not used.
        assertEquals("v1", client.version)
        assertEquals("one", client.text("title"))
        assertEquals("one", client.text("body"))
    }

    @Test
    fun aLostConnectionIsRetriedAndThenFailsAsRetryable() {
        val h = harness()
        h.transport.unreachable(POINTER_URL)
        val client = h.launch()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(Reason.NETWORK, result.error?.reason)
        assertTrue(result.error!!.isRetryable)
        assertNull(result.version)
        assertEquals(3, h.transport.pointerRequests)
    }

    @Test
    fun aRequestThatSucceedsOnTheSecondAttemptIsEnough() {
        val h = harness()
        h.transport.pointer("v1")
        var attempts = 0
        h.transport.on(fileUrl("v1", "en")) {
            if (++attempts == 1) LocalizeResponse(502, "") else LocalizeResponse(200, """{ "title": "ok" }""")
        }
        val client = h.launch()

        assertEquals(Status.APPLIED, client.refreshNow().status)

        assertEquals("ok", client.text("title"))
        assertEquals(listOf(ReleaseLoader.RETRY_DELAYS_MILLIS[0]), h.sleeps.toList())
    }

    @Test
    fun aRefusedFileIsNotRetried() {
        val h = harness()
        h.transport.pointer("v1")
        h.transport.status(fileUrl("v1", "en"), 403)
        val client = h.launch()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(Reason.HTTP_ERROR, result.error?.reason)
        assertFalse(result.error!!.isRetryable)
        assertEquals(1, h.transport.fileRequests.size)
        assertNull(client.version)
    }

    @Test
    fun aFileThatIsNotAJsonObjectFailsTheRefresh() {
        for (body in listOf("<html>captive portal</html>", "[1, 2]", "\"text\"", "{ \"a\": ", "")) {
            val h = Harness(folder.newFolder())
            h.transport.pointer("v1")
            h.transport.file("v1", "en", body)
            val client = h.launch()

            val result = client.refreshNow()

            assertEquals(body, Status.FAILED, result.status)
            assertEquals(body, Reason.INVALID_RESPONSE, result.error?.reason)
            assertFalse(body, result.error!!.isRetryable)
            assertEquals(body, 1, h.transport.fileRequests.size)
            assertNull(body, client.version)
        }
    }

    @Test
    fun aTransportThatThrowsSomethingUnexpectedFailsTheRefreshWithoutCrashing() {
        val h = harness()
        h.transport.on(POINTER_URL) { throw IllegalStateException("broken client") }
        val client = h.launch()

        val result = client.refreshNow()

        assertEquals(Status.FAILED, result.status)
        assertEquals(Reason.NETWORK, result.error?.reason)
        assertEquals(1, h.transport.requests.size)
    }

    @Test
    fun aSecondRefreshOfTheSameReleaseDownloadsNothing() {
        val h = harness()
        val client = h.launchWith("en" to """{ "title": "one" }""")
        h.transport.requests.clear()

        val result = client.refreshNow()

        assertEquals(Status.UP_TO_DATE, result.status)
        assertEquals("v1", result.version)
        assertEquals(listOf(POINTER_URL), h.transport.requests)
    }

    @Test
    fun addressesAreBuiltLikeTheJavaScriptSdkBuildsThem() {
        assertEquals("https://cdn.localize.work/my-app/latest.json", CdnUrls.pointer("https://cdn.localize.work/", "my-app"))
        assertEquals(
            "https://cdn.localize.work/my-app/v12/en-FI/common.json",
            CdnUrls.file("https://cdn.localize.work//", "my-app", "v12", "en-FI", "common"),
        )
        assertEquals(
            "http://10.0.2.2:3000/cdn/a%20b%2Fc/v1/zh-Hant-TW/sp%C3%A9cial%3F%23.json",
            CdnUrls.file("http://10.0.2.2:3000/cdn", "a b/c", "v1", "zh-Hant-TW", "spécial?#"),
        )
        assertEquals("-_.!~*'()", CdnUrls.encodeSegment("-_.!~*'()"))
    }

    @Test
    fun refreshRunsOnTheWorkerAndReportsThroughTheCallback() {
        val h = harness()
        h.transport.pointer("v1")
        h.transport.file("v1", "en", """{ "title": "one" }""")
        val client = h.launch()
        val results = ArrayList<RefreshResult>()

        client.refresh { results.add(it) }
        client.refresh(null)

        assertEquals(1, results.size)
        assertTrue(results[0].isSuccess)
        assertEquals(Status.APPLIED, results[0].status)
    }
}
