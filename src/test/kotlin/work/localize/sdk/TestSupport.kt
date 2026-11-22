package work.localize.sdk

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

internal const val CDN = "https://cdn.test"
internal const val SLUG = "demo"
internal const val POINTER_URL = "$CDN/$SLUG/latest.json"

internal fun fileUrl(version: String, locale: String, namespace: String = "translation") =
    "$CDN/$SLUG/$version/$locale/$namespace.json"

/** A CDN that exists only in memory. An address nothing was published at answers 404. */
internal class FakeTransport : LocalizeTransport {
    val requests = CopyOnWriteArrayList<String>()
    private val routes = ConcurrentHashMap<String, () -> LocalizeResponse>()

    /** Runs when a request arrives, before it is answered. */
    @Volatile
    var onRequest: (String) -> Unit = {}

    override fun get(url: String): LocalizeResponse {
        requests.add(url)
        onRequest(url)
        return routes[url]?.invoke() ?: LocalizeResponse(404, "")
    }

    fun on(url: String, answer: () -> LocalizeResponse) {
        routes[url] = answer
    }

    fun pointer(version: String) = pointerBody("""{ "version": "$version" }""")

    fun pointerBody(body: String) = on(POINTER_URL) { LocalizeResponse(200, body) }

    fun file(version: String, locale: String, json: String, namespace: String = "translation") =
        on(fileUrl(version, locale, namespace)) { LocalizeResponse(200, json) }

    fun status(url: String, status: Int) = on(url) { LocalizeResponse(status, "") }

    fun unreachable(url: String) = on(url) { throw IOException("connection reset") }

    val pointerRequests: Int get() = requests.count { it == POINTER_URL }

    val fileRequests: List<String> get() = requests.filter { it != POINTER_URL }
}

/** Builds clients that share one cache folder and one fake CDN, as launches of one app do. */
internal class Harness(private val cacheRoot: File, val transport: FakeTransport = FakeTransport()) {
    val sleeps = CopyOnWriteArrayList<Long>()
    val bundledResources = ConcurrentHashMap<String, String>()
    val bundledPlurals = ConcurrentHashMap<String, String>()

    private val direct = Executor { task -> task.run() }

    /**
     * A new client: what a launch of the app gets from `Localize.init`. With
     * [continuing], what a later `Localize.init` in the same run gets: the
     * earlier client is closed first, as `Localize.init` does, and the new
     * one is part of the same run of the app.
     */
    fun launch(
        deviceLocale: String = "en",
        continuing: LocalizeClient? = null,
        slug: String = SLUG,
        configure: LocalizeConfig.Builder.() -> Unit = {},
    ): LocalizeClient {
        continuing?.close()
        return client(deviceLocale, continuing, slug, configure)
    }

    private fun client(
        deviceLocale: String,
        continuing: LocalizeClient?,
        slug: String,
        configure: LocalizeConfig.Builder.() -> Unit,
    ): LocalizeClient =
        LocalizeClient(
            config = LocalizeConfig(slug) {
                cdnBaseUrl(CDN)
                configure()
            },
            cacheRoot = cacheRoot,
            deviceLocale = deviceLocale,
            transport = transport,
            bundled = { name, count ->
                (if (count != null) bundledPlurals[name] else null) ?: bundledResources[name]
            },
            worker = direct,
            callbacks = direct,
            sleeper = { millis -> sleeps.add(millis) },
            appRun = continuing?.appRun ?: AppRun(),
        )
}

internal fun LocalizeClient.text(
    key: String,
    args: Map<String, Any?> = emptyMap(),
    count: Long? = null,
    namespace: String? = null,
    fallback: String? = null,
): String = string(key, args, count, namespace, fallback)

/** A client holding exactly the given files (locale to JSON) of release v1. */
internal fun Harness.launchWith(
    vararg files: Pair<String, String>,
    deviceLocale: String = files.first().first,
    configure: LocalizeConfig.Builder.() -> Unit = {},
): LocalizeClient {
    transport.pointer("v1")
    for ((locale, json) in files) transport.file("v1", locale, json)
    val client = launch(deviceLocale, configure = configure)
    check(client.refreshNow().status == RefreshResult.Status.APPLIED) { "the test release did not load" }
    return client
}
