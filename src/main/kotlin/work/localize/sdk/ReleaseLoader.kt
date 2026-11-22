package work.localize.sdk

import java.io.IOException
import work.localize.sdk.LocalizeException.Reason

/** Waits between attempts; replaced in tests so they do not sleep. */
internal fun interface Sleeper {
    @Throws(InterruptedException::class)
    fun sleep(millis: Long)
}

/**
 * The network half of the read algorithm: resolve the pointer to a version,
 * and fetch one file of a version.
 *
 * A request that got no answer, or a 5xx answer, is tried again a couple of
 * times, because both pass on their own. Anything else is final: a 4xx status
 * or a body that is not what the contract promises does not get better by
 * asking again.
 */
internal class ReleaseLoader(
    private val config: LocalizeConfig,
    private val transport: LocalizeTransport,
    private val sleeper: Sleeper,
) {
    /** The pinned version, or the one `latest.json` names right now. */
    @Throws(LocalizeException::class)
    fun resolveVersion(): String {
        config.version?.let { return it }
        val url = CdnUrls.pointer(config.cdnBaseUrl, config.projectSlug)
        val response = request(url)
        if (response.status !in 200..299) {
            val hint = if (response.status == 404) " (nothing published yet, or the project slug is wrong)" else ""
            throw LocalizeException(
                Reason.HTTP_ERROR,
                "$url returned ${response.status}$hint",
                url = url,
                status = response.status,
            )
        }
        val version = try {
            (Json.parse(response.body) as? Map<*, *>)?.get("version")
        } catch (invalid: JsonException) {
            null
        }
        if (version !is String || !CdnUrls.isVersion(version)) {
            throw LocalizeException(
                Reason.INVALID_POINTER,
                "invalid latest release pointer at $url",
                url = url,
                status = response.status,
            )
        }
        return version
    }

    /**
     * One namespace file of [version], or `null` when the CDN has none (a
     * 404). That is normal and the caller treats it as an empty file: a
     * regional variant publishes only the texts it words differently, and no
     * file at all for a namespace it does not touch.
     */
    @Throws(LocalizeException::class)
    fun fetchFile(version: String, locale: String, namespace: String): NamespaceTree? {
        val url = CdnUrls.file(config.cdnBaseUrl, config.projectSlug, version, locale, namespace)
        val response = request(url)
        if (response.status == 404) return null
        if (response.status !in 200..299) {
            throw LocalizeException(
                Reason.HTTP_ERROR,
                "$url returned ${response.status}",
                url = url,
                status = response.status,
            )
        }
        val parsed = try {
            Json.parse(response.body)
        } catch (invalid: JsonException) {
            throw LocalizeException(
                Reason.INVALID_RESPONSE,
                "$url is not valid JSON: ${invalid.message}",
                url = url,
                status = response.status,
                cause = invalid,
            )
        }
        if (parsed !is Map<*, *>) {
            throw LocalizeException(
                Reason.INVALID_RESPONSE,
                "$url is not a JSON object",
                url = url,
                status = response.status,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return parsed as NamespaceTree
    }

    private fun request(url: String): LocalizeResponse {
        var attempt = 0
        while (true) {
            val failure = try {
                val response = transport.get(url)
                if (response.status < 500) return response
                LocalizeException(
                    Reason.SERVER_ERROR,
                    "$url returned ${response.status}",
                    url = url,
                    status = response.status,
                    isRetryable = true,
                )
            } catch (tooLarge: ResponseTooLargeException) {
                throw LocalizeException(Reason.INVALID_RESPONSE, "$url: ${tooLarge.message}", url = url, cause = tooLarge)
            } catch (noAnswer: IOException) {
                LocalizeException(
                    Reason.NETWORK,
                    "failed fetching $url: $noAnswer",
                    url = url,
                    isRetryable = true,
                    cause = noAnswer,
                )
            } catch (broken: RuntimeException) {
                // A transport that throws something else is not asked again.
                throw LocalizeException(Reason.NETWORK, "failed fetching $url: $broken", url = url, cause = broken)
            }
            if (attempt >= RETRY_DELAYS_MILLIS.size) throw failure
            try {
                sleeper.sleep(RETRY_DELAYS_MILLIS[attempt])
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
            attempt++
        }
    }

    internal companion object {
        /** The waits before the second and third attempt of one request. */
        val RETRY_DELAYS_MILLIS: LongArray = longArrayOf(400, 1200)
    }
}
