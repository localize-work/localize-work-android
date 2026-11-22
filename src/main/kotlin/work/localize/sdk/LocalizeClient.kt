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
) {
    private class Snapshot(val locale: String, val chain: List<String>, val release: Release?)

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
        snapshot = Snapshot(locale, LocaleTags.chain(locale, config.fallbackLocale), store.load())
    }

    /** The locale texts are shown in. */
    val locale: String get() = snapshot.locale

    /** The locales a text is looked up in, in order. */
    val localeChain: List<String> get() = snapshot.chain

    /** The release in use, or `null` before the first download. */
    val version: String? get() = snapshot.release?.version

    fun string(key: String, args: Map<String, Any?>, count: Long?, namespace: String?, fallback: String?): String {
        val current = snapshot
        val resolvedNamespace = namespace ?: config.defaultNamespace
        val release = current.release
        if (release != null) {
            for (locale in current.chain) {
                val tree = release.tree(locale, resolvedNamespace) ?: continue
                val text = ResourceLookup.find(tree, key, count, locale)
                if (text != null) return Interpolator.interpolate(text, args, count)
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
     * Switches the locale. Texts the release in use already has for the new
     * locale are used at once; the rest are downloaded by the refresh this
     * starts.
     */
    fun setLocale(locale: String, callback: RefreshCallback?) {
        require(LocaleTags.isWellFormed(locale)) { "locale \"$locale\" is not a language tag" }
        val canonical = LocaleTags.canonicalize(locale)
        var changedVersion: String? = null
        var changed = false
        synchronized(stateLock) {
            val current = snapshot
            if (current.locale != canonical) {
                snapshot = Snapshot(canonical, LocaleTags.chain(canonical, config.fallbackLocale), current.release)
                changed = true
                changedVersion = current.release?.version
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

    /** Stops this client from changing anything further; used when [Localize] is set up again. */
    fun close() {
        closed = true
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
        val chain = snapshot.chain
        var version: String? = null
        val result = try {
            val resolved = loader.resolveVersion()
            version = resolved
            // Files of an immutable version the app already holds are not fetched again.
            val held = synchronized(stateLock) {
                sequenceOf(snapshot.release, pending).firstOrNull { it?.version == resolved }
            }
            val download = download(resolved, chain, held)
            if (config.version != null && held == null && download.foundNothing) {
                // Most likely the pinned release is not published yet. Storing
                // it as empty would hide it for good once it does exist.
                throw LocalizeException(
                    Reason.HTTP_ERROR,
                    "release $resolved has no files for ${chain.joinToString()}; is it published?",
                    status = 404,
                )
            }
            if (closed) replaced(resolved) else switchTo(resolved, download.files)
        } catch (failure: LocalizeException) {
            failed(version, failure)
        } catch (failure: RuntimeException) {
            failed(version, LocalizeException(Reason.INVALID_RESPONSE, "refresh failed: $failure", cause = failure))
        }
        if (config.updatePolicy == UpdatePolicy.NEXT_LAUNCH && version != null && !closed) {
            completeReleaseInUse(chain, version)
        }
        return result
    }

    private class Download(val files: Map<String, Map<String, NamespaceTree>>, val foundNothing: Boolean)

    /**
     * Fetches the files of [version] that [chain] and the namespaces need and
     * [held] lacks. Throws when one of them cannot be had, so the caller gets
     * all of them or none.
     */
    private fun download(version: String, chain: List<String>, held: Release?): Download {
        val files = LinkedHashMap<String, MutableMap<String, NamespaceTree>>()
        var found = false
        for (locale in chain) {
            for (namespace in config.namespaces) {
                if (held?.has(locale, namespace) == true) continue
                val tree = loader.fetchFile(version, locale, namespace)
                if (tree != null) found = true
                files.getOrPut(locale) { LinkedHashMap() }[namespace] = tree ?: emptyMap()
            }
        }
        return Download(files, foundNothing = files.isNotEmpty() && !found)
    }

    /**
     * [UpdatePolicy.NEXT_LAUNCH] only. A newer release waits for the next
     * launch, so the one in use stays in use for the rest of this run. If the
     * locale was switched to one it has no files for, they are fetched from
     * that same release: adding files of the version already shown changes no
     * text on screen and mixes nothing.
     */
    private fun completeReleaseInUse(chain: List<String>, currentVersion: String) {
        val active = snapshot.release ?: return
        if (active.version == currentVersion || active.hasAll(chain, config.namespaces)) return
        val more = try {
            download(active.version, chain, active)
        } catch (failure: LocalizeException) {
            return
        } catch (failure: RuntimeException) {
            return
        }
        // Nothing at all: the old release has probably been removed from the CDN.
        if (more.foundNothing || closed) return
        synchronized(stateLock) {
            val current = snapshot
            val extended = active.plus(more.files)
            store.save(extended.version, pending?.version, extended)
            snapshot = Snapshot(current.locale, current.chain, extended)
        }
        notifyListeners(active.version)
    }

    private fun switchTo(version: String, fetched: Map<String, Map<String, NamespaceTree>>): RefreshResult {
        var notifyVersion: String? = null
        val result = synchronized(stateLock) {
            val current = snapshot
            val active = current.release
            if (active != null && active.version == version) {
                // The release in use is the current one. A release that was
                // waiting for the next launch has been rolled back; forget it.
                val hadPending = pending != null
                pending = null
                if (fetched.isEmpty()) {
                    if (hadPending) store.save(active.version, null, null)
                    return@synchronized RefreshResult(RefreshResult.Status.UP_TO_DATE, version, null)
                }
                // More files of the same immutable release (a new locale or
                // namespace): nothing the app already shows can change.
                val extended = active.plus(fetched)
                store.save(extended.version, null, extended)
                snapshot = Snapshot(current.locale, current.chain, extended)
                notifyVersion = version
                return@synchronized RefreshResult(RefreshResult.Status.APPLIED, version, null)
            }

            val waiting = pending?.takeIf { it.version == version }
            val release = waiting?.plus(fetched) ?: Release(version, fetched)
            if (config.updatePolicy == UpdatePolicy.IMMEDIATE) {
                // Kept in memory even when the disk refuses it; the next
                // launch then starts from the older copy and refreshes again.
                store.save(release.version, null, release)
                pending = null
                snapshot = Snapshot(current.locale, current.chain, release)
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
        if (result.status == RefreshResult.Status.APPLIED) notifyListeners(notifyVersion)
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

    private fun run(executor: Executor, task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (rejected: RejectedExecutionException) {
            // The app is shutting down; there is nobody left to tell.
        }
    }
}
