package work.localize.sdk

/** One parsed namespace file: nested objects with texts at the leaves. */
internal typealias NamespaceTree = Map<String, Any?>

/**
 * The files of one published release that the app has, by locale and
 * namespace. Never changed after it is built, so any thread may read it.
 */
internal class Release(
    val version: String,
    private val files: Map<String, Map<String, NamespaceTree>>,
) {
    fun tree(locale: String, namespace: String): NamespaceTree? = files[locale]?.get(namespace)

    fun has(locale: String, namespace: String): Boolean = tree(locale, namespace) != null

    fun hasAll(locales: List<String>, namespaces: List<String>): Boolean =
        locales.all { locale -> namespaces.all { namespace -> has(locale, namespace) } }

    /**
     * This release with more files of the same version added; this very
     * release when it has all of them already.
     */
    fun plus(more: Map<String, Map<String, NamespaceTree>>): Release {
        if (more.all { (locale, namespaces) -> namespaces.keys.all { has(locale, it) } }) return this
        val merged = LinkedHashMap<String, Map<String, NamespaceTree>>(files)
        for ((locale, namespaces) in more) {
            merged[locale] = LinkedHashMap(merged[locale] ?: emptyMap()).apply { putAll(namespaces) }
        }
        return Release(version, merged)
    }

    /** This release with the files of [other], of the same version, added. */
    fun plus(other: Release): Release = plus(other.files)

    fun toJson(): String = Json.write(
        linkedMapOf("format" to FORMAT, "version" to version, "files" to files),
    )

    companion object {
        const val FORMAT: Long = 1

        /**
         * The objects [toJson] wraps a namespace file in: the document, its
         * `files`, and a locale. A file as deep as the parser takes from the
         * CDN must still be readable once it is stored inside them.
         */
        private const val ENVELOPE_DEPTH = 3

        /** Reads what [toJson] wrote; `null` for anything else. */
        fun fromJson(text: String, expectedVersion: String): Release? {
            val root = try {
                Json.parse(text, Json.MAX_DEPTH + ENVELOPE_DEPTH) as? Map<*, *> ?: return null
            } catch (invalid: JsonException) {
                return null
            }
            if (root["format"] != FORMAT || root["version"] != expectedVersion) return null
            val files = root["files"] as? Map<*, *> ?: return null
            val result = LinkedHashMap<String, Map<String, NamespaceTree>>()
            for ((locale, namespaces) in files) {
                if (locale !is String || namespaces !is Map<*, *>) return null
                val trees = LinkedHashMap<String, NamespaceTree>()
                for ((namespace, tree) in namespaces) {
                    if (namespace !is String || tree !is Map<*, *>) return null
                    @Suppress("UNCHECKED_CAST")
                    trees[namespace] = tree as NamespaceTree
                }
                result[locale] = trees
            }
            return Release(expectedVersion, result)
        }
    }
}
