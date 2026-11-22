package work.localize.sdk

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import work.localize.sdk.LocalizeException.Reason

/**
 * Everything [Localize] does, without Android classes, so it runs in plain
 * JVM unit tests.
 *
 * Lookups read one immutable [Snapshot] and never wait. A refresh builds a
 * complete release off to the side and swaps the snapshot in one step, so a
 * lookup sees the old release or the new one, never a mixture.
 *
 * @param deviceLocale the locale to use when the configuration names none
 * @param worker runs downloads; must not be the caller's thread in production
 * @param callbacks delivers results and listener calls (the main thread on Android)
 * @param continuing the client this one takes the place of while the app
 *   keeps running; it must have been [close]d
 */
internal class LocalizeClient(
    private val config: LocalizeConfig,
    cacheRoot: File,
    deviceLocale: String,
    transport: LocalizeTransport,
    private val bundled: BundledStrings,
    private val worker: Executor,
    private val callbacks: Executor,
    private val listeners: CopyOnWriteArrayList<LocalizeListener> = CopyOnWriteArrayList(),
    sleeper: Sleeper = Sleeper { millis -> Thread.sleep(millis) },
    continuing: LocalizeClient? = null,
) {
    /**
     * @param release the release the app holds for this run, which after a
     *   change of locale may lack files for the new one
     * @param usable [release] when it has every file [chain] and the
     *   namespaces need, else `null`: a release is read whole or not at all,
     *   and until it is whole the app's bundled strings, which are in the
     *   user's language, are the better text
     */
    private class Snapshot(val locale: String, val chain: List<String>, val release: Release?, val usable: Release?)

    private fun snapshotOf(locale: String, chain: List<String>, release: Release?) =
        Snapshot(locale, chain, release, release?.takeIf { it.hasAll(chain, config.namespaces) })

    private val store = ReleaseStore(cacheRoot, config.projectSlug, config.cdnBaseUrl)
    private val loader = ReleaseLoader(config, transport, sleeper)

    /** Held for a whole refresh, so two refreshes never interleave. */
    private val refreshLock = Any()

    /** Guards [snapshot] writes and [pending]. */
    private val stateLock = Any()

    @Volatile
    private var snapshot: Snapshot

    /** A downloaded release waiting for the next launch ([UpdatePolicy.NEXT_LAUNCH]). */
    private var pending: Release? = null

    @Volatile
    private var closed = false

    init {
        val locale = LocaleTags.canonicalize(config.locale ?: deviceLocale).ifEmpty { "und" }
        // A client that takes over from another one for the same project is
        // still the same run of the app, and goes on from the release that
        // one held.
        val sameRun = continuing?.takeIf { it.store.isSameAs(store) }
        val matchesPin = { release: Release -> config.version == null || release.version == config.version }
        val started = if (sameRun != null && config.updatePolicy == UpdatePolicy.NEXT_LAUNCH) {
            // The release a run shows is decided when the app starts, so it
            // is what that client held, not the release that waits on the
            // device. That holds for a run that started with nothing, too.
            val (held, waiting) = sameRun.heldAndPending()
            pending = waiting?.takeIf(matchesPin)
            held?.takeIf(matchesPin)
        } else {
            // The newest release is read as soon as it is here, and the
            // stored one is that. When a release could not be stored it is
            // in use from memory only, and is not dropped for that.
            store.load(config.version) ?: sameRun?.heldAndPending()?.first?.takeIf(matchesPin)
        }
        snapshot = snapshotOf(locale, LocaleTags.chain(locale, config.fallbackLocale), started)
    }

    private fun heldAndPending(): Pair<Release?, Release?> = synchronized(stateLock) { snapshot.release to pending }

    /** The locale texts are shown in. */
    val locale: String get() = snapshot.locale

    /** The locales a text is looked up in, in order. */
    val localeChain: List<String> get() = snapshot.chain

    /** The release in use, or `null` while there is none the current locale can be read from. */
    val version: String? get() = snapshot.usable?.version

    fun string(key: String, args: Map<String, Any?>, count: Long?, namespace: String?, fallback: String?): String {
        val current = snapshot
        val resolvedNamespace = namespace ?: config.defaultNamespace
        val release = current.usable
        if (release != null) {
            for (locale in current.chain) {
                val tree = release.tree(locale, resolvedNamespace) ?: continue
                val text = ResourceLookup.find(tree, key, count, locale)
                if (text != null) return Interpolator.interpolate(text, args, count)
            }
            // No locale has the form the count asks for, nor the plain key.
            // Before giving up on the release, take a language's general form.
            if (count != null) {
                for (locale in current.chain) {
                    val tree = release.tree(locale, resolvedNamespace) ?: continue
                    val text = ResourceLookup.findOther(tree, key)
                    if (text != null) return Interpolator.interpolate(text, args, count)
                }
            }
        }
        val resource = try {
            bundled.find(ResourceNames.forKey(resolvedNamespace, key), count)
        } catch (failure: RuntimeException) {
            null
        }
        if (resource != null) return BundledText.format(resource, args, count)
        if (fallback != null) return Interpolator.interpolate(fallback, args, count)
        return key
    }

    /**
     * Switches the locale. When the release the app holds already has every
     * file of the new locale it is read at once; otherwise the app's bundled
     * strings are shown until the refresh this starts has downloaded the rest.
     */
    fun setLocale(locale: String, callback: RefreshCallback?) {
        require(LocaleTags.isWellFormed(locale)) { "locale \"$locale\" is not a language tag" }
        val canonical = LocaleTags.canonicalize(locale)
        var changedVersion: String? = null
        var changed = false
        synchronized(stateLock) {
            val current = snapshot
            if (current.locale != canonical) {
                val next = snapshotOf(canonical, LocaleTags.chain(canonical, config.fallbackLocale), current.release)
                snapshot = next
                changed = true
                changedVersion = next.usable?.version
            }
        }
        if (changed) notifyListeners(changedVersion)
        refresh(callback)
    }

    /** Downloads the current release on [worker] and reports through [callback]. */
    fun refresh(callback: RefreshCallback?) {
        try {
            worker.execute {
                val result = refreshNow()
                deliver(callback, result)
            }
        } catch (rejected: RejectedExecutionException) {
            deliver(callback, failed(null, LocalizeException(Reason.NOT_INITIALIZED, "the SDK was shut down")))
        }
    }

    /**
     * Stops this client from changing anything further; used when [Localize]
     * is set up again. Returns once a write to the device that was under way
     * has finished; none is started after it.
     */
    fun close() {
        closed = true
        store.close()
    }

    /**
     * The read algorithm, on the calling thread: resolve the version once,
     * fetch every file the locale chain and the namespaces need from that one
     * version, and only when all of them are in hand switch to it.
     */
    internal fun refreshNow(): RefreshResult {
        synchronized(refreshLock) {
            return refreshLocked()
        }
    }

    private fun refreshLocked(): RefreshResult {
        if (closed) return replaced(null)
        // First the release the app already holds. After a change of locale
        // it lacks the new locale's files; they come from that same version,
        // whatever is published now and whether or not the pointer or a
        // newer release can be reached.
        val startChain = snapshot.chain
        val completedHeld = completeReleaseInUse(startChain)
        var completedLater = false
        var chain = startChain
        var version: String? = null
        val result = try {
            val resolved = loader.resolveVersion()
            version = resolved
            var switched: RefreshResult? = null
            // The locale can be switched while files are on their way. What
            // was fetched for the earlier locale is then not a whole release
            // for the current one, so the rest is fetched from the same
            // version before anything is put in use.
            var fetched: Map<String, Map<String, NamespaceTree>> = emptyMap()
            for (attempt in 1..MAX_LOCALE_CHANGES) {
                // After a switch of locale the release held comes first, as
                // it does at the start of a refresh.
                if (chain != startChain && completeReleaseInUse(chain)) completedLater = true
                // Files of an immutable version the app already holds are not
                // fetched again. A version the pointer (or the pin) names is
                // there, so a file it does not have is an empty file, and
                // the texts of that locale come from the next one in the
                // chain. [completeReleaseInUse] cannot take that for granted
                // of the release it completes.
                val held = synchronized(stateLock) {
                    sequenceOf(snapshot.release, pending).firstOrNull { it?.version == resolved }
                }
                val download = download(resolved, chain, held, fetched)
                if (config.version != null && held == null && download.foundNothing) {
                    // Most likely the pinned release is not published yet. Storing
                    // it as empty would hide it for good once it does exist.
                    throw LocalizeException(
                        Reason.HTTP_ERROR,
                        "release $resolved has no files for ${chain.joinToString()}; is it published?",
                        status = 404,
                    )
                }
                fetched = download.files
                if (closed) {
                    switched = replaced(resolved)
                    break
                }
                switched = switchTo(resolved, fetched)
                if (switched != null) break
                chain = snapshot.chain
            }
            switched ?: failed(
                resolved,
                LocalizeException(Reason.NETWORK, "the locale kept changing while release $resolved was downloaded"),
            )
        } catch (failure: LocalizeException) {
            failed(version, failure)
        } catch (failure: RuntimeException) {
            failed(version, LocalizeException(Reason.INVALID_RESPONSE, "refresh failed: $failure", cause = failure))
        }
        // The locale was switched while this refresh ran, and the refresh
        // ended before it came to that: the release held gets the newer
        // locale's files as well.
        val current = snapshot.chain
        if (current != startChain && !closed && completeReleaseInUse(current)) completedLater = true
        if ((completedHeld || completedLater) && result.status == RefreshResult.Status.UP_TO_DATE) {
            // Nothing newer is published, but files this refresh fetched are in use now.
            return RefreshResult(RefreshResult.Status.APPLIED, result.version, null)
        }
        return result
    }

    private class Download(val files: Map<String, Map<String, NamespaceTree>>, val foundNothing: Boolean)

    /**
     * Fetches the files of [version] that [chain] and the namespaces need and
     * [held] and [already] lack. Throws when one of them cannot be had, so
     * the caller gets all of them or none.
     */
    private fun download(
        version: String,
        chain: List<String>,
        held: Release?,
        already: Map<String, Map<String, NamespaceTree>> = emptyMap(),
    ): Download {
        val files = LinkedHashMap<String, MutableMap<String, NamespaceTree>>()
        for ((locale, namespaces) in already) files[locale] = LinkedHashMap(namespaces)
        var found = already.values.any { namespaces -> namespaces.values.any { it.isNotEmpty() } }
        for (locale in chain) {
            for (namespace in config.namespaces) {
                if (held?.has(locale, namespace) == true) continue
                if (files[locale]?.containsKey(namespace) == true) continue
                val tree = loader.fetchFile(version, locale, namespace)
                if (tree != null) found = true
                files.getOrPut(locale) { LinkedHashMap() }[namespace] = tree ?: emptyMap()
            }
        }
        return Download(files, foundNothing = files.isNotEmpty() && !found)
    }

    /**
     * Makes the release the app holds whole for [chain], from its own
     * version, and returns whether that put it in use. A change of locale
     * never changes which version is shown: the files the release lacks for
     * the new locale are fetched from that same release, since adding files
     * of the version already shown changes no text on screen and mixes
     * nothing. Nothing is thrown: when the files cannot be had now, the app
     * shows bundled strings and the next refresh tries again.
     *
     * When none of the missing files exists, the release may be gone from the
     * CDN, and reading it with empty files would show its fallback language
     * in place of the app's bundled strings. It is completed all the same
     * when every locale that lacks files is a regional form of a language the
     * release has texts for: a regional locale without overrides of its own
     * has no file, and that is normal.
     */
    private fun completeReleaseInUse(chain: List<String>): Boolean {
        val active = snapshot.release ?: return false
        if (active.hasAll(chain, config.namespaces)) return false
        val more = try {
            download(active.version, chain, active)
        } catch (failure: LocalizeException) {
            return false
        } catch (failure: RuntimeException) {
            return false
        }
        if (closed) return false
        if (more.foundNothing && !more.files.keys.all { isRegionalFormOfHeldLanguage(active, it) }) return false
        val usable = synchronized(stateLock) {
            val current = snapshot
            // The release may have been replaced while the files were on their way.
            if (current.release !== active) return false
            val extended = active.plus(more.files)
            store.save(extended.version, pending?.version, extended)
            val next = snapshotOf(current.locale, current.chain, extended)
            snapshot = next
            next.usable != null
        }
        if (usable) notifyListeners(active.version)
        return usable
    }

    private fun isRegionalFormOfHeldLanguage(release: Release, locale: String): Boolean =
        LocaleTags.parents(locale).any { parent ->
            config.namespaces.any { namespace -> release.tree(parent, namespace)?.isNotEmpty() == true }
        }

    /**
     * Puts [version] with the [fetched] files in use, or stores it for the
     * next launch. `null` when the locale was switched meanwhile and the
     * files do not make a whole release for the current one: nothing is
     * changed then, and the caller fetches the rest.
     */
    private fun switchTo(version: String, fetched: Map<String, Map<String, NamespaceTree>>): RefreshResult? {
        var notifyVersion: String? = null
        val result = synchronized(stateLock) {
            val current = snapshot
            val active = current.release
            if (active != null && active.version == version) {
                val extended = active.plus(fetched)
                if (!extended.hasAll(current.chain, config.namespaces)) return@synchronized null
                // The release the app holds is the current one. A release that
                // was waiting for the next launch has been rolled back; it is
                // forgotten once the device no longer names it, so that a
                // write that failed is tried again by the next refresh.
                val hadPending = pending != null
                if (fetched.isEmpty() && current.usable != null) {
                    if (hadPending && store.save(active.version, null, null)) pending = null
                    return@synchronized RefreshResult(RefreshResult.Status.UP_TO_DATE, version, null)
                }
                // More files of the same immutable release (a new locale or
                // namespace): nothing the app already shows can change.
                if (store.save(extended.version, null, extended) || !hadPending) pending = null
                snapshot = snapshotOf(current.locale, current.chain, extended)
                notifyVersion = version
                return@synchronized RefreshResult(RefreshResult.Status.APPLIED, version, null)
            }

            val waiting = pending?.takeIf { it.version == version }
            val release = waiting?.plus(fetched) ?: Release(version, fetched)
            if (!release.hasAll(current.chain, config.namespaces)) return@synchronized null
            if (config.updatePolicy == UpdatePolicy.IMMEDIATE) {
                // Kept in memory even when the disk refuses it; the next
                // launch then starts from the older copy and refreshes again.
                store.save(release.version, null, release)
                pending = null
                snapshot = snapshotOf(current.locale, current.chain, release)
                notifyVersion = version
                return@synchronized RefreshResult(RefreshResult.Status.APPLIED, version, null)
            }

            if (waiting != null && fetched.isEmpty()) {
                return@synchronized RefreshResult(RefreshResult.Status.DOWNLOADED, version, null)
            }
            if (!store.save(active?.version, release.version, release)) {
                return@synchronized failed(
                    version,
                    LocalizeException(Reason.STORAGE, "release $version could not be stored for the next launch"),
                )
            }
            pending = release
            RefreshResult(RefreshResult.Status.DOWNLOADED, version, null)
        }
        if (result?.status == RefreshResult.Status.APPLIED) notifyListeners(notifyVersion)
        return result
    }

    private fun replaced(version: String?) =
        failed(version, LocalizeException(Reason.NOT_INITIALIZED, "this configuration was replaced"))

    private fun failed(version: String?, error: LocalizeException) =
        RefreshResult(RefreshResult.Status.FAILED, version, error)

    private fun notifyListeners(version: String?) {
        if (listeners.isEmpty()) return
        run(callbacks) {
            for (listener in listeners) {
                try {
                    listener.onStringsChanged(version)
                } catch (failure: RuntimeException) {
                    // One listener's failure must not keep the others from hearing.
                }
            }
        }
    }

    private fun deliver(callback: RefreshCallback?, result: RefreshResult) {
        if (callback == null) return
        run(callbacks) { callback.onResult(result) }
    }

    private companion object {
        /** How many times one refresh follows a locale switched while it downloads. */
        const val MAX_LOCALE_CHANGES = 4
    }

    private fun run(executor: Executor, task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (rejected: RejectedExecutionException) {
            // The app is shutting down; there is nobody left to tell.
        }
    }
}
