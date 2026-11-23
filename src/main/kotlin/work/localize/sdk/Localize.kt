package work.localize.sdk

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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

    /** This run of the app: what it holds of every project [init] was called for. */
    private val appRun = AppRun()

    /**
     * Sets the SDK up. Reads the stored release on the calling thread (a
     * small file read), then, unless [LocalizeConfig.refreshOnInit] is off,
     * downloads the current release in the background.
     *
     * Calling it again replaces the earlier configuration, whose downloads
     * then store nothing more. It is still the same run of the app: with
     * [UpdatePolicy.NEXT_LAUNCH] the release on screen stays, and one that
     * waits for the next launch keeps waiting. That holds for every project
     * the app is set up for in one run, also when it goes to another project
     * and back.
     */
    @JvmStatic
    public fun init(context: Context, config: LocalizeConfig) {
        val application = context.applicationContext ?: context
        val mainThread = Handler(Looper.getMainLooper())
        synchronized(this) {
            // The client being replaced may still be downloading. It is
            // stopped first, which waits for a release it is putting in use
            // just now, so the new client finds a settled state and the old
            // one writes nothing over what the new one stores.
            client?.close()
            client = LocalizeClient(
                config = config,
                cacheRoot = config.cacheDirectory ?: File(application.noBackupFilesDir, "localize-work"),
                deviceLocale = deviceLocale(application),
                transport = config.transport
                    ?: HttpUrlConnectionTransport(config.connectTimeoutMillis, config.readTimeoutMillis),
                bundled = AndroidBundledStrings(application),
                worker = worker,
                callbacks = { task -> mainThread.post(task) },
                listeners = listeners,
                appRun = appRun,
            )
        }
        if (config.refreshOnInit) refresh()
    }

    /**
     * The text for [key].
     *
     * The user's language comes first: the key is looked up in the locale,
     * then in its base language (`en` for `en-FI`), then in the app's string
     * resource of the matching name when the app has it in that language.
     * After that come the fallback locale, the app's string resource in
     * whatever language Android picks for the app, [fallback], and failing
     * all of those the key itself.
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
     * [callback] is called on the main thread with the outcome. After a
     * failed download the release already in use stays in use. One thing may
     * have changed all the same: after a change of locale, the files the
     * release the app holds lacked for the new locale are fetched from that
     * same version first, and are used and stored even when the rest fails.
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
    private val context = context.applicationContext ?: context
    private val resources = this.context.resources
    private val packageName = this.context.packageName

    /**
     * The app's resources as Android resolves them for one language ([own]),
     * and as it resolves them for a language no app has resources in
     * ([default]): the ones in `values/`. The two differ in the language and
     * in nothing else: both are fixed to the qualifiers the app had when they
     * were made ([seen]), and are made anew once the app's have changed. Two
     * sets that followed the app would not change together, and a lookup on
     * another thread while the screen turns would compare a portrait text
     * with a landscape one.
     *
     * @param probes the whole numbers that show whether a `plurals` resource
     *   is the one in `values/`
     */
    private class Language(
        val locale: String,
        val seen: Configuration,
        val own: Resources,
        val default: Resources,
        val probes: IntArray,
    )

    /** Those of the locale asked for last, which is the one in use. */
    @Volatile
    private var language: Language? = null

    override fun find(resourceName: String, count: Long?): String? {
        try {
            if (count != null) {
                val plurals = resources.getIdentifier(resourceName, "plurals", packageName)
                if (plurals != 0) return resources.getQuantityText(plurals, quantity(count)).toString()
            }
            val string = resources.getIdentifier(resourceName, "string", packageName)
            if (string != 0) return resources.getText(string).toString()
        } catch (failure: RuntimeException) {
            // A resource that cannot be read is treated as missing.
        }
        return null
    }

    /**
     * Android does not say which language a resource it returns is written
     * in: a text the app lacks in the language asked for comes from `values/`
     * without a word. So a text counts as written in the language of [locale]
     * when it is another text than the one in `values/`. The build drops a
     * resource that is in a language's folder alone, so `values/` has one;
     * a resource that is only in a folder with a further qualifier, such as
     * `values-et-night`, can lack it, and is treated as missing.
     *
     * That leaves out a text that reads the same in both, where nothing is
     * lost, and an app whose `values/` is itself in the user's language.
     */
    override fun findIn(locale: String, resourceName: String, count: Long?): String? {
        try {
            val language = languageOf(locale) ?: return null
            if (count != null) {
                val plurals = resources.getIdentifier(resourceName, "plurals", packageName)
                // A `plurals` resource the app has in `values/` alone leaves
                // the name to a `string` resource it has in the language.
                if (plurals != 0) pluralIn(language, plurals, quantity(count))?.let { return it }
            }
            val string = resources.getIdentifier(resourceName, "string", packageName)
            if (string == 0) return null
            val text = language.own.getText(string).toString()
            return text.takeIf { it != language.default.getText(string).toString() }
        } catch (failure: RuntimeException) {
            // A resource that cannot be read is treated as missing.
            return null
        }
    }

    /**
     * The form of a `plurals` resource for [quantity], when the resource is
     * written in the language.
     *
     * Both sets of resources are asked for the same number. A language that
     * has no resources counts nothing, so [Language.default] answers with the
     * general form of the resource in `values/`. For a number the language
     * asked for gives the general form to as well, the two answers are the
     * general forms of the two resources, and the same text means the same
     * resource. See [BundledPlurals.probes] for a language that has no such
     * number.
     */
    private fun pluralIn(language: Language, plurals: Int, quantity: Int): String? {
        for (probe in language.probes) {
            val general = language.default.getQuantityText(plurals, probe).toString()
            if (language.own.getQuantityText(plurals, probe).toString() == general) return null
        }
        return language.own.getQuantityText(plurals, quantity).toString()
    }

    private fun languageOf(locale: String): Language? {
        val current = resources.configuration
        language?.let { if (it.locale == locale && sameQualifiers(it.seen, current)) return it }
        val asked = Locale.forLanguageTag(locale)
        if (asked.language.isEmpty()) return null
        val none = Locale.forLanguageTag(NO_LANGUAGE)
        // Read once: both sets of resources start from the same values, also
        // when the app's configuration changes while it is read.
        val seen = qualifiersOf(current)
        return Language(
            locale, seen, resourcesIn(asked, asked, seen), resourcesIn(none, asked, seen), BundledPlurals.probes(locale),
        ).also { language = it }
    }

    /**
     * The app's resources for [locale], laid out as for [direction], with
     * every other qualifier, such as night mode or the screen's size, as in
     * [qualifiers]. Android keeps what a context like this one names over
     * what the app changes to later, so naming them keeps these resources as
     * they are. The direction is set apart from the locale so that both sets
     * of resources read the same folders but for the language's.
     */
    private fun resourcesIn(locale: Locale, direction: Locale, qualifiers: Configuration): Resources {
        val override = Configuration(qualifiers)
        override.setLocale(locale)
        override.setLayoutDirection(direction)
        return context.createConfigurationContext(override).resources
    }

    /**
     * A configuration of the library's own with the qualifiers of [app] that
     * decide which folder a resource is read from, but for the language.
     *
     * The app's configuration is the object Android itself keeps and changes,
     * on the main thread. It is only read here, field by field: copying it
     * whole, or comparing it with `diff`, makes Android tidy the language
     * list of the object it is given, and on another thread, in the middle
     * of a change of language, that could leave the app with the wrong list.
     */
    private fun qualifiersOf(app: Configuration): Configuration {
        val own = Configuration()
        own.fontScale = app.fontScale
        own.mcc = app.mcc
        own.mnc = app.mnc
        own.orientation = app.orientation
        own.screenLayout = app.screenLayout
        own.uiMode = app.uiMode
        own.screenWidthDp = app.screenWidthDp
        own.screenHeightDp = app.screenHeightDp
        own.smallestScreenWidthDp = app.smallestScreenWidthDp
        own.densityDpi = app.densityDpi
        own.touchscreen = app.touchscreen
        own.keyboard = app.keyboard
        own.keyboardHidden = app.keyboardHidden
        own.hardKeyboardHidden = app.hardKeyboardHidden
        own.navigation = app.navigation
        own.navigationHidden = app.navigationHidden
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) own.colorMode = app.colorMode
        return own
    }

    /** Whether [app] still has the qualifiers [seen] was made with; see [qualifiersOf]. */
    private fun sameQualifiers(seen: Configuration, app: Configuration): Boolean =
        seen.fontScale == app.fontScale &&
            seen.mcc == app.mcc &&
            seen.mnc == app.mnc &&
            seen.orientation == app.orientation &&
            seen.screenLayout == app.screenLayout &&
            seen.uiMode == app.uiMode &&
            seen.screenWidthDp == app.screenWidthDp &&
            seen.screenHeightDp == app.screenHeightDp &&
            seen.smallestScreenWidthDp == app.smallestScreenWidthDp &&
            seen.densityDpi == app.densityDpi &&
            seen.touchscreen == app.touchscreen &&
            seen.keyboard == app.keyboard &&
            seen.keyboardHidden == app.keyboardHidden &&
            seen.hardKeyboardHidden == app.hardKeyboardHidden &&
            seen.navigation == app.navigation &&
            seen.navigationHidden == app.navigationHidden &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || seen.colorMode == app.colorMode)

    private fun quantity(count: Long): Int = count.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        /** ISO 639's code for "no linguistic content": a language no resource folder is named after. */
        const val NO_LANGUAGE = "zxx"
    }
}
