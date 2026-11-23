package work.localize.sdk

import android.app.LocaleManager
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole way of a text on a device, through the public API: a release
 * served by an HTTP server on the device itself, downloaded with the built-in
 * transport, stored in the app's files, and looked up beside the test app's
 * real resources.
 */
@RunWith(AndroidJUnit4::class)
class WholeFlowDeviceTest {
    private val context = Device.context
    private val slug = "demo"
    private lateinit var cdn: LocalCdn
    private lateinit var cache: File

    private class Change(val version: String?, val onMainThread: Boolean)
    private class Outcome(val result: RefreshResult, val onMainThread: Boolean)

    private val changes = CopyOnWriteArrayList<Change>()
    private val listener = LocalizeListener { version -> changes.add(Change(version, onMain())) }

    private fun onMain() = Looper.myLooper() == Looper.getMainLooper()

    private companion object {
        /** Letters outside ASCII, two scripts and a character that takes two UTF-16 units. */
        const val LETTERS = "Õäöü – žš «繁體» 😀"
    }

    @Before
    fun setUp() {
        cdn = LocalCdn()
        cache = File(context.cacheDir, "lw-flow-${System.nanoTime()}")
        Localize.addListener(listener)
        publish("v1")
    }

    @After
    fun tearDown() {
        Localize.removeListener(listener)
        cdn.close()
        cache.deleteRecursively()
    }

    private fun publish(version: String) {
        cdn.file(
            slug, version, "et",
            """{ "step1": "Samm 1 (release et $version)",
                 "step4_other": "{{count}} sammu 4 (release et other)",
                 "items_one": "{{count}} asi, {{name}}", "items_other": "{{count}} asja, {{name}}",
                 "nav": { "home": "Avaleht" }, "letters": "$LETTERS" }""",
        )
        cdn.file(
            slug, version, "en",
            """{ "step1": "Step 1 (release en $version)", "step2": "Step 2 (release en $version)",
                 "step3": "Step 3 (release en $version)",
                 "items_one": "{{count}} item, {{name}}", "items_other": "{{count}} items, {{name}}" }""",
        )
        cdn.file(
            slug, version, "ru",
            """{ "step1": "Шаг 1 (release ru $version)", "items_one": "{{count}} предмет, {{name}}",
                 "items_few": "{{count}} предмета, {{name}}", "items_many": "{{count}} предметов, {{name}}" }""",
        )
        cdn.pointer(slug, version)
    }

    private fun config(configure: LocalizeConfig.Builder.() -> Unit = {}) = LocalizeConfig(slug) {
        cdnBaseUrl(cdn.baseUrl)
        cacheDirectory(cache)
        locale("et")
        fallbackLocale("en")
        refreshOnInit(false)
        configure()
    }

    /** `Localize.init` on the main thread, as in `Application.onCreate`. */
    private fun init(configure: LocalizeConfig.Builder.() -> Unit = {}) {
        val config = config(configure)
        Device.onMainThread { Localize.init(context, config) }
    }

    private fun await(start: (RefreshCallback) -> Unit): Outcome {
        val done = CountDownLatch(1)
        var outcome: Outcome? = null
        Device.onMainThread {
            start(RefreshCallback { result ->
                outcome = Outcome(result, onMain())
                done.countDown()
            })
        }
        assertTrue("no result within 60 s", done.await(60, TimeUnit.SECONDS))
        return outcome!!
    }

    private fun refresh(): Outcome = await { Localize.refresh(it) }

    private fun setLocale(locale: String): Outcome = await { Localize.setLocale(locale, it) }

    /** Lets what was posted to the main thread before now run. */
    private fun settleMainThread() = Device.onMainThread { }

