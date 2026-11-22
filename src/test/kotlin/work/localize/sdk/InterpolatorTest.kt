package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class InterpolatorTest {
    private fun fill(text: String, args: Map<String, Any?> = emptyMap(), count: Long? = null) =
        Interpolator.interpolate(text, args, count)

    @Test
    fun namedPlaceholdersAreFilled() {
        assertEquals("Hello, Ann!", fill("Hello, {{name}}!", mapOf("name" to "Ann")))
        assertEquals("Ann and Bob", fill("{{a}} and {{b}}", mapOf("a" to "Ann", "b" to "Bob")))
        assertEquals("Ann, meet Ann", fill("{{name}}, meet {{name}}", mapOf("name" to "Ann")))
        assertEquals("7 of 9.5 (true)", fill("{{a}} of {{b}} ({{c}})", mapOf("a" to 7, "b" to 9.5, "c" to true)))
    }

    @Test
    fun spacesInsideTheBracesAreAllowed() {
        assertEquals("Hello, Ann!", fill("Hello, {{ name }}!", mapOf("name" to "Ann")))
        assertEquals("Hello, Ann!", fill("Hello, {{  name\t}}!", mapOf("name" to "Ann")))
        assertEquals("Ann", fill("{{first name}}", mapOf("first name" to "Ann")))
    }

    @Test
    fun countComesFromThePluralCount() {
        assertEquals("3 items", fill("{{count}} items", count = 3))
        assertEquals("-2 items", fill("{{ count }} items", count = -2))
        // The number that chose the plural form is the number shown, as on iOS.
        assertEquals("3 items", fill("{{count}} items", mapOf("count" to 1), count = 3))
        // Without a count, a value named "count" is a value like any other.
        assertEquals("about 3", fill("{{count}}", mapOf("count" to "about 3")))
        assertEquals("{{count}} items", fill("{{count}} items"))
    }

    @Test
    fun aFormatNameIsIgnored() {
        assertEquals("Total: 12.5", fill("Total: {{price, currency}}", mapOf("price" to 12.5)))
        assertEquals("On 2026-01-02", fill("On {{date, datetime(dateStyle: long)}}", mapOf("date" to "2026-01-02")))
        assertEquals("3 files", fill("{{count, number}} files", count = 3))
    }

    @Test
    fun theUnescapedFormIsAPlainPlaceholder() {
        assertEquals("<b>Ann</b>", fill("{{- name}}", mapOf("name" to "<b>Ann</b>")))
        assertEquals("<b>Ann</b>", fill("{{-name}}", mapOf("name" to "<b>Ann</b>")))
    }

    @Test
    fun anUnknownVariableIsLeftAsWritten() {
        assertEquals("Hello, {{name}}!", fill("Hello, {{name}}!"))
        assertEquals("Hello, {{ name }} and Bob", fill("Hello, {{ name }} and {{other}}", mapOf("other" to "Bob")))
        assertEquals("{{value, format}}", fill("{{value, format}}", mapOf("other" to 1)))
        assertEquals("{{}} {{ }} {{,x}}", fill("{{}} {{ }} {{,x}}", mapOf("" to "never")))
    }

    @Test
    fun aNullValueIsEmpty() {
        assertEquals("Hello, !", fill("Hello, {{name}}!", mapOf("name" to null)))
    }

    @Test
    fun valuesAreInsertedAsTheyAre() {
        // No HTML escaping, and no second pass over an inserted value.
        assertEquals("<a href=\"x\">&</a>", fill("{{html}}", mapOf("html" to "<a href=\"x\">&</a>")))
        assertEquals("{{b}} B", fill("{{a}} {{b}}", mapOf("a" to "{{b}}", "b" to "B")))
        assertEquals("\$1 \\ \$name", fill("{{a}}", mapOf("a" to "\$1 \\ \$name")))
    }

    @Test
    fun nestingIsNotExpanded() {
        assertEquals("See \$t(common.more)", fill("See \$t(common.more)", mapOf("common.more" to "x")))
        assertEquals("\$t(key, {\"count\": 2})", fill("\$t(key, {\"count\": {{count}}})", count = 2))
    }

    @Test
    fun strayBracesAreLeftAlone() {
        assertEquals("{{name", fill("{{name", mapOf("name" to "Ann")))
        assertEquals("name}}", fill("name}}", mapOf("name" to "Ann")))
        assertEquals("{ {name} }", fill("{ {name} }", mapOf("name" to "Ann")))
        assertEquals("{Ann}", fill("{{{name}}}", mapOf("name" to "Ann")))
        assertEquals("{{ {{ Ann", fill("{{ {{ {{name}}", mapOf("name" to "Ann")))
        assertEquals("Ann }} }}", fill("{{name}} }} }}", mapOf("name" to "Ann")))
        assertEquals("", fill(""))
    }

    @Test
    fun aTextWithoutPlaceholdersIsReturnedUnchanged() {
        val text = "Nothing to fill in"
        assertSame(text, fill(text, mapOf("name" to "Ann"), count = 2))
    }
}
