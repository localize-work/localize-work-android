package work.localize.sdk

/** Why a download did not complete. */
public class LocalizeException internal constructor(
    public val reason: Reason,
    message: String,
    /** The address that was being fetched, when the failure belongs to one. */
    public val url: String? = null,
    /** The HTTP status, when the server answered. */
    public val status: Int? = null,
    /**
     * Whether trying again later can help: `true` for a lost connection or a
     * server error, `false` for a problem in the configuration or the data.
     */
    public val isRetryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {

    public enum class Reason {
        /** No answer arrived: offline, timeout, connection reset. */
        NETWORK,

        /** The server answered with a 5xx status. */
        SERVER_ERROR,

        /**
         * The server refused the request (a 4xx status). For `latest.json` a
         * 404 means nothing has been published yet, or the slug is wrong.
         */
        HTTP_ERROR,

        /** `latest.json` did not name a release such as `v12`. A configuration problem, not retried. */
        INVALID_POINTER,

        /** A translation file was not a JSON object, or was too large. */
        INVALID_RESPONSE,

        /** The release could not be written to the device. */
        STORAGE,

        /** [Localize.init] has not been called, or was called again with other settings since. */
        NOT_INITIALIZED,
    }
}

/** How a call to [Localize.refresh] ended. */
public class RefreshResult internal constructor(
    public val status: Status,
    /** The release the result is about; `null` when it could not be determined. */
    public val version: String?,
    /** Set when [status] is [Status.FAILED]. */
    public val error: LocalizeException?,
) {
    public enum class Status {
        /**
         * Lookups read other texts now. A new release is in use; or the one
         * this run started with is, which lacked files for the locale or
         * could not be read from the device until now.
         */
        APPLIED,

        /** A new release is stored and takes effect the next time the app starts ([UpdatePolicy.NEXT_LAUNCH]). */
        DOWNLOADED,

        /** The release already in use is the current one, and lookups read what they read before. */
        UP_TO_DATE,

        /**
         * The download failed; the release already in use stays in use. After
         * a change of locale, files of the release the app holds may have
         * been added for the new locale all the same; listeners are told.
         */
        FAILED,
    }

    public val isSuccess: Boolean get() = status != Status.FAILED

    override fun toString(): String =
        "RefreshResult(status=$status, version=$version${if (error != null) ", error=${error.message}" else ""})"
}

/** Receives the outcome of [Localize.refresh]. Called on the main thread. */
public fun interface RefreshCallback {
    public fun onResult(result: RefreshResult)
}

/**
 * Told when the texts [Localize] returns may have changed: a downloaded
 * release took effect, or the locale was switched. Called on the main thread.
 */
public fun interface LocalizeListener {
    /** @param version the release now in use, or `null` when none has been downloaded yet. */
    public fun onStringsChanged(version: String?)
}