    /** A new launch of the app: a client that shares nothing with `Localize` but the files on the device. */
    private fun newLaunch(configure: LocalizeConfig.Builder.() -> Unit = {}): LocalizeClient {
        val config = config(configure)
        val mainThread = Handler(Looper.getMainLooper())
        return LocalizeClient(
            config = config,
            cacheRoot = config.cacheDirectory!!,
            deviceLocale = "en-US",
            transport = HttpUrlConnectionTransport(config.connectTimeoutMillis, config.readTimeoutMillis),
            bundled = AndroidBundledStrings(context),
            worker = Executor { task -> Thread(task, "localize-work-test").start() },
            callbacks = Executor { task -> mainThread.post(task) },
        )
    }

    @Test
    fun theFirstLoadAndTheSixPlacesATextComesFrom() {
        init()
        assertNull(Localize.version)
        // Before anything is downloaded the app's own texts are shown.
        assertEquals("Samm 1 (bundled et)", Localize.string("step1"))
        assertEquals("Step 3 (bundled default)", Localize.string("step3"))

        val first = refresh()
        assertEquals(RefreshResult.Status.APPLIED, first.result.status)
        assertEquals("v1", first.result.version)
        assertTrue("the callback ran off the main thread", first.onMainThread)
        assertEquals("v1", Localize.version)
        assertEquals("et", Localize.locale)
        assertEquals(
            listOf("/demo/latest.json", "/demo/v1/et/translation.json", "/demo/v1/en/translation.json"),
            cdn.paths,
        )
        for (request in cdn.requests) {
            assertEquals("GET", request.headers["(method)"])
            assertEquals("localize-work-android/0.1.0", request.headers["user-agent"])
            assertEquals("application/json", request.headers["accept"])
            assertNull(request.headers["cookie"])
        }
        Report.line("flow", "request headers the device sent: ${cdn.requests[0].headers}")
        settleMainThread()
        assertEquals(listOf("v1"), changes.map { it.version })
        assertTrue("the listener ran off the main thread", changes.all { it.onMainThread })

        for (thread in listOf("the test's thread", "the main thread")) {
            val read: (() -> String) -> String = { if (thread == "the main thread") Device.onMainThread(it) else it() }
            // 1. The release, in the user's language.
            assertEquals(thread, "Samm 1 (release et v1)", read { Localize.string("step1") })
            // 2. The app's own resource in the user's language, before the release's fallback language.
            assertEquals(thread, "Samm 2 (bundled et)", read { Localize.string("step2") })
            // 3. The release, in the fallback language, before the app's resource in another language.
            assertEquals(thread, "Step 3 (release en v1)", read { Localize.string("step3") })
            // 4. The release's general plural form, when no file has the form of the count.
            assertEquals(thread, "1 sammu 4 (release et other)", read { Localize.string("step4", count = 1) })
            // 5. The app's own resource as Android picks it.
            assertEquals(thread, "Step 5 (bundled default)", read { Localize.string("step5") })
            assertEquals(thread, "Hello, Ann! (bundled default)", read { Localize.string("hello", mapOf("name" to "Ann")) })
            // 6. The caller's fallback, then the key.
            assertEquals(thread, "Fallback for Ann", read { Localize.string("step6", mapOf("name" to "Ann"), fallback = "Fallback for {{name}}") })
            assertEquals(thread, "step6", read { Localize.string("step6") })
        }

        // A count picks the form and is filled in, with other arguments beside it.
        assertEquals("1 asi, Ann", Localize.string("items", mapOf("name" to "Ann"), count = 1))
        assertEquals("5 asja, Ann", Localize.string("items", mapOf("name" to "Ann"), count = 5))
        assertEquals("0 asja, Ann", Localize.plural("items", 0, mapOf("name" to "Ann")))
        assertEquals("5 sammu 4 (release et other)", Localize.string("step4", count = 5))
        assertEquals("Avaleht", Localize.string("nav.home"))
        assertEquals(LETTERS, Localize.string("letters"))
        // The same from the app's own plurals resource, by position.
        assertEquals("1 toode, Ann", Localize.string("cart", mapOf("name" to "Ann"), count = 1))
        assertEquals("2 toodet, Ann", Localize.string("cart", mapOf("name" to "Ann"), count = 2))

        // Stored where the configuration says.
        assertTrue(File(cache, "demo/state.json").isFile)
        assertTrue(File(cache, "demo/v1.json").isFile)
    }

