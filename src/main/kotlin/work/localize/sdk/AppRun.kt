package work.localize.sdk

import java.io.File

/**
 * One run of the app, from its start to its end, and what it holds of every
 * project the SDK was set up for in that time.
 *
 * The release a run shows is decided when the app starts. `Localize.init` may
 * be called many times after that, for another locale, or for another project
 * and then the first one again, and every one of those is still the same run.
 * So what the run holds is kept here, apart from the clients that come and
 * go: the first client of a project reads the device, and every later one
 * goes on from what the run holds by then.
 */
internal class AppRun {
    private val projects = HashMap<Key, Project>()

    /**
     * What identifies the releases a client reads from the device: where they
     * are kept, the CDN they came from, and the version it is pinned to.
     */
    data class Key(val directory: File, val cdnBaseUrl: String, val pinnedVersion: String?)

    /**
     * What the run holds of one project. It is also the lock for it: a client
     * reads and changes these, puts a release in use and is closed all under
     * `synchronized(project)`, so the client that takes over finds a settled
     * state, whichever of the two got there first.
     */
    class Project {
        /**
         * The release this run reads. After a change of locale it may lack
         * files for the new one. `null` when the run has none: with
         * [UpdatePolicy.NEXT_LAUNCH] it then shows bundled strings to its end.
         */
        var held: Release? = null

        /** A downloaded release waiting for the next launch ([UpdatePolicy.NEXT_LAUNCH]). */
        var pending: Release? = null

        /**
         * Whether [held] has files the device could not take. It is in use
         * from memory all the same, and the next refresh stores it.
         */
        var unstored = false
    }

    /**
     * The run's record of the project [key] names, and whether this is the
     * first time in the run it is asked for. It is new then, and holds what
     * [onDevice] read: the release the run starts with.
     */
    fun project(key: Key, onDevice: () -> Release?): Pair<Project, Boolean> = synchronized(projects) {
        val known = projects[key]
        if (known != null) return known to false
        val started = Project().apply { held = onDevice() }
        projects[key] = started
        started to true
    }
}
