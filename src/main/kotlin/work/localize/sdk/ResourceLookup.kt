package work.localize.sdk

/**
 * Finds a text in one published file, the way i18next reads it.
 *
 * A published file nests keys on their dots (`nav.home` is
 * `{ "nav": { "home": … } }`). Where a text sits on the path of a deeper key
 * (keys `a` and `a.b`), the deeper key is written under its dotted name
 * instead, at the top level of the file or next to the text. So a lookup first
 * walks the nested path, and when that does not end at a text it tries the
 * remaining segments joined into one name at each level.
 */
internal object ResourceLookup {
    /**
     * The text stored under [key], or `null` when the file has none. An empty
     * text is a value and is returned as `""`.
     */
    fun find(tree: Map<String, Any?>, key: String): String? {
        val segments = key.split('.')

        // i18next takes the nested path when it leads to something non-empty.
        val nested = walkNested(tree, segments)
        if (nested is String && nested.isNotEmpty()) return nested

        // Then the whole key as one top-level name.
        val whole = tree[key]
        if (whole is String && whole.isNotEmpty()) return whole

        // Then level by level, taking the shortest name that exists and
        // lengthening it while it names a text that still has segments after it.
        var current: Any? = tree
        var index = 0
        while (index < segments.size) {
            val level = current as? Map<*, *> ?: return null
            var found = false
            var next: Any? = null
            val name = StringBuilder()
            var end = index
            while (end < segments.size) {
                if (end != index) name.append('.')
                name.append(segments[end])
                val candidate = name.toString()
                if (level.containsKey(candidate)) {
                    next = level[candidate]
                    val isScalar = next is String || next is Number || next is Boolean
                    if (!(isScalar && end < segments.size - 1)) {
                        found = true
                        index = end + 1
                        break
                    }
                }
                end++
            }
            if (!found) return null
            current = next
        }
        return current as? String
    }

    /**
     * The text for [key] and [count] in a file written in [locale], as
     * i18next looks it up: the form for a count of zero when the count is 0
     * and the file has one, the form of the count's plural category in that
     * language, then the key without a suffix. Without a count, the key
     * itself.
     *
     * A file that has none of them leaves the key to the next locale: a
     * regional variant that overrides only the `other` form inherits its
     * `one` form from the base language.
     */
    fun find(tree: Map<String, Any?>, key: String, count: Long?, locale: String): String? {
        if (count == null) return find(tree, key)
        if (count == 0L) find(tree, "${key}_${PluralRules.ZERO}")?.let { return it }
        find(tree, "${key}_${PluralRules.category(locale, count)}")?.let { return it }
        return find(tree, key)
    }

    /** The general plural form of [key]: the last resort when no locale has the count's own form. */
    fun findOther(tree: Map<String, Any?>, key: String): String? = find(tree, "${key}_${PluralRules.OTHER}")

    private fun walkNested(tree: Map<String, Any?>, segments: List<String>): Any? {
        var node: Any? = tree
        for (segment in segments) {
            val level = node as? Map<*, *> ?: return null
            node = level[segment] ?: return null
        }
        return node
    }
}
