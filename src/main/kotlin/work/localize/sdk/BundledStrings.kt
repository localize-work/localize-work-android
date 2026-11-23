package work.localize.sdk

/** The app's own string resources, used when no downloaded file has a text. */
internal fun interface BundledStrings {
    /**
     * The raw text of the resource called [resourceName], or `null` when the
     * app has none, in the language Android picks for the app. With a
     * [count], a `plurals` resource of that name is preferred over a `string`
     * resource.
     */
    fun find(resourceName: String, count: Long?): String?

    /**
     * The same, but only a text the app has in the language of [locale]:
     * `null` when the app has the resource in other languages alone.
     */
    fun findIn(locale: String, resourceName: String, count: Long?): String? = null
}

/**
 * How an app's `plurals` resource in one language is told from the one in
 * `values/`, of which only the general (`other`) form can be read.
 */
internal object BundledPlurals {
    /** The whole numbers looked at: enough to meet every form of every language. */
    private val wholeNumbers: List<Int> = (0..200) + 1_000_000

    /**
     * The numbers to ask a resource for in the language of [locale]. The
     * resource is the one in `values/` when it answers any of them with that
     * one's general form.
     *
     * Where the language gives the general form to a whole number, those are
     * such numbers: the answer is then the resource's own general form. The
     * first two, in case a device counts one of them differently than CLDR 47
     * does.
     *
     * Russian, Ukrainian, Belarusian and Polish give it to no whole number.
     * For them it is one number of each form: asked for a form `values/` does
     * not have, Android answers with the general one. That misses a resource
     * in `values/` that has every form the language asks for.
     */
    fun probes(locale: String): IntArray {
        val general = wholeNumbers.filter { PluralRules.category(locale, it.toLong()) == PluralRules.OTHER }
        if (general.isNotEmpty()) return general.take(2).toIntArray()
        return wholeNumbers.distinctBy { PluralRules.category(locale, it.toLong()) }.toIntArray()
    }
}

internal object ResourceNames {
    /** The namespace whose keys the Android export writes without a prefix. */
    private const val UNPREFIXED_NAMESPACE = "strings"

    /**
     * The resource name localize.work's Android export gives a key: the
     * namespace and the key joined by `_` (the key alone in a namespace called
     * `strings`), every character other than a letter, digit or underscore
     * replaced by `_`, and a leading `_` added when the name would start with
     * a digit. `translation` + `nav.home` is `translation_nav_home`.
     */
    fun forKey(namespace: String, key: String): String {
        val full = if (namespace == UNPREFIXED_NAMESPACE) key else "${namespace}_$key"
        val name = StringBuilder(full.length + 1)
        for (char in full) {
            val keep = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_'
            name.append(if (keep) char else '_')
        }
        if (name.isNotEmpty() && name[0] in '0'..'9') name.insert(0, '_')
        return name.toString()
    }
}

/**
 * Fills the arguments into a bundled resource text.
 *
 * localize.work's Android export writes a key's `{{name}}` placeholders as
 * numbered conversions (`%1$s`, `%2$s`), numbered in the order the variables
 * first appear in the source text, with the count of a plural key as `%N$d`.
 * The resource no longer names its variables, so the arguments are matched by
 * position: the first entry of the argument map is argument 1, and so on. The
 * count of a plural lookup, when it is not in the map itself, takes the
 * position of the text's `%N$d` conversion, or in a form that does not show
 * the count the position the text leaves unused. A form that shows neither
 * the count nor every other argument does not say where the count belongs;
 * an app with such texts names the count's place by putting an entry called
 * `count` into the map where the source text has it. The number shown there
 * is the count of the lookup.
 */
internal object BundledText {
    private class Conversion(val start: Int, val end: Int, val position: Int, val type: Char)

    /**
     * The count's position in a plural form that does not show the count
     * ("New messages for %2$s"). The export numbers a key's arguments alike in
     * every form, so the count still holds a position there: the text refers
     * to one position more than there are other arguments, and the count's is
     * the lowest one the text leaves unused. `null` when the text does not
     * show such a gap.
     */
    private fun unusedPosition(conversions: List<Conversion>, otherArguments: Int): Int? {
        val highest = conversions.maxOfOrNull { it.position } ?: return null
        if (highest != otherArguments + 1) return null
        val used = conversions.mapTo(HashSet()) { it.position }
        return (1..highest).firstOrNull { it !in used }
    }

    fun format(raw: String, args: Map<String, Any?>, count: Long?): String {
        val conversions = ArrayList<Conversion>()
        var hasDoubledPercent = false
        var sequential = 0
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '%') {
                index++
                continue
            }
            if (index + 1 < raw.length && raw[index + 1] == '%') {
                hasDoubledPercent = true
                index += 2
                continue
            }
            var cursor = index + 1
            var explicit = 0
            val digitsStart = cursor
            while (cursor < raw.length && raw[cursor] in '0'..'9' && cursor - digitsStart < 4) {
                explicit = explicit * 10 + (raw[cursor] - '0')
                cursor++
            }
            val numbered = cursor > digitsStart && cursor < raw.length && raw[cursor] == '$'
            if (numbered) cursor++ else cursor = index + 1
            if (cursor < raw.length && (raw[cursor] == 's' || raw[cursor] == 'd')) {
                val position = if (numbered) explicit else ++sequential
                conversions.add(Conversion(index, cursor + 1, position, raw[cursor]))
                index = cursor + 1
            } else {
                index++
            }
        }

        // The export doubles a literal "%" only in a text the app formats,
        // which is one that takes arguments.
        val formatted = conversions.isNotEmpty() || args.isNotEmpty() || count != null
        if (!formatted || (conversions.isEmpty() && !hasDoubledPercent)) {
            return Interpolator.interpolate(raw, args, count)
        }

        // An entry called "count" marks the count's place; the count that
        // chose the plural form is the number shown.
        val values = args.entries.map { (name, value) -> if (name == "count" && count != null) count else value }
        val countPosition = if (count != null && !args.containsKey("count")) {
            conversions.firstOrNull { it.type == 'd' }?.position ?: unusedPosition(conversions, values.size)
        } else {
            null
        }

        val out = StringBuilder(raw.length + 16)
        var copiedTo = 0
        fun appendLiteral(until: Int) {
            if (until > copiedTo) {
                val literal = raw.substring(copiedTo, until).replace("%%", "%")
                out.append(Interpolator.interpolate(literal, args, count))
            }
        }
        for (conversion in conversions) {
            appendLiteral(conversion.start)
            val position = conversion.position
            val valueIndex = when {
                countPosition == null -> position - 1
                position == countPosition -> -1
                position > countPosition -> position - 2
                else -> position - 1
            }
            when {
                countPosition != null && position == countPosition -> out.append(count)
                valueIndex in values.indices -> out.append(values[valueIndex]?.toString() ?: "")
                conversion.type == 'd' && count != null -> out.append(count)
                else -> out.append(raw, conversion.start, conversion.end)
            }
            copiedTo = conversion.end
        }
        appendLiteral(raw.length)
        return out.toString()
    }
}
