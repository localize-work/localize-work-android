package work.localize.sdk

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The default transport against a real HTTP server on this machine. */
class TransportTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/demo/v1/en/translation.json") { exchange ->
            val body = """{ "title": "Tere, maailm" }""".toByteArray(Charsets.UTF_8)
            headers["user-agent"] = exchange.requestHeaders.getFirst("User-Agent")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/demo/v1/et/translation.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        start()
    }
    private val headers = HashMap<String, String?>()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @After
    fun stop() = server.stop(0)

    @Test
    fun aFileIsReadAndAMissingOneIsAnAnswerNotAFailure() {
        val transport = HttpUrlConnectionTransport(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

        val found = transport.get("$base/demo/v1/en/translation.json")
        assertEquals(200, found.status)
        assertEquals("""{ "title": "Tere, maailm" }""", found.body)
        // The request names the SDK, not the device.
        assertEquals(HttpUrlConnectionTransport.USER_AGENT, headers["user-agent"])

        assertEquals(404, transport.get("$base/demo/v1/et/translation.json").status)
    }

    /**
     * The read timeout only limits the wait for the next bytes. A download
     * that is still going when its total time is up is cut off.
     */
    @Test
    fun aDownloadThatOutlastsItsTotalTimeIsCutOff() {
        var calls = 0
        val transport = HttpUrlConnectionTransport(
            connectTimeoutMillis = 2_000,
            readTimeoutMillis = 2_000,
            totalTimeoutMillis = 1_000,
            // The clock starts at 0 and has run far past the limit by the first read.
            nanoTime = { if (calls++ == 0) 0L else 5_000_000_000L },
        )
        try {
            transport.get("$base/demo/v1/en/translation.json")
            fail("the download was not cut off")
        } catch (expected: IOException) {
            assertTrue(expected.message, expected.message!!.contains("longer than 1000 ms"))
        }
    }
}
