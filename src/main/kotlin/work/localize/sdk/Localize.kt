package work.localize.sdk

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Translations published with localize.work, downloaded at run time.
 *
 * Call [init] once, in `Application.onCreate`. It reads the last downloaded
 * release from the device, so texts are there at once and without a
 * connection, and starts a download of the current release in the background.
 * Then ask for texts with [string]:
 *
 *     Localize.string("nav.home")
 *     Localize.string("items", count = 3)
 *     Localize.string("greeting", mapOf("name" to "Ann"))
 *
 * A text that no downloaded file has comes from the app's own string
 * resources, so the strings you ship in the app are the fallback.
 *
 * Every method may be called from any thread.
 */
public object Localize {
    private const val TAG = "Localize"

    /** One thread for all downloads; it ends by itself when idle. */
    private val worker: Executor = ThreadPoolExecutor(
        1,
        1,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    ) { task ->
        Thread(task, "localize-work").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    private val listeners = CopyOnWriteArrayList<LocalizeListener>()
    private val warnedAboutMissingInit = AtomicBoolean(false)

    @Volatile
    private var client: LocalizeClient? = null

    /**
     * Sets the SDK up. Reads the stored release on the calling thread (a
     * small file read), then, unless [LocalizeConfig.refreshOnInit] is off,
     * downloads the current release in the background.
     *
     * Calling it again replaces the earlier configuration.
     */
    @JvmStatic
    public fun init(context: Context, config: LocalizeConfig) {
        val application = context.applicationContext ?: context
        val mainThread = Handler(Looper.getMainLooper())
        val created = LocalizeClient(
            config = config,
            cacheRoot = config.cacheDirectory ?: File(application.noBackupFilesDir, "localize-work"),
            deviceLocale = deviceLocale(application),
            transport = config.transport
                ?: HttpUrlConnectionTransport(config.connectTimeoutMillis, config.readTimeoutMillis),
            bundled = AndroidBundledStrings(application),
            worker = worker,
            callbacks = { task -> mainThread.post(task) },
            listeners = listeners,
        )
        synchronized(this) {
            client?.close()
            client = created
        }
        if (config.refreshOnInit) refresh()
    }

    /**
     * The text for [key].
     *
     * The key is looked up in the locale, then in its base language (`en` for
     * `en-FI`), then in the fallback locale. A text found nowhere comes from
     * the app's string resource of the matching name, then from [fallback],
     * and failing all of those the key itself is returned.
     *
     * @param args values for `{{name}}` placeholders
     * @param count selects the plural form (`key_one`, `key_other`, …) and fills `{{count}}`
     * @param namespace the namespace to look in; the configured default when `null`
     * @param fallback returned when the text is found nowhere
     */
    @JvmStatic
    @JvmOverloads
    public fun string(
        key: String,
        args: Map<String, Any?> = emptyMap(),
        count: Int? = null,
        namespace: String? = null,
        fallback: String? = null,
    ): String {
        val current = client
        if (current == null) {
            if (warnedAboutMissingInit.compareAndSet(false, true)) {
                Log.w(TAG, "Localize.string() was called before Localize.init(); returning the fallback or the key")
            }
            return if (fallback != null) Interpolator.interpolate(fallback, args, count?.toLong()) else key
        }
        return current.string(key, args, count?.toLong(), namespace, fallback)
    }

    /** The plural form of [key] for [count]; the same as [string] with a count, for Java callers. */
    @JvmStatic
    @JvmOverloads
    public fun plural(
        key: String,
        count: Int,
        args: Map<String, Any?> = emptyMap(),
        namespace: String? = null,
        fallback: String? = null,
    ): String = string(key, args, count, namespace, fallback)

    /**
     * Downloads the project's current release in the background.
     *
     * [callback] is called on the main thread with the outcome. A failed
     * download changes nothing: the release already in use stays in use.
     */
    @JvmStatic
    @JvmOverloads
    public fun refresh(callback: RefreshCallback? = null) {
        val current = client
        if (current == null) {
            callback?.let { notInitialized(it) }
            return
        }
        current.refresh { result ->
            if (result.status == RefreshResult.Status.FAILED) {
                Log.w(TAG, "refresh failed: ${result.error?.message}")
            }
            callback?.onResult(result)
        }
    }

    /**
     * Switches to another locale, such as `et` or `en-FI`, for the rest of
     * this run of the app, and downloads its files if the device does not have
     * them yet; until they have arrived the app's bundled strings are shown.
     * To start in a chosen locale, pass it to [LocalizeConfig.Builder.locale].
     *
     * @throws IllegalArgumentException when [locale] is not a language tag
     */
    @JvmStatic
    @JvmOverloads
    public fun setLocale(locale: String, callback: RefreshCallback? = null) {
        val current = client
        if (current == null) {
            callback?.let { notInitialized(it) }
            return
        }
        current.setLocale(locale, callback)
    }

    /** The locale texts are shown in, or `null` before [init]. */
    @JvmStatic
    public val locale: String?
        get() = client?.locale

    /**
     * The release in use, such as `v12`, or `null` when there is none yet, or
     * none that has every file of the current locale.
     */
    @JvmStatic
    public val version: String?
        get() = client?.version

    /** Registers [listener] to be told, on the main thread, when texts may have changed. */
    @JvmStatic
    public fun addListener(listener: LocalizeListener) {
        listeners.addIfAbsent(listener)
    }

    @JvmStatic
    public fun removeListener(listener: LocalizeListener) {
        listeners.remove(listener)
    }

    private fun notInitialized(callback: RefreshCallback) {
        val result = RefreshResult(
            RefreshResult.Status.FAILED,
            null,
            LocalizeException(LocalizeException.Reason.NOT_INITIALIZED, "Localize.init() has not been called"),
        )
        Handler(Looper.getMainLooper()).post { callback.onResult(result) }
    }

    /**
     * The first language the user asked for. Not the first locale of the
     * app's resource configuration: Android moves the best match among the
     * languages the app bundles to the front of that list, and a language
     * that is only published, not bundled, would then never be asked for.
     */
    private fun deviceLocale(context: Context): String {
        val locale: Locale? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            LocaleList.getDefault().get(0)
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return (locale ?: Locale.getDefault()).toLanguageTag()
    }
}

/** Reads `string` and `plurals` resources of the app by name. */
internal class AndroidBundledStrings(context: Context) : BundledStrings {
    private val resources = context.resources
    private val packageName = context.packageName

    override fun find(resourceName: String, count: Long?): String? {
        try {
            if (count != null) {
                val plurals = resources.getIdentifier(resourceName, "plurals", packageName)
                if (plurals != 0) {
                    val quantity = count.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    return resources.getQuantityText(plurals, quantity).toString()
                }
            }
            val string = resources.getIdentifier(resourceName, "string", packageName)
            if (string != 0) return resources.getText(string).toString()
        } catch (failure: RuntimeException) {
            // A resource that cannot be read is treated as missing.
        }
        return null
    }
}
