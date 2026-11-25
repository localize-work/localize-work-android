package work.localize.demo

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The whole flow of the demo app on a device, read off the screen: bundled
 * texts, the first download, languages, an update and working offline.
 *
 * Each test is one start of the app's process, so `flow.sh` runs them one at
 * a time, in order, and does between them what a test cannot: clearing the
 * app's data and turning the network off and on. See that script for the
 * arguments (`-e cdn`, `-e version` and so on).
 *
 * The expected texts are those of the test project the flow is run against;
 * each expectation names the part of the README it comes from.
 */
class FlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "work.localize.demo"

    private fun argument(name: String): String =
        arguments.getString(name) ?: throw AssertionError("this phase needs -e $name")

    private val project: String get() = arguments.getString("project") ?: "demo-android"

    // ------------------------------------------------------------- phases

    /** README, the introduction: the bundled strings are there on first launch and without a connection. */
    @Test
    fun offlineFirstLaunch() = phase("offline-first-launch") {
        assertOffline()
        launch()
        expect("version", "none")
        expectBundledEnglish()
        // "Failures": a request that gets no answer fails the refresh, and can be tried again later.
        tap("refresh")
        expect("status", "FAILED NETWORK, can be retried", LONG)
        expect("version", "none")
        expectBundledEnglish()
    }

    /**
     * README, "Immediately or at the next launch": with the default policy a
     * release is used as soon as it is complete, and listeners are told. The
     * screen changes while the app is open.
     */
    @Test
    fun firstDownload() = phase("first-download") {
        val version = argument("version")
        awaitBackend()
        launch()
        expect("version", version, LONG)
        expectEnglish(version)
    }

    /**
     * README, "Stored on the device": `init` loads the last complete release
     * before it returns, so the app starts with its texts, also offline.
     */
    @Test
    fun coldStart() = phase(arguments.getString("name") ?: "cold-start") {
        val version = argument("version")
        if (arguments.getString("offline") == "true") assertOffline() else awaitBackend()
        launch(policy = arguments.getString("policy"))
        expect("version", version)
        expectEnglish(version)
    }

    /**
     * README, "Choosing the locale" and "Where a text comes from": the files
     * of a new locale are fetched from the same release, and each text comes
     * from the first of the six places that has it.
     */
    @Test
    fun languages() = phase("languages") {
        val version = argument("version")
        awaitBackend()
        launch()
        expect("version", version, LONG)

        tap("lang_et")
        expect("locale", "et")
        expect("version", version, LONG)
        expect("title", "Demo (väljalase $version)")
        expect("greeting", "Tere, Ann!")
        // "Plural forms": the category of the count in the file's language.
        expect("items_one", "1 asi")
        expect("items_five", "5 asja")
        // Not in the release's Estonian nor in the app's: step 3, the release in the fallback locale.
        expect("only_source", "Only in English on the server")
        // Step 2: the app's own resource in the user's language.
        expect("only_bundled", "Ainult rakenduses")
        expect("added_later", addedLater(version))
        expect("missing", "missing")
        screenshot("languages-et")

        tap("lang_de")
        expect("locale", "de")
        expect("version", version, LONG)
        expect("title", "Demo (Version $version)")
        expect("greeting", "Hallo, Ann!")
        expect("items_one", "1 Artikel")
        expect("items_five", "5 Artikel")
        expect("only_source", "Only in English on the server")
        // The app bundles no German: step 4, the resource in the language Android picks.
        expect("only_bundled", "Only in the app")
        expect("added_later", addedLater(version))
        expect("missing", "missing")
        screenshot("languages-de")

        // "Going back to a language the app used before needs no download."
        tap("lang_et")
        expect("locale", "et")
        expect("title", "Demo (väljalase $version)")
        tap("lang_en")
        expect("locale", "en")
        expectEnglish(version)
    }

    /**
     * README, "Quick start": the SDK does not look for releases on a timer;
     * `refresh` does, and with the default policy reports `APPLIED` and the
     * screen changes without a restart.
     */
    @Test
    fun updateWhileOpen() = phase("update-while-open") {
        val version = argument("version")
        awaitBackend()
        launch()
        expect("version", version, LONG)
        expectEnglish(version)

        val next = publishNext()
        assertNotEquals("the backend published nothing new", version, next)
        // Nothing asked for it yet: the screen is as it was.
        expect("title", "Demo (release $version)")

        tap("refresh")
        expect("status", "APPLIED $next", LONG)
        expect("version", next)
        expectEnglish(next)
    }

    /**
     * README, "Immediately or at the next launch": with `NEXT_LAUNCH` a new
     * release is stored and reported as `DOWNLOADED`, and no text changes
     * while the app is open. `flow.sh` publishes `next` before this phase and
     * starts the app again after it.
     */
    @Test
    fun updateAtNextLaunch() = phase("update-at-next-launch") {
        val version = argument("version")
        val next = argument("next")
        awaitBackend()
        launch(policy = "next_launch")
        expect("version", version)
        expectEnglish(version)

        tap("refresh")
        expect("status", "DOWNLOADED $next", LONG)
        expect("version", version)
        expectEnglish(version)
    }

    /**
     * The default CDN over HTTPS, read only, with a real project (`-e slug`).
     * README, "Android 5.0 to 7.0": downloads work from Android 7.1.1 and fail
     * as a retryable network error on 7.0 and older, where the app shows its
     * bundled strings.
     */
    @Test
    fun realCdn() = phase("real-cdn") {
        awaitBackend() // the network is back on, so a failure below is not a missing connection
        launch(locale = "et", cdn = null, slug = argument("slug"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val arrived = device.wait(Until.hasObject(By.res(pkg, "version").text(RELEASE)), LONG)
            assertTrue("no release from the default CDN; the screen shows ${screen()}", arrived == true)
            expect("locale", "et")
        } else {
            tap("refresh")
            expect("status", "FAILED NETWORK, can be retried", LONG)
            expect("version", "none")
            expect("title", "Demo (kaasas)")
        }
    }

    // ------------------------------------------------------ what is shown

    /** What the app shows of its own resources in English, with nothing downloaded. */
    private fun expectBundledEnglish() {
        expect("locale", "en")
        expect("title", "Demo (bundled)")
        expect("greeting", "Hello, Ann! (bundled)")
        expect("items_one", "1 item (bundled)")
        expect("items_five", "5 items (bundled)")
        expect("only_source", "Only source (bundled)")
        expect("only_bundled", "Only in the app")
        // Step 5, the fallback of the call, and step 6, the key itself.
        expect("added_later", "Not published yet")
        expect("missing", "missing")
    }

    /** The English screen with release [version] in use. */
    private fun expectEnglish(version: String) {
        expect("locale", "en")
        expect("title", "Demo (release $version)")
        expect("greeting", "Hello, Ann!")
        expect("items_one", "1 item")
        expect("items_five", "5 items")
        expect("only_source", "Only in English on the server")
        // Not published: step 4, the app's own resource.
        expect("only_bundled", "Only in the app")
        expect("added_later", addedLater(version))
        expect("missing", "missing")
    }

    /** The test project publishes `added_later`, in English only, from its second release on. */
    private fun addedLater(version: String): String =
        if (version == "v1") "Not published yet" else "Added in release $version"

    // ------------------------------------------------------------ helpers

    private fun phase(name: String, body: () -> Unit) {
        device.wakeUp()
        try {
            body()
        } finally {
            screenshot(name)
        }
    }

    /** Starts the app's one screen; the process is new, so the extras are the SDK's settings. */
    private fun launch(
        locale: String = "en",
        policy: String? = null,
        cdn: String? = argument("cdn"),
        slug: String = project,
    ) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(pkg, "$pkg.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("slug", slug)
            .putExtra("locale", locale)
        cdn?.let { intent.putExtra("cdn", it) }
        policy?.let { intent.putExtra("policy", it) }
        instrumentation.targetContext.startActivity(intent)
        val deadline = System.currentTimeMillis() + 30_000
        while (device.wait(Until.hasObject(By.res(pkg, "version")), 1_000) != true) {
            assertTrue("the app's screen did not appear", System.currentTimeMillis() < deadline)
            rereadWindows()
        }
    }

    /**
     * Android 5.0 goes on reporting a window it has just opened as lying
     * below the screen, and UiAutomator then takes every view in it for
     * hidden. Turning the list of windows off makes the system build it
     * again; UiAutomator turns it back on at its next search.
     */
    private fun rereadWindows() {
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo ?: return
        service.flags = service.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
        automation.serviceInfo = service
    }

    /** Waits until the view [id] shows exactly [text]. */
    private fun expect(id: String, text: String, timeoutMillis: Long = SHORT) {
        if (device.wait(Until.hasObject(By.res(pkg, id).text(text)), timeoutMillis) == true) return
        fail("\"$id\" shows \"${shown(id)}\", not \"$text\"; the screen shows ${screen()}")
    }

    private fun shown(id: String): String? = try {
        device.findObject(By.res(pkg, id))?.text
    } catch (gone: RuntimeException) {
        null // the view was redrawn while it was read
    }

    private fun screen(): String = VIEWS.joinToString { "$it=\"${shown(it)}\"" }

    private fun tap(id: String) {
        val view = device.wait(Until.findObject(By.res(pkg, id)), SHORT) ?: throw AssertionError("no \"$id\" on screen")
        view.click()
    }

    private fun screenshot(name: String) {
        try {
            val picture = instrumentation.uiAutomation.takeScreenshot() ?: return
            val folder = File(instrumentation.targetContext.filesDir, "flow").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (unavailable: Exception) {
            // A picture is a help for the reader, not part of the check.
        }
    }

    private fun backendAnswers(): Boolean = try {
        get("${argument("cdn")}/$project/latest.json", 5_000)
        true
    } catch (unreachable: Exception) {
        false
    }

    private fun awaitBackend() {
        val deadline = System.currentTimeMillis() + LONG
        while (!backendAnswers()) {
            if (System.currentTimeMillis() > deadline) fail("the device cannot reach ${argument("cdn")}")
            Thread.sleep(2_000)
        }
    }

    private fun assertOffline() {
        assertFalse("the device still reaches ${argument("cdn")}; turn its network off first", backendAnswers())
    }

    /** Asks the flow's backend to publish the project's next release; returns its version. */
    private fun publishNext(): String {
        val answer = get("${argument("control")}/publish-next/$project", 180_000)
        return Regex("\"version\":\"(v\\d+)\"").find(answer)?.groupValues?.get(1)
            ?: throw AssertionError("the backend published nothing: $answer")
    }

    private fun get(address: String, timeoutMillis: Int): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = minOf(timeoutMillis, 10_000)
            connection.readTimeout = timeoutMillis
            if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode} from $address")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** Long enough for the SDK's three tries of a request that gets no answer. */
        const val LONG = 150_000L
        const val SHORT = 10_000L
        val RELEASE: java.util.regex.Pattern = java.util.regex.Pattern.compile("v\\d+")
        val VIEWS = listOf(
            "version", "locale", "status", "title", "greeting", "items_one", "items_five",
            "only_source", "only_bundled", "added_later", "missing",
        )
    }
}