    /** README: "The last complete release is kept in the app's private storage (`no_backup/localize-work/`)". */
    @Test
    fun theDefaultCacheFolderIsInTheAppsNoBackupFiles() {
        val folder = File(context.noBackupFilesDir, "localize-work")
        folder.deleteRecursively()
        try {
            Device.onMainThread {
                Localize.init(
                    context,
                    LocalizeConfig(slug) {
                        cdnBaseUrl(cdn.baseUrl)
                        locale("et")
                        refreshOnInit(false)
                    },
                )
            }
            assertEquals(RefreshResult.Status.APPLIED, refresh().result.status)
            Report.line("flow", "default cache folder: $folder holds ${File(folder, slug).list()?.sorted()}")
            assertTrue(File(folder, "$slug/state.json").isFile)
            assertTrue(File(folder, "$slug/v1.json").isFile)
            assertTrue(folder.absolutePath, folder.absolutePath.contains("/no_backup/localize-work"))
        } finally {
            folder.deleteRecursively()
        }
    }

    /** Without a `locale` the SDK takes the first language of the device, or of the app. */
    @Test
    fun withoutALocaleTheDevicesFirstLanguageIsUsed() {
        val device = if (Device.sdk >= 24) LocaleList.getDefault().get(0) else context.resources.configuration.locale
        init { locale(null) }
        Report.line("flow", "device language ${device.toLanguageTag()}, Localize.locale ${Localize.locale}")
        assertEquals(LocaleTags.canonicalize(device.toLanguageTag()), Localize.locale)
    }

    /** README: "the first language the user chose for the device (or for your app, where Android offers per-app languages)". */
    @Test
    fun withoutALocaleThePerAppLanguageIsUsed() {
        assumeTrue("per-app languages are Android 13 and later", Device.sdk >= 33)
        val locales = context.getSystemService(LocaleManager::class.java)
        try {
            locales.applicationLocales = LocaleList.forLanguageTags("et-EE")
            check(Device.waitFor { LocaleList.getDefault().get(0).language == "et" }) { "the app's language could not be set" }
            init { locale(null) }
            assertEquals("et-EE", Localize.locale)
            assertEquals("Samm 1 (bundled et)", Localize.string("step1"))

            // The first language the user chose, also when the app bundles
            // only a later one, which Android then moves to the front of the
            // app's own resources.
            locales.applicationLocales = LocaleList.forLanguageTags("sv-SE,et-EE")
            check(Device.waitFor { LocaleList.getDefault().get(0).language == "sv" }) { "the app's languages could not be set" }
            Report.line("flow", "user's languages ${LocaleList.getDefault().toLanguageTags()}, the app's resources ${context.resources.configuration.locales.toLanguageTags()}")
            // README: a change of language while the app runs "is not picked up by itself".
            assertEquals("et-EE", Localize.locale)
            init { locale(null) }
            assertEquals("sv-SE", Localize.locale)
            assertEquals("et", context.resources.configuration.locales.get(0).language)
        } finally {
            locales.applicationLocales = LocaleList.getEmptyLocaleList()
            Device.waitFor { LocaleList.getDefault().get(0).language !in setOf("et", "sv") }
        }
    }

