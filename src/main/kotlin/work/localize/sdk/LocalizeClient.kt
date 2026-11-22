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
 * @param appRun the run of the app this client is part of. A client that
 *   takes the place of another one in the same run, which must have been
 *   [close]d, is given the same one; a new one stands for a new launch
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
    internal val appRun: AppRun = AppRun(),
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

    /**
     * What this run of the app holds of the project, whichever client is in
     * use. Also the lock for it, for [snapshot] writes and for [closed].
     */
    private val project: AppRun.Project

    @Volatile
    private var snapshot: Snapshot

    /** Written under [project], so a client is closed before or after it puts a release in use, never in between. */
    @Volatile
    private var closed = false

    init {
        val locale = LocaleTags.canonicalize(config.locale ?: deviceLocale).ifEmpty { "und" }
        val key = AppRun.Key(store.directory, config.cdnBaseUrl, config.version)
        // The release a run shows is decided when the app starts, and that
        // is the first time the run asks for this project: what the device
        // holds then, where a release that waited becomes the one in use.
        val (project, first) = appRun.project(key) { store.read(config.version) }
        this.project = project
        snapshot = synchronized(project) {
            // A later client of the same run goes on from what the run holds
            // by now. With the next-launch policy that is all: the release
            // that waits on the device keeps waiting, and a run that started
            // with nothing still has nothing. A run that could not look yet
            // looks now.
            if (!first) lookAgain()
            if (!first && config.updatePolicy == UpdatePolicy.IMMEDIATE) {
                // The newest release is read as soon as it is here, and the
                // stored one is that. When a release could not be stored it
                // is held in memory only, and is not dropped for that.
                val stored = store.load(config.version)
                if (stored != null) {
                    project.held = stored
                    project.unstored = false
                }
                project.pending = null
            }
            snapshotOf(locale, LocaleTags.chain(locale, config.fallbackLocale), project.held)
        }
    }

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
        synchronized(project) {
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
     * is set up again. Returns once a release that was being put in use, and
     * its write to the device, are through; nothing is put in use or written
     * after it, so the client that takes over reads a settled state.
     */
    fun close() {
        synchronized(project) { closed = true }
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

    /**
     * A refresh is one or more passes. A pass works for the locale the app is
     * in when it starts, and everything it concludes is about that locale:
     * that the release held has the language, that a pinned release looks
     * unpublished, how the refresh ended.
     *
     * The one rule for all of it: **a conclusion holds only while the app is
     * still in that locale and this client is still the one in use**
     * ([stillHolds]). It is checked where a pass puts something in use or
     * stores it, under [project], and here before a result is reported. A
     * pass the locale has moved on from changes nothing and reports nothing;
     * the next pass starts over for the current locale. A client that was
     * replaced reports only that.
     *
     * What the CDN answered is no conclusion. [Facts] are true in any locale,
     * so they are kept from pass to pass and nothing is asked for twice. A
     * pass takes from them the files of its own locale chain and no others:
     * what the CDN answered for a locale the app has left was never weighed
     * for the locale it is in now.
     */
    private fun refreshLocked(): RefreshResult {
        startFromDevice()
        val facts = Facts()
        var applied = false
        repeat(MAX_LOCALE_CHANGES) {
            if (closed) return replaced(facts.version)
            val view = snapshot
            val pass = pass(view, facts)
            if (closed) return replaced(facts.version)
            applied = applied || pass.completedHeld || pass.result?.status == RefreshResult.Status.APPLIED
            val result = pass.result
            if (result != null && stillHolds(view)) {
                // Nothing newer is published, but files this refresh fetched are in use now.
                val upToDate = result.status == RefreshResult.Status.UP_TO_DATE
                return if (applied && upToDate) RefreshResult(RefreshResult.Status.APPLIED, result.version, null) else result
            }
        }
        return failed(
            facts.version,
            LocalizeException(Reason.NETWORK, "the locale kept changing while the refresh ran"),
        )
    }

    /**
     * Reads the device once more when it could not be read before
     * ([AppRun.Project.unread]): what is there now is what the run starts
     * with. Not in place of a release the run has put in use from memory
     * meanwhile. Returns whether the run got its release this way. Called
     * under [project].
     */
    private fun lookAgain(): Boolean {
        if (!project.unread) return false
        val stored = store.read(config.version)
        if (!stored.readable) return false
        project.unread = false
        if (project.held != null || stored.release == null) return false
        project.held = stored.release
        return true
    }

    /** [lookAgain] for a client in use: the release found is read at once when it is whole for the locale. */
    private fun startFromDevice() {
        val usable = synchronized(project) {
            if (closed || !lookAgain()) return
            project.held?.let(::hold)?.usable
        }
        if (usable != null) notifyListeners(usable.version)
    }

    /**
     * [ReleaseStore.save], under [project]. Once the run has written to the
     * device, what was there before is gone: nothing is left to look for.
     */
    private fun write(activeVersion: String?, pendingVersion: String?, changed: Release?): Boolean =
        store.save(activeVersion, pendingVersion, changed).also { stored -> if (stored) project.unread = false }

    /** Whether what a pass that started with [view] concluded may still be acted on. */
    private fun stillHolds(view: Snapshot): Boolean = !closed && snapshot.locale == view.locale

    /**
     * @param result how the pass ended; `null` when the locale was switched
     *   before it could put its release in use
     * @param completedHeld whether the release held was made whole and put in use
     */
    private class Pass(val result: RefreshResult?, val completedHeld: Boolean)

    /** One pass of a refresh, for the locale of [view]. */
    private fun pass(view: Snapshot, facts: Facts): Pass {
        // First the release the app already holds. After a change of locale
        // it lacks the new locale's files; they come from that same version,
        // whatever is published now and whether or not the pointer or a
        // newer release can be reached.
        val completedHeld = completeReleaseInUse(view, facts)
        val result = try {
            val version = facts.resolveVersion()
            // Files of an immutable version the app already holds are not
            // fetched again. A version the pointer (or the pin) names is
            // there, so a file it does not have is an empty file, and the
            // texts of that locale come from the next one in the chain.
            // [completeReleaseInUse] cannot take that for granted of the
            // release it completes.
            val held = synchronized(project) {
                sequenceOf(snapshot.release, project.pending).firstOrNull { it?.version == version }
            }
            val download = facts.files(version, view.chain, held)
            if (config.version != null && held == null && download.foundNothing) {
                // Most likely the pinned release is not published yet. Storing
                // it as empty would hide it for good once it does exist.
                throw LocalizeException(
                    Reason.HTTP_ERROR,
                    "release $version has no files for ${view.chain.joinToString()}; is it published?",
                    status = 404,
                )
            }
            switchTo(view, version, download.files)
        } catch (failure: LocalizeException) {
            failed(facts.version, failure)
        } catch (failure: RuntimeException) {
            failed(facts.version, LocalizeException(Reason.INVALID_RESPONSE, "refresh failed: $failure", cause = failure))
        }
        return Pass(result, completedHeld)
    }

    /**
     * What one refresh has learnt from the CDN: the version to read, and what
     * it answered for every file the refresh asked for. See [refreshLocked].
     */
    private inner class Facts {
        /** The version the pointer (or the pin) named; `null` until it has, or when it could not. */
        var version: String? = null
            private set
        private var versionFailure: LocalizeException? = null

        /** By version, locale and namespace: the file, or `null` for one the version does not have (a 404). */
        private val fetched = HashMap<Triple<String, String, String>, NamespaceTree?>()

        /** The files that could not be had. That is final for this refresh. */
        private val failures = HashMap<Triple<String, String, String>, LocalizeException>()

        /** The pointer is read once per refresh, also when reading it failed. */
        fun resolveVersion(): String {
            versionFailure?.let { throw it }
            version?.let { return it }
            try {
                return loader.resolveVersion().also { version = it }
            } catch (failure: LocalizeException) {
                versionFailure = failure
                throw failure
            }
        }

        /**
         * The files of [version] that [chain] and the namespaces need and
         * [held] lacks, and nothing of any other locale. Each is asked for
         * once in a refresh, whichever pass comes to it first and however
         * that ended. Throws when one of them cannot be had, so the caller
         * gets all of them or none.
         */
        fun files(version: String, chain: List<String>, held: Release?): Download {
            val files = LinkedHashMap<String, MutableMap<String, NamespaceTree>>()
            val answered = LinkedHashSet<String>()
            for (locale in chain) {
                for (namespace in config.namespaces) {
                    if (held?.has(locale, namespace) == true) continue
                    val tree = file(version, locale, namespace)
                    if (tree != null) answered.add(locale)
                    files.getOrPut(locale) { LinkedHashMap() }[namespace] = tree ?: emptyMap()
                }
            }
            return Download(files, answered)
        }

        private fun file(version: String, locale: String, namespace: String): NamespaceTree? {
            val file = Triple(version, locale, namespace)
            failures[file]?.let { throw it }
            if (file in fetched) return fetched[file]
            try {
                return loader.fetchFile(version, locale, namespace).also { fetched[file] = it }
            } catch (failure: LocalizeException) {
                failures[file] = failure
                throw failure
            }
        }
    }

    /**
     * @param files what was fetched; a file the release does not have (a 404)
     *   is here as an empty one
     * @param answered the locales the release did have a file for, which may
     *   itself be empty. Kept apart from [files], where the two look alike.
     */
    private class Download(val files: Map<String, Map<String, NamespaceTree>>, val answered: Set<String>) {
        val foundNothing: Boolean get() = files.isNotEmpty() && answered.isEmpty()
    }

    /**
     * Makes the release the app holds whole for the locale of [view], from its
     * own version, and returns whether that put it in use. A change of locale
     * never changes which version is shown: the files the release lacks for
     * the new locale are fetched from that same release, since adding files
     * of the version already shown changes no text on screen and mixes
     * nothing. Nothing is thrown: when the files cannot be had now, the app
     * shows bundled strings and the next refresh tries again.
     *
     * Nothing has named this release as the published one. If it answers "not
     * found" for the locale asked for, it may be gone from the CDN, or have
     * nothing in that language, and reading it with empty files would show
     * its fallback language in place of the app's bundled strings. So when a
     * file of the locale asked for or of its shorter forms is missing, the
     * release must have shown that it has the language: one of those files
     * was found now, or it already has texts in one of them
     * ([hasTextsIn]). A file of the fallback locale shows nothing of the kind.
     * With that shown, a file it does not have is an empty one, as for a
     * regional locale without overrides of its own or a namespace the
     * language has no texts in.
     */
    private fun completeReleaseInUse(view: Snapshot, facts: Facts): Boolean {
        val active = snapshot.release ?: return false
        if (active.hasAll(view.chain, config.namespaces)) return false
        val more = try {
            facts.files(active.version, view.chain, active)
        } catch (failure: LocalizeException) {
            return false
        } catch (failure: RuntimeException) {
            return false
        }
        // The locale asked for and its shorter forms, without the fallback locale.
        val asked = LocaleTags.chain(view.locale, null)
        val lacksAsked = asked.any { locale -> config.namespaces.any { !active.has(locale, it) } }
        if (lacksAsked && more.answered.none { it in asked } && !hasTextsIn(active, asked)) return false
        val usable = synchronized(project) {
            if (!stillHolds(view)) return false
            val extended = active.plus(more.files)
            project.unstored = !write(extended.version, project.pending?.version, extended)
            hold(extended).usable != null
        }
        if (usable) notifyListeners(active.version)
        return usable
    }

    /**
     * Whether [release] has texts in one of [locales], in the namespaces the
     * app asks for. Texts in a namespace it no longer asks for say little
     * about the ones it does.
     */
    private fun hasTextsIn(release: Release, locales: List<String>): Boolean =
        locales.any { locale ->
            config.namespaces.any { namespace -> release.tree(locale, namespace)?.isNotEmpty() == true }
        }

    /**
     * Makes [release] the one the run holds: lookups read it once it is whole
     * for the current locale, and whichever client the run uses next goes on
     * from it. Called under [project].
     */
    private fun hold(release: Release): Snapshot {
        val current = snapshot
        val next = snapshotOf(current.locale, current.chain, release)
        project.held = release
        snapshot = next
        return next
    }

    /**
     * Puts [version] with the [fetched] files in use, or stores it for the
     * next launch. `null` when the locale of [view] is no longer the app's,
     * or the client was replaced: nothing is changed then.
     */
    private fun switchTo(
        view: Snapshot,
        version: String,
        fetched: Map<String, Map<String, NamespaceTree>>,
    ): RefreshResult? {
        var notifyVersion: String? = null
        val result = synchronized(project) {
            if (!stillHolds(view)) return@synchronized null
            // From here the files are whole for the current locale: they
            // were fetched for the chain of [view], which is still the app's.
            val current = snapshot
            val active = current.release
            if (active != null && active.version == version) {
                val extended = active.plus(fetched)
                if (extended === active && current.usable != null) {
                    // The release the app holds is the current one. Three
                    // things may still be owed to the device. A release that
                    // was waiting for the next launch has been rolled back,
                    // and is forgotten once the device no longer names it.
                    // Files the device could not take earlier are written
                    // now. And what the run holds says little of the device,
                    // which another configuration of the app may have
                    // written since, one pinned to another version say: when
                    // the device no longer names this release, it is stored
                    // again. Each is tried again by the next refresh for as
                    // long as it fails.
                    if (project.pending != null || project.unstored || !store.names(active.version, null)) {
                        if (write(active.version, null, active)) {
                            project.pending = null
                            project.unstored = false
                        }
                    }
                    return@synchronized RefreshResult(RefreshResult.Status.UP_TO_DATE, version, null)
                }
                // More files of the same immutable release (a new locale or
                // namespace): nothing the app already shows can change.
                val stored = write(extended.version, null, extended)
                if (stored) project.pending = null
                project.unstored = !stored
                hold(extended)
                notifyVersion = version
                return@synchronized RefreshResult(RefreshResult.Status.APPLIED, version, null)
            }

            val waiting = project.pending?.takeIf { it.version == version }
            val release = waiting?.plus(fetched) ?: Release(version, fetched)
            if (config.updatePolicy == UpdatePolicy.IMMEDIATE) {
                // Kept in memory even when the device refuses it; the next
                // refresh tries to store it again, and until then the next
                // launch starts from the older copy.
                project.unstored = !write(release.version, null, release)
                project.pending = null
                hold(release)
                notifyVersion = version
                return@synchronized RefreshResult(RefreshResult.Status.APPLIED, version, null)
            }

            // A release the run has stored for the next launch waits there
            // only while the device says so. Another configuration of the
            // app may have removed it since; then it is stored again.
            if (release === waiting && store.names(active?.version, version)) {
                return@synchronized RefreshResult(RefreshResult.Status.DOWNLOADED, version, null)
            }
            if (!write(active?.version, release.version, release)) {
                return@synchronized failed(
                    version,
                    LocalizeException(Reason.STORAGE, "release $version could not be stored for the next launch"),
                )
            }
            project.pending = release
            RefreshResult(RefreshResult.Status.DOWNLOADED, version, null)
        }
        if (result?.status == RefreshResult.Status.APPLIED) notifyListeners(notifyVersion)
        return result
    }

    private fun replaced(version: String?) =
        failed(version, LocalizeException(Reason.NOT_INITIALIZED, "this configuration was replaced"))

    private fun failed(version: String?, error: LocalizeException) =
        RefreshResult(RefreshResult.Status.FAILED, version, error)

    /**
     * Runs [task] on [callbacks], where it may wait its turn, and tells it
     * whether this client is still the one in use when it gets there. By
     * then the SDK may have been set up again, and whatever the task has to
     * say is about a client the app no longer reads.
     */
    private fun onCallbacks(task: (live: Boolean) -> Unit) {
        try {
            callbacks.execute { task(!closed) }
        } catch (rejected: RejectedExecutionException) {
            // The app is shutting down; there is nobody left to tell.
        }
    }

    /** Tells the listeners of a change. A client that was replaced meanwhile tells them nothing. */
    private fun notifyListeners(version: String?) {
        if (listeners.isEmpty()) return
        onCallbacks { live ->
            if (!live) return@onCallbacks
            for (listener in listeners) {
                try {
                    listener.onStringsChanged(version)
                } catch (failure: RuntimeException) {
                    // One listener's failure must not keep the others from hearing.
                }
            }
        }
    }

    /** Hands [result] to [callback]. A client that was replaced meanwhile reports only that. */
    private fun deliver(callback: RefreshCallback?, result: RefreshResult) {
        if (callback == null) return
        onCallbacks { live -> callback.onResult(if (live) result else replaced(result.version)) }
    }

    private companion object {
        /** How many times one refresh follows a locale switched while it downloads. */
        const val MAX_LOCALE_CHANGES = 4
    }
}
