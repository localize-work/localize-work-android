package work.localize.sdk

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** The device the tests run on, and the test app's resources in `src/androidTest/res`. */
internal object Device {
    val sdk: Int = Build.VERSION.SDK_INT

    /** The test app: a library's tests are their own app, with the library and the test resources in it. */
    val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private val byLocale = ConcurrentHashMap<String, Resources>()

    fun id(name: String, type: String): Int {
        val id = context.resources.getIdentifier(name, type, context.packageName)
        check(id != 0) { "the test app has no $type resource called $name" }
        return id
    }

    /**
     * The test app's resources as Android itself resolves them for a user
     * whose only language is [tag]: nothing but the locale is set.
     */
    fun resourcesFor(tag: String): Resources = byLocale.getOrPut(tag) {
        val override = Configuration()
        override.fontScale = 0f
        override.setLocale(Locale.forLanguageTag(tag))
        context.createConfigurationContext(override).resources
    }

    /** What Android itself shows a [tag] user for the string [name]. */
    fun androidString(tag: String, name: String): String = resourcesFor(tag).getString(id(name, "string"))

    /**
     * The plural form this device gives [number] in the language of [tag],
     * read the way an app's `plurals` resource is: `p_all_forms` is in
     * `values/` alone and each of its six forms is the form's own name.
     */
    fun pluralForm(tag: String, number: Int): String =
        resourcesFor(tag).getQuantityString(id("p_all_forms", "plurals"), number)

    /** Runs a shell command with the rights `adb shell` has, and returns what it printed. */
    fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun <T> onMainThread(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Waits until [condition] holds; `false` when it did not within [millis]. */
    fun waitFor(millis: Long = 15_000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private val isNight: Boolean
        get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * Runs [block] with the device really in night mode, and puts it back.
     * [block] is given the way that worked. Throws when the device could not
     * be put in night mode at all.
     */
    fun <T> inNightMode(block: (how: String) -> T): T {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        check(!isNight) { "the device is in night mode before the test" }
        var carMode = false
        var how = "cmd uimode night yes"
        try {
            if (sdk >= 29) shell("cmd uimode night yes")
            if (!waitFor(if (sdk >= 29) 10_000 else 0) { isNight }) {
                how = "UiModeManager.setNightMode"
                uiMode.nightMode = UiModeManager.MODE_NIGHT_YES
            }
            if (!waitFor(5_000) { isNight }) {
                // Before Android 6 the setting only counts in car or desk mode.
                how = "UiModeManager.enableCarMode and setNightMode"
                carMode = true
                uiMode.enableCarMode(0)
                uiMode.nightMode = UiModeManager.MODE_NIGHT_YES
            }
            check(waitFor(10_000) { isNight }) { "the device could not be put in night mode (API $sdk)" }
            settle()
            return block(how)
        } finally {
            if (sdk >= 29) shell("cmd uimode night no")
            try {
                uiMode.nightMode = UiModeManager.MODE_NIGHT_NO
                if (carMode) uiMode.disableCarMode(0)
            } catch (ignored: RuntimeException) {
                // Needs a permission from Android 10 on, where the shell command did it.
            }
            waitFor(10_000) { !isNight }
            settle()
        }
    }

    /**
     * Android hands a new configuration to the app's resources one set after
     * another, on the main thread. Once the first has it, a turn of the main
     * thread later all of them do.
     */
    private fun settle() = onMainThread { }

    private val isLandscape: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Runs [block] with the device's screen really wider than tall, and puts it back. */
    fun <T> inLandscape(block: () -> T): T {
        check(!isLandscape) { "the screen is in landscape before the test" }
        try {
            shell("wm size 1920x1080")
            check(waitFor(15_000) { isLandscape }) { "the screen could not be made landscape (API $sdk)" }
            settle()
            return block()
        } finally {
            shell("wm size reset")
            waitFor(15_000) { !isLandscape }
            settle()
        }
    }
}

/**
 * What the tests found out about the device, as lines in logcat under the tag
 * `LWDT` and, where the build passes a folder for it, in a file the build
 * copies off the device.
 */
internal object Report {
    private const val TAG = "LWDT"

    private val file: File? by lazy {
        try {
            val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            if (folder == null) null else File(folder).apply { mkdirs() }.resolve("device-report-api${Device.sdk}.txt")
        } catch (failure: Exception) {
            null
        }
    }

    @Synchronized
    fun line(section: String, text: String) {
        val full = "API ${Device.sdk} [$section] $text"
        for (part in full.chunked(3_000)) Log.i(TAG, part)
        try {
            file?.appendText(full + "\n")
        } catch (failure: Exception) {
            // Logcat has it.
        }
    }
}

/** Collects every mismatch of a test, so one run shows all of them and not only the first. */
internal class Mismatches {
    private val found = ArrayList<String>()

    fun add(text: String) {
        found.add(text)
    }

    fun expect(what: String, expected: Any?, actual: Any?) {
        if (expected != actual) found.add("$what: expected <$expected> but was <$actual>")
    }

    fun assertNone(title: String) {
        if (found.isEmpty()) return
        throw AssertionError("$title on API ${Device.sdk}: ${found.size} mismatch(es)\n" + found.joinToString("\n"))
    }
}

/**
 * A CDN on this device: a plain HTTP server on 127.0.0.1 that answers `GET`
 * for the paths it was given and 404 for every other.
 */
internal class LocalCdn : Closeable {
    class Request(val path: String, val headers: Map<String, String>)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val routes = ConcurrentHashMap<String, Pair<Int, String>>()
    val requests = CopyOnWriteArrayList<Request>()
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    private val thread = Thread({
        while (!server.isClosed) {
            try {
                server.accept().use(::answer)
            } catch (closed: SocketException) {
                // The server was stopped, or the client went away.
            } catch (failure: Exception) {
                Log.w("LWDT", "LocalCdn: $failure")
            }
        }
    }, "local-cdn").apply {
        isDaemon = true
        start()
    }

    val paths: List<String> get() = requests.map { it.path }

    fun put(path: String, body: String, status: Int = 200) {
        routes[path] = status to body
    }

    fun remove(path: String) {
        routes.remove(path)
    }

    fun pointer(slug: String, version: String) = put("/$slug/latest.json", """{"version":"$version"}""")

    fun file(slug: String, version: String, locale: String, json: String, namespace: String = "translation") =
        put("/$slug/$version/$locale/$namespace.json", json)

    private fun answer(socket: Socket) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val path = parts.getOrElse(1) { "" }
        requests.add(Request(path, headers + ("(method)" to parts[0])))
        val (status, body) = routes[path] ?: (404 to "")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Status"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    /** Stops the server: from here a request gets no answer. */
    override fun close() {
        server.close()
        thread.join(3_000)
    }
}