    @Test
    fun aNewReleaseIsPickedUpByTheNextRefresh() {
        init()
        assertEquals(RefreshResult.Status.APPLIED, refresh().result.status)
        assertEquals("Samm 1 (release et v1)", Localize.string("step1"))

        // Nothing new: only the pointer is asked for.
        var before = cdn.requests.size
        val same = refresh()
        assertEquals(RefreshResult.Status.UP_TO_DATE, same.result.status)
        assertEquals(listOf("/demo/latest.json"), cdn.paths.drop(before))

        publish("v2")
        before = cdn.requests.size
        changes.clear()
        val second = refresh()
        assertEquals(RefreshResult.Status.APPLIED, second.result.status)
        assertEquals("v2", second.result.version)
        assertTrue(second.onMainThread)
        assertEquals(
            listOf("/demo/latest.json", "/demo/v2/et/translation.json", "/demo/v2/en/translation.json"),
            cdn.paths.drop(before),
        )
        assertEquals("v2", Localize.version)
        assertEquals("Samm 1 (release et v2)", Localize.string("step1"))
        assertEquals("Step 3 (release en v2)", Localize.string("step3"))
        settleMainThread()
        assertEquals(listOf("v2"), changes.map { it.version })
        assertTrue(changes.all { it.onMainThread })
        // README: "Older releases are deleted."
        assertEquals(listOf("state.json", "v2.json"), File(cache, "demo").list()!!.sorted())

        // README: "If you roll a project back to an earlier release, the app follows at its next refresh."
        cdn.pointer(slug, "v1")
        val back = refresh()
        assertEquals(RefreshResult.Status.APPLIED, back.result.status)
        assertEquals("v1", Localize.version)
        assertEquals("Samm 1 (release et v1)", Localize.string("step1"))
    }

