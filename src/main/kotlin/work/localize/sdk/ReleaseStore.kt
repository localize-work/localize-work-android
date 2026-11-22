package work.localize.sdk

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Keeps downloaded releases on the device, so the app has its texts at start
 * and without a connection.
 *
 * One folder per project slug holds:
 *
 *     state.json     which release is in use, and which one waits for the next launch
 *     v12.json       every file of release v12 the app has downloaded
 *
 * A release file is written whole under a temporary name and then renamed, and
 * `state.json` is written after it, so a crash or a full disk leaves either
 * the old state or the new one. Anything that cannot be read is thrown away;
 * the cache is only ever a copy of what the CDN serves.
 *
 * Not safe for use from several threads at once; the caller serialises access.
 */
internal class ReleaseStore(root: File, projectSlug: String, private val cdnBaseUrl: String) {
    private val directory = File(root, directoryName(projectSlug))

    /**
     * The release to use at start, or `null` when there is none. A release
     * waiting for the next launch becomes the one in use. With a [pinned]
     * version only that version is taken, whichever of the two holds it; a
     * device that holds other versions keeps them and gets `null`. Never throws.
     */
    fun load(pinned: String? = null): Release? {
        try {
            val state = readState()
            if (state == null) {
                // No state, or one written for another CDN or by another format.
                wipe()
                return null
            }
            var active = state.active?.let(::readRelease)
            val pending = state.pending?.let(::readRelease)
            if (pinned != null) {
                active = sequenceOf(pending, active).firstOrNull { it?.version == pinned }
                // What is there belongs to the configuration that follows the
                // latest release; a refresh replaces it with the pinned one.
                if (active == null) return null
            } else if (pending != null) {
                active = pending
            }
            if (active == null) {
                wipe()
                return null
            }
            if (state.pending != null || state.active != active.version) {
                try {
                    writeState(active.version, null)
                } catch (failure: IOException) {
                    // Still usable from memory; the promotion is repeated at the next start.
                    return active
                }
            }
            removeAllExcept(setOf(active.version))
            return active
        } catch (failure: Exception) {
            try {
                wipe()
            } catch (ignored: Exception) {
                // Nothing more to do; the SDK works without a cache.
            }
            return null
        }
    }

    /**
     * Records that [activeVersion] is in use and [pendingVersion] waits for
     * the next launch, after writing [changed], the release whose files are
     * new. Other releases are removed. Returns whether it reached the disk.
     */
    fun save(activeVersion: String?, pendingVersion: String?, changed: Release?): Boolean = save {
        if (changed != null) writeRelease(changed)
        writeState(activeVersion, pendingVersion)
        removeAllExcept(setOfNotNull(activeVersion, pendingVersion))
    }

    private inline fun save(write: () -> Unit): Boolean =
        try {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("cannot create $directory")
            }
            write()
            true
        } catch (failure: IOException) {
            false
        } catch (failure: SecurityException) {
            false
        }

    private class State(val active: String?, val pending: String?)

    private fun readState(): State? {
        val file = File(directory, STATE_FILE)
        if (!file.isFile || file.length() > MAX_STATE_BYTES) return null
        val root = try {
            Json.parse(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return null
        } catch (invalid: JsonException) {
            return null
        }
        if (root["format"] != Release.FORMAT || root["cdnBaseUrl"] != cdnBaseUrl) return null
        val active = root["active"]
        val pending = root["pending"]
        if (active != null && (active !is String || !CdnUrls.isVersion(active))) return null
        if (pending != null && (pending !is String || !CdnUrls.isVersion(pending))) return null
        return State(active as String?, pending as String?)
    }

    private fun writeState(active: String?, pending: String?) {
        val state = linkedMapOf(
            "format" to Release.FORMAT,
            "cdnBaseUrl" to cdnBaseUrl,
            "active" to active,
            "pending" to pending,
        )
        writeAtomically(File(directory, STATE_FILE), Json.write(state))
    }

    private fun readRelease(version: String): Release? {
        val file = releaseFile(version)
        if (!file.isFile || file.length() > MAX_RELEASE_BYTES) return null
        return Release.fromJson(file.readText(Charsets.UTF_8), version)
    }

    private fun writeRelease(release: Release) {
        writeAtomically(releaseFile(release.version), release.toJson())
    }

    private fun releaseFile(version: String) = File(directory, "$version.json")

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(directory, "${target.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                // Some file systems do not rename over an existing file.
                target.delete()
                if (!temporary.renameTo(target)) throw IOException("cannot replace $target")
            }
        } finally {
            temporary.delete()
        }
    }

    /** Removes old releases and leftovers, keeping `state.json` and the named versions. */
    private fun removeAllExcept(versions: Set<String>) {
        val keep = versions.mapTo(HashSet()) { "$it.json" }
        keep.add(STATE_FILE)
        directory.listFiles()?.forEach { file ->
            if (file.name !in keep) file.deleteRecursively()
        }
    }

    private fun wipe() {
        if (directory.exists()) directory.deleteRecursively()
    }

    internal companion object {
        const val STATE_FILE = "state.json"
        private const val MAX_STATE_BYTES = 64L * 1024
        private const val MAX_RELEASE_BYTES = 256L * 1024 * 1024

        /**
         * A folder name for a slug that cannot leave the cache folder or clash
         * with another slug: letters, digits, `-` and `_` are kept, every
         * other byte is written as `%XX`.
         */
        fun directoryName(projectSlug: String): String {
            val out = StringBuilder(projectSlug.length)
            for (byte in projectSlug.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '_') {
                    out.append(char)
                } else {
                    out.append('%').append("0123456789ABCDEF"[code shr 4]).append("0123456789ABCDEF"[code and 0x0F])
                }
            }
            return out.toString()
        }
    }
}
