package work.localize.sdk

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * How the SDK downloads a file. The default uses [HttpURLConnection]; supply
 * your own through [LocalizeConfig.Builder.transport] to route requests through
 * your HTTP client, or to serve canned responses in tests.
 *
 * An implementation makes one plain `GET` and returns whatever the server
 * answered, including error statuses. It throws [IOException] only when no
 * answer arrived (no connection, timeout). It is called on a background thread.
 */
public fun interface LocalizeTransport {
    @Throws(IOException::class)
    public fun get(url: String): LocalizeResponse
}

/** An HTTP answer: the status code and the body decoded as UTF-8 text. */
public class LocalizeResponse(
    public val status: Int,
    public val body: String,
)

/** Thrown by the default transport for a body larger than the SDK is willing to hold in memory. */
internal class ResponseTooLargeException(message: String) : IOException(message)

/**
 * The default transport. It goes through the platform's HTTP stack, which
 * also applies a process-wide [java.net.CookieHandler] when the app has
 * installed one: cookies that handler holds for the CDN's host are sent with
 * these requests. The localize.work CDN sets none; an app that serves the
 * files from a host of its own and wants them fetched without its cookies
 * supplies its own [LocalizeTransport].
 */
internal class HttpUrlConnectionTransport(
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    /** The longest one download may take in all, however slowly it trickles in. */
    private val totalTimeoutMillis: Long = TOTAL_TIMEOUT_MILLIS,
    private val nanoTime: () -> Long = System::nanoTime,
) : LocalizeTransport {

    override fun get(url: String): LocalizeResponse {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("not an HTTP or HTTPS address: $url")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            // The platform default names the device model and system build.
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val deadline = nanoTime() + totalTimeoutMillis * 1_000_000
            val status = connection.responseCode
            if (status !in 200..299) {
                // Reading the error body to its end lets the connection be reused.
                connection.errorStream?.use { drain(it, deadline) }
                return LocalizeResponse(status, "")
            }
            val body = connection.inputStream.use { readLimited(it, maxBodyBytes, deadline) }
            return LocalizeResponse(status, body)
        } catch (failure: IOException) {
            connection.disconnect()
            throw failure
        } catch (failure: RuntimeException) {
            // Some platform versions throw unchecked exceptions for a malformed
            // answer or a missing INTERNET permission.
            connection.disconnect()
            throw IOException("request failed: ${failure.javaClass.simpleName}: ${failure.message}", failure)
        }
    }

    /**
     * Reads the body. The read timeout only limits the wait for the next
     * bytes, so a response that keeps trickling in is cut off at [deadline].
     */
    private fun readLimited(stream: InputStream, limit: Int, deadline: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (nanoTime() - deadline > 0) throw IOException("the download took longer than $totalTimeoutMillis ms")
            if (out.size() + read > limit) throw ResponseTooLargeException("response is larger than $limit bytes")
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun drain(stream: InputStream, deadline: Long) {
        try {
            val buffer = ByteArray(4 * 1024)
            var total = 0
            while (total < 64 * 1024 && nanoTime() - deadline <= 0) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
        } catch (ignored: IOException) {
            // Only the status matters for an error answer.
        }
    }

    internal companion object {
        /** Far above any real namespace file; a guard against a runaway response. */
        const val MAX_BODY_BYTES: Int = 16 * 1024 * 1024
        const val TOTAL_TIMEOUT_MILLIS: Long = 120_000
        const val USER_AGENT: String = "localize-work-android/${LocalizeConfig.SDK_VERSION}"
    }
}
