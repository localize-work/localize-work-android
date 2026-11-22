package work.localize.sdk

import java.io.File

/** When a release that was just downloaded starts being used. */
public enum class UpdatePolicy {
    /** As soon as all of its files have arrived. Listeners are told, so screens can re-read their texts. */
    IMMEDIATE,

    /**
     * The next time the app starts. Texts never change while the app is
     * running; the downloaded release waits on disk until then.
     */
    NEXT_LAUNCH,
}

/**
 * Settings for [Localize]. Build one with [LocalizeConfig.Builder], or in
 * Kotlin with the `LocalizeConfig("my-project") { … }` function.
 */
public class LocalizeConfig private constructor(builder: Builder) {
    /** The project slug, as it appears in the project's CDN addresses. */
    public val projectSlug: String = builder.projectSlug

    /** Where published files are served from, without a trailing slash. */
    public val cdnBaseUrl: String = CdnUrls.trimTrailingSlashes(builder.cdnBaseUrl.trim())

    /** The namespaces to download. */
    public val namespaces: List<String> = builder.namespaces.toList()

    /** The namespace a lookup uses when it does not name one. */
    public val defaultNamespace: String = builder.defaultNamespace ?: namespaces.firstOrNull().orEmpty()

    /** The locale to show, or `null` for the device's first preferred locale. */
    public val locale: String? = builder.locale?.let(LocaleTags::canonicalize)

    /** The locale tried after [locale] and its base language; usually the project's source language. */
    public val fallbackLocale: String? = builder.fallbackLocale?.let(LocaleTags::canonicalize)

    /** A fixed release such as `v12`, or `null` to follow the project's latest release. */
    public val version: String? = builder.version

    public val updatePolicy: UpdatePolicy = builder.updatePolicy

    /** Whether [Localize.init] also starts a download in the background. */
    public val refreshOnInit: Boolean = builder.refreshOnInit

    public val connectTimeoutMillis: Int = builder.connectTimeoutMillis

    public val readTimeoutMillis: Int = builder.readTimeoutMillis

    /** A replacement for the built-in HTTP client, or `null` to use it. */
    public val transport: LocalizeTransport? = builder.transport

    /** Where downloaded releases are kept, or `null` for a folder in the app's private storage. */
    public val cacheDirectory: File? = builder.cacheDirectory

    init {
        require(projectSlug.isNotBlank()) { "projectSlug must not be empty" }
        require(projectSlug != "." && projectSlug != "..") { "projectSlug must not be \"$projectSlug\"" }
        require(cdnBaseUrl.startsWith("https://") || cdnBaseUrl.startsWith("http://")) {
            "cdnBaseUrl must start with https:// or http:// (got \"$cdnBaseUrl\")"
        }
        require(cdnBaseUrl.substringAfter("://").isNotEmpty()) { "cdnBaseUrl has no host" }
        require(namespaces.isNotEmpty()) { "namespaces must not be empty" }
        for (namespace in namespaces) {
            require(namespace.isNotBlank()) { "a namespace must not be empty" }
        }
        require(namespaces.toSet().size == namespaces.size) { "namespaces must not repeat: $namespaces" }
        require(defaultNamespace in namespaces) {
            "defaultNamespace \"$defaultNamespace\" is not one of the namespaces $namespaces"
        }
        builder.locale?.let { require(LocaleTags.isWellFormed(it)) { "locale \"$it\" is not a language tag" } }
        builder.fallbackLocale?.let {
            require(LocaleTags.isWellFormed(it)) { "fallbackLocale \"$it\" is not a language tag" }
        }
        version?.let {
            require(CdnUrls.isVersion(it)) { "version must look like \"v12\" (got \"$it\")" }
        }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
    }

