package work.localize.sdk

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import work.localize.sdk.RefreshResult.Status

class ConcurrencyTest {
    @get:Rule
    val folder = TemporaryFolder()

    /**
     * Three releases whose every text is the release's own name, shaped so
     * that files of two releases read together give a text no release has:
     *
     *     "mix" in v1: the variant file has it          -> "v1"
     *     "mix" in v2: only the base file has it        -> "v2"
     *     "mix" in v3: the variant file has it          -> "v3"
     *
     * The base files of v1 and v3 say "mixed", which a lookup can only reach
     * through the variant file of v2, the one release without the key there.
     */
    private fun publish(transport: FakeTransport) {
        for (version in listOf("v1", "v2", "v3")) {
            val keys = (0 until 50).joinToString(",") { """ "key$it": "$version" """ }
            val variantMix = if (version == "v2") "" else """ "mix": "$version", """
            val baseMix = if (version == "v2") "v2" else "mixed"
            transport.file(version, "en-FI", """{ $variantMix "variant": "$version", "group": { "leaf": "$version" } }""")
            transport.file(
                version,
                "en",
                """{ $keys, "mix": "$baseMix", "group": { "leaf": "base", "other": "$version" },
                     "n_one": "$version", "n_other": "$version" }""",
            )
        }
    }

    @Test
    fun lookupsDuringRefreshesSeeOneWholeReleaseAndNeverFail() {
        val h = Harness(folder.root)
        publish(h.transport)
        h.transport.pointer("v1")
        val client = h.launch("en-FI")
        assertEquals(Status.APPLIED, client.refreshNow().status)

        val valid = setOf("v1", "v2", "v3")
        val stop = AtomicBoolean(false)
        val problems = CopyOnWriteArrayList<String>()
        val lookups = AtomicInteger()
        val seen = CopyOnWriteArrayList<String>()
        val readers = Executors.newFixedThreadPool(6)
        val started = CountDownLatch(6)
        repeat(6) { reader ->
            readers.execute {
                started.countDown()
                try {
                    var round = 0
                    while (!stop.get()) {
                        val results = listOf(
                            client.text("mix"),
                            client.text("key${(round + reader) % 50}"),
                            client.text("variant"),
                            client.text("group.leaf"),
                            client.text("group.other"),
                            client.text("n", mapOf("x" to round), count = round.toLong()),
                        )
                        for (result in results) if (result !in valid) problems.add("unexpected text \"$result\"")
                        if (results[0] !in seen) seen.add(results[0])
                        if (client.text("missing.key", fallback = "fallback") != "fallback") problems.add("fallback lost")
                        lookups.addAndGet(results.size + 1)
                        round++
                    }
                } catch (failure: Throwable) {
                    problems.add("lookup threw $failure")
                }
            }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        // Two threads refresh at once while the pointer keeps moving, with a failed download now and then.
        val refreshers = Executors.newFixedThreadPool(2)
        val refreshes = AtomicInteger()
        val finished = CountDownLatch(2)
        repeat(2) { refresher ->
            refreshers.execute {
                try {
                    for (round in 0 until 300) {
                        val version = "v${1 + (round + refresher) % 3}"
                        if (round % 10 == 9) h.transport.status(POINTER_URL, 500) else h.transport.pointer(version)
                        val result = client.refreshNow()
                        if (result.status == Status.DOWNLOADED) problems.add("unexpected status ${result.status}")
                        refreshes.incrementAndGet()
                    }
                } catch (failure: Throwable) {
                    problems.add("refresh threw $failure")
                } finally {
                    finished.countDown()
                }
            }
        }
        assertTrue(finished.await(60, TimeUnit.SECONDS))
        stop.set(true)
        readers.shutdown()
        refreshers.shutdown()
        assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(emptyList<String>(), problems.distinct())
        assertEquals(600, refreshes.get())
        assertTrue("only ${lookups.get()} lookups ran", lookups.get() > 1_000)
        assertTrue("the readers only ever saw $seen", seen.size >= 2)
        assertTrue(client.version in valid)
    }

    @Test
    fun theMixedTextIsReachableOnlyByMixingReleases() {
        // Guards the test above: if files of v2 and v1 were ever read
        // together, this is the text a lookup of "mix" would return.
        val h = Harness(folder.root)
        h.transport.pointer("v9")
        h.transport.file("v9", "en-FI", """{ "variant": "v2" }""")
        h.transport.file("v9", "en", """{ "mix": "mixed" }""")
        val client = h.launch("en-FI")
        client.refreshNow()

        assertEquals("mixed", client.text("mix"))
    }
}
