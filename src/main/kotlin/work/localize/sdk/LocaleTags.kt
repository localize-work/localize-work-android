package work.localize.sdk

import java.util.Locale

/** BCP 47 tags as localize.work writes them: `en`, `en-FI`, `zh-Hant-TW`. */
internal object LocaleTags {
    /** Language codes older Java and Android versions still report. */
    private val legacyLanguages = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /**
     * The tag in the product's spelling: lower-case language, title-case
     * script, upper-case region. Extensions (`-u-…`, `-x-…`), which a device
     * adds for things like the first day of the week, are dropped, because a
     * published locale never has them.
     */
    fun canonicalize(tag: String): String {
        val subtags = tag.trim().split('-', '_').filter { it.isNotEmpty() }
        if (subtags.isEmpty()) return ""
        val out = StringBuilder()
        val language = subtags[0].lowercase(Locale.ROOT)
        out.append(legacyLanguages[language] ?: language)
        for (index in 1 until subtags.size) {
            val subtag = subtags[index]
            if (subtag.length == 1) break
            out.append('-')
            out.append(
                when {
                    subtag.length == 4 && subtag.all(::isAsciiLetter) ->
                        subtag.substring(0, 1).uppercase(Locale.ROOT) + subtag.substring(1).lowercase(Locale.ROOT)
                    subtag.length == 2 && subtag.all(::isAsciiLetter) -> subtag.uppercase(Locale.ROOT)
                    else -> subtag.lowercase(Locale.ROOT)
                },
            )
        }
        return out.toString()
    }

    /** Whether [tag] is made only of letters, digits and `-` or `_` separators. */
    fun isWellFormed(tag: String): Boolean {
        if (tag.isEmpty()) return false
        var previousWasSeparator = true
        for (char in tag) {
            val separator = char == '-' || char == '_'
            if (separator && previousWasSeparator) return false
            if (!separator && !isAsciiLetter(char) && char !in '0'..'9') return false
            previousWasSeparator = separator
        }
        return !previousWasSeparator
    }

    /**
     * The locales a text is looked up in, most specific first: the locale
     * itself, its shorter forms (`en-FI` → `en`), then the fallback locale and
     * its shorter forms, each once.
     */
    fun chain(locale: String, fallbackLocale: String?): List<String> {
        val chain = LinkedHashSet<String>()
        addWithParents(canonicalize(locale), chain)
        if (fallbackLocale != null) addWithParents(canonicalize(fallbackLocale), chain)
        return chain.toList()
    }

    private fun addWithParents(tag: String, into: MutableSet<String>) {
        var current = tag
        while (current.isNotEmpty()) {
            into.add(current)
            val dash = current.lastIndexOf('-')
            if (dash < 0) return
            current = current.substring(0, dash)
        }
    }

    private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
}
