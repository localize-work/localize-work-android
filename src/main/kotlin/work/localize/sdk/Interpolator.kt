package work.localize.sdk

/**
 * Fills `{{name}}` placeholders the way i18next writes them.
 *
 * - `{{name}}` and `{{ name }}` take the value given under `name`.
 * - `{{count}}` is the count of a plural lookup: the number that chose the
 *   plural form is the number shown. Without a count, a value named `count`
 *   is used like any other.
 * - `{{value, format}}` takes `value`; the format name is ignored.
 * - `{{- name}}`, i18next's "do not escape" form, is read as `{{name}}`.
 * - A placeholder without a value is left exactly as written.
 *
 * Values are inserted as they are: no HTML escaping, and no second pass over
 * an inserted value. `$t(…)` nesting is not expanded.
 */
internal object Interpolator {
    private const val OPEN = "{{"
    private const val CLOSE = "}}"

    fun interpolate(text: String, args: Map<String, Any?>, count: Long?): String {
        var open = text.indexOf(OPEN)
        if (open < 0) return text
        val out = StringBuilder(text.length + 16)
        var copiedTo = 0
        while (open >= 0) {
            val close = text.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) break
            val inner = text.substring(open + OPEN.length, close)
            if (inner.indexOf('{') >= 0) {
                // "{{{name}}": the placeholder, if any, starts at a later brace.
                open = text.indexOf(OPEN, open + 1)
                continue
            }
            val name = nameOf(inner)
            val replacement = when {
                name.isEmpty() -> null
                name == "count" && count != null -> count.toString()
                args.containsKey(name) -> args[name]?.toString() ?: ""
                else -> null
            }
            if (replacement != null) {
                out.append(text, copiedTo, open).append(replacement)
                copiedTo = close + CLOSE.length
            }
            open = text.indexOf(OPEN, close + CLOSE.length)
        }
        if (copiedTo == 0) return text
        return out.append(text, copiedTo, text.length).toString()
    }

    private fun nameOf(inner: String): String {
        var body = inner.trim()
        if (body.startsWith("-")) body = body.substring(1)
        val comma = body.indexOf(',')
        if (comma >= 0) body = body.substring(0, comma)
        return body.trim()
    }
}