    /** `UpdatePolicy.NEXT_LAUNCH`: stored now, shown from the next start. */
    @Test
    fun aReleaseForTheNextLaunchWaitsOnTheDevice() {
        init { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        val first = refresh()
        assertEquals(RefreshResult.Status.DOWNLOADED, first.result.status)
        assertEquals("v1", first.result.version)
        // README: "On the very first launch that means the app shows its bundled strings until it is started again."
        assertNull(Localize.version)
        assertEquals("Samm 1 (bundled et)", Localize.string("step1"))
        assertEquals("Step 3 (bundled default)", Localize.string("step3"))
        settleMainThread()
        assertEquals(emptyList<String?>(), changes.map { it.version })

        // The next launch, without a connection.
        cdn.close()
        val launch = newLaunch { updatePolicy(UpdatePolicy.NEXT_LAUNCH) }
        assertEquals("v1", launch.version)
        assertEquals("Samm 1 (release et v1)", launch.string("step1", emptyMap(), null, null, null))
        assertEquals("Step 3 (release en v1)", launch.string("step3", emptyMap(), null, null, null))
    }

    /** A cold start without a connection: the stored release answers, and the failed refresh changes nothing. */
    @Test
    fun aColdStartOfflineReadsTheStoredRelease() {
        init()
        assertEquals(RefreshResult.Status.APPLIED, refresh().result.status)
        cdn.close()

        // A new launch, sharing only the files.
        val launch = newLaunch()
        assertEquals("v1", launch.version)
        assertEquals("Samm 1 (release et v1)", launch.string("step1", emptyMap(), null, null, null))
        assertEquals("Samm 2 (bundled et)", launch.string("step2", emptyMap(), null, null, null))
        assertEquals("Step 3 (release en v1)", launch.string("step3", emptyMap(), null, null, null))
        assertEquals("5 asja, Ann", launch.string("items", mapOf("name" to "Ann"), 5, null, null))
        // Through the download, the file on the device and back.
        assertEquals(LETTERS, launch.string("letters", emptyMap(), null, null, null))
        val started = SystemClock.elapsedRealtime()
        val offline = launch.refreshNow()
        Report.line("flow", "refresh with the server stopped: ${offline.result()} after ${SystemClock.elapsedRealtime() - started} ms")
        assertEquals(RefreshResult.Status.FAILED, offline.status)
        assertEquals(LocalizeException.Reason.NETWORK, offline.error!!.reason)
        assertTrue(offline.error!!.isRetryable)
        assertEquals("v1", launch.version)
        assertEquals("Samm 1 (release et v1)", launch.string("step1", emptyMap(), null, null, null))

        // And through Localize itself, set up again with its default of refreshing at once.
        Device.onMainThread {
            Localize.init(context, config { refreshOnInit(true) })
            // Read before init returns control: no download can have helped.
            assertEquals("v1", Localize.version)
            assertEquals("Samm 1 (release et v1)", Localize.string("step1"))
        }
        val failed = refresh()
        assertEquals(RefreshResult.Status.FAILED, failed.result.status)
        assertTrue(failed.onMainThread)
        assertEquals("v1", Localize.version)
        assertEquals("Step 3 (release en v1)", Localize.string("step3"))
    }

    private fun RefreshResult.result(): String =
        "$status ${error?.reason} retryable=${error?.isRetryable} ${error?.message}"

    @Test
    fun changingTheLocale() {
        init()
        assertEquals(RefreshResult.Status.APPLIED, refresh().result.status)
        changes.clear()
        var before = cdn.requests.size

        val russian = setLocale("ru")
        assertEquals(RefreshResult.Status.APPLIED, russian.result.status)
        assertEquals("v1", russian.result.version)
        assertTrue(russian.onMainThread)
        assertEquals("ru", Localize.locale)
        assertEquals("v1", Localize.version)
        // README: the missing files "are fetched from that same release first"; English is there already.
        assertEquals(listOf("/demo/v1/ru/translation.json", "/demo/latest.json"), cdn.paths.drop(before))
        settleMainThread()
        // README: the listener is called when the locale changes, and when the release starts being read.
        assertEquals(listOf(null, "v1"), changes.map { it.version })
        assertTrue(changes.all { it.onMainThread })

        assertEquals("Шаг 1 (release ru v1)", Localize.string("step1"))
        // Russian has no step2 or step3 in the release, and the app none in Russian.
        assertEquals("Step 2 (release en v1)", Localize.string("step2"))
        assertEquals("1 предмет, Ann", Localize.string("items", mapOf("name" to "Ann"), count = 1))
        assertEquals("3 предмета, Ann", Localize.string("items", mapOf("name" to "Ann"), count = 3))
        assertEquals("5 предметов, Ann", Localize.string("items", mapOf("name" to "Ann"), count = 5))
        assertEquals("21 предмет, Ann", Localize.string("items", mapOf("name" to "Ann"), count = 21))
        // The app's own Russian plurals, counted by the device.
        assertEquals("1 товар, Ann", Localize.string("cart", mapOf("name" to "Ann"), count = 1))
        assertEquals("3 товара, Ann", Localize.string("cart", mapOf("name" to "Ann"), count = 3))
        assertEquals("5 товаров, Ann", Localize.string("cart", mapOf("name" to "Ann"), count = 5))

        // README: "going back to a language the app used before needs no download".
        before = cdn.requests.size
        changes.clear()
        val estonian = setLocale("et")
        assertTrue(estonian.result.isSuccess)
        assertEquals(listOf("/demo/latest.json"), cdn.paths.drop(before))
        assertEquals("Samm 1 (release et v1)", Localize.string("step1"))
        settleMainThread()
        assertEquals(listOf<String?>("v1"), changes.map { it.version })

        // A regional variant the release has no file for: 404 is an empty file.
        before = cdn.requests.size
        val regional = setLocale("et_ee")
        assertEquals(RefreshResult.Status.APPLIED, regional.result.status)
        assertEquals("et-EE", Localize.locale)
        assertEquals(listOf("/demo/v1/et-EE/translation.json", "/demo/latest.json"), cdn.paths.drop(before))
        assertEquals("Samm 1 (release et v1)", Localize.string("step1"))
        assertEquals("Samm 2 (bundled et)", Localize.string("step2"))

        try {
            Localize.setLocale("not a tag!")
            fail("no exception for a locale that is not a language tag")
        } catch (expected: IllegalArgumentException) {
            assertEquals("et-EE", Localize.locale)
        }
    }

    /**
     * README: "Ask for texts anywhere, on any thread" and "Listeners and
     * callbacks are called on the main thread". Everything is called on the
     * main thread here, which may not touch the network: a violation ends the
     * call with an exception, and the refresh would not succeed.
     */
    @Test
    fun nothingTouchesTheNetworkOnTheMainThread() {
        val earlier = Device.onMainThread {
            val policy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
            policy
        }
        try {
            // The default: init starts the download itself.
            val started = SystemClock.elapsedRealtime()
            Device.onMainThread { Localize.init(context, config { refreshOnInit(true) }) }
            Report.line("flow", "Localize.init on the main thread, nothing stored: ${SystemClock.elapsedRealtime() - started} ms")
            assertTrue("the release was not put in use", Device.waitFor { Localize.version == "v1" })
            // The listener is posted after the release is put in use, so it may not be queued yet.
            assertTrue("the listener was not told", Device.waitFor { changes.isNotEmpty() })
            settleMainThread()
            assertEquals(listOf("v1"), changes.map { it.version })
            assertTrue(changes.all { it.onMainThread })

            publish("v2")
            val second = refresh()
            assertEquals(RefreshResult.Status.APPLIED, second.result.status)
            assertTrue(second.onMainThread)
            val russian = setLocale("ru")
            assertEquals(RefreshResult.Status.APPLIED, russian.result.status)
            assertTrue(russian.onMainThread)
            assertEquals("Шаг 1 (release ru v2)", Device.onMainThread { Localize.string("step1") })

            // Set up again with a release stored: read on the calling thread, as the README says.
            val again = SystemClock.elapsedRealtime()
            Device.onMainThread {
                Localize.init(context, config { refreshOnInit(true) })
                assertEquals("v2", Localize.version)
            }
            Report.line("flow", "Localize.init on the main thread, a release stored: ${SystemClock.elapsedRealtime() - again} ms")
            assertTrue(refresh().result.isSuccess)
            assertTrue(changes.all { it.onMainThread })
        } finally {
            Device.onMainThread { StrictMode.setThreadPolicy(earlier) }
        }
    }

    /** The failures the README lists, as a device reports them. */
    @Test
    fun failuresAreReportedAsTheReadmeSays() {
        cdn.remove("/demo/latest.json")
        init()
        val missing = refresh()
        assertEquals(RefreshResult.Status.FAILED, missing.result.status)
        assertEquals(LocalizeException.Reason.HTTP_ERROR, missing.result.error!!.reason)
        assertEquals(404, missing.result.error!!.status)
        assertFalse(missing.result.error!!.isRetryable)
        assertTrue(missing.onMainThread)
        assertNull(Localize.version)

        cdn.put("/demo/latest.json", """{"version":"latest"}""")
        val pointer = refresh()
        assertEquals(LocalizeException.Reason.INVALID_POINTER, pointer.result.error!!.reason)

        cdn.pointer(slug, "v1")
        cdn.put("/demo/v1/et/translation.json", "<html>not json</html>")
        val broken = refresh()
        assertEquals(LocalizeException.Reason.INVALID_RESPONSE, broken.result.error!!.reason)
        assertNull(Localize.version)

        // A server error is tried three times.
        cdn.put("/demo/v1/et/translation.json", "", status = 503)
        val before = cdn.requests.size
        val server = refresh()
        assertEquals(LocalizeException.Reason.SERVER_ERROR, server.result.error!!.reason)
        assertTrue(server.result.error!!.isRetryable)
        assertEquals(3, cdn.paths.drop(before).count { it == "/demo/v1/et/translation.json" })
        assertNotNull(server.result.error!!.message)

        // And the app still has its own texts.
        assertEquals("Samm 1 (bundled et)", Localize.string("step1"))
    }
}
