package work.localize.sdk

/**
 * The two paths of the delivery contract:
 *
 *     pointer:  {cdnBaseUrl}/{projectSlug}/latest.json
 *     release:  {cdnBaseUrl}/{projectSlug}/{version}/{locale}/{namespace}.json
 */
internal object CdnUrls {
    private const val UNRESERVED = "-_.!~*'()"
    private const val HEX = "0123456789ABCDEF"

    /** A release version: `v` and a positive whole number without a leading zero. */
    private val versionPattern = Regex("v[1-9][0-9]*")

    fun isVersion(value: String): Boolean = versionPattern.matches(value)

    fun pointer(cdnBaseUrl: String, projectSlug: String): String =
        "${trimTrailingSlashes(cdnBaseUrl)}/${encodeSegment(projectSlug)}/latest.json"

    fun file(cdnBaseUrl: String, projectSlug: String, version: String, locale: String, namespace: String): String =
        "${trimTrailingSlashes(cdnBaseUrl)}/${encodeSegment(projectSlug)}/${encodeSegment(version)}/" +
            "${encodeSegment(locale)}/${encodeSegment(namespace)}.json"

    fun trimTrailingSlashes(url: String): String = url.trimEnd('/')

    /** One path segment, percent-encoded the way JavaScript's `encodeURIComponent` does it. */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || UNRESERVED.indexOf(char) >= 0) {
                out.append(char)
            } else {
                out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return out.toString()
    }
}