    /**
     * Collects the settings. Only the project slug is required.
     *
     * [build] throws [IllegalArgumentException] for a setting that cannot
     * work, such as an empty slug or a version that is not `v` and a number.
     */
    public class Builder(internal val projectSlug: String) {
        internal var cdnBaseUrl: String = DEFAULT_CDN_BASE_URL
        internal var namespaces: List<String> = listOf(DEFAULT_NAMESPACE)
        internal var defaultNamespace: String? = null
        internal var locale: String? = null
        internal var fallbackLocale: String? = null
        internal var version: String? = null
        internal var updatePolicy: UpdatePolicy = UpdatePolicy.IMMEDIATE
        internal var refreshOnInit: Boolean = true
        internal var connectTimeoutMillis: Int = 10_000
        internal var readTimeoutMillis: Int = 15_000
        internal var transport: LocalizeTransport? = null
        internal var cacheDirectory: File? = null

        /** Where published files are served from. Default: `https://cdn.localize.work`. */
        public fun cdnBaseUrl(url: String): Builder = apply { cdnBaseUrl = url }

        /** The namespaces to download. Default: `translation`. */
        public fun namespaces(vararg namespaces: String): Builder = apply { this.namespaces = namespaces.toList() }

        /** The namespaces to download. Default: `translation`. */
        public fun namespaces(namespaces: List<String>): Builder = apply { this.namespaces = namespaces.toList() }

        /** The namespace a lookup uses when it names none. Default: the first of [namespaces]. */
        public fun defaultNamespace(namespace: String): Builder = apply { defaultNamespace = namespace }

        /** The locale to show, such as `et` or `en-FI`. Default: the device's first preferred locale. */
        public fun locale(locale: String?): Builder = apply { this.locale = locale }

        /** The locale tried when a text is missing in [locale]; usually the project's source language. */
        public fun fallbackLocale(locale: String?): Builder = apply { fallbackLocale = locale }

        /**
         * Pins one release, such as `v12`, instead of following the latest.
         * `null` or `"latest"` follows the latest release.
         */
        public fun version(version: String?): Builder = apply {
            this.version = if (version == "latest") null else version
        }

        /** When a downloaded release starts being used. Default: [UpdatePolicy.IMMEDIATE]. */
        public fun updatePolicy(policy: UpdatePolicy): Builder = apply { updatePolicy = policy }

        /** Whether [Localize.init] starts a download right away. Default: `true`. */
        public fun refreshOnInit(refresh: Boolean): Builder = apply { refreshOnInit = refresh }

        /** How long to wait for a connection. Default: 10 seconds. */
        public fun connectTimeoutMillis(millis: Int): Builder = apply { connectTimeoutMillis = millis }

        /** How long to wait for data on an open connection. Default: 15 seconds. */
        public fun readTimeoutMillis(millis: Int): Builder = apply { readTimeoutMillis = millis }

        /** Replaces the built-in HTTP client, for example with canned responses in a test. */
        public fun transport(transport: LocalizeTransport?): Builder = apply { this.transport = transport }

        /** Where to keep downloaded releases. Default: a folder in the app's private storage. */
        public fun cacheDirectory(directory: File?): Builder = apply { cacheDirectory = directory }

        public fun build(): LocalizeConfig = LocalizeConfig(this)
    }

    public companion object {
        public const val DEFAULT_CDN_BASE_URL: String = "https://cdn.localize.work"

        /** i18next's default namespace, and so the name most projects publish under. */
        public const val DEFAULT_NAMESPACE: String = "translation"

        /** The version of this library. */
        public const val SDK_VERSION: String = "0.1.0"
    }
}

/**
 * Builds a [LocalizeConfig] in Kotlin:
 *
 *     LocalizeConfig("my-project") {
 *         fallbackLocale("en")
 *         namespaces("common", "checkout")
 *     }
 */
@JvmSynthetic
public fun LocalizeConfig(projectSlug: String, configure: LocalizeConfig.Builder.() -> Unit = {}): LocalizeConfig =
    LocalizeConfig.Builder(projectSlug).apply(configure).build()
