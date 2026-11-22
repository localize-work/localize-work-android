package work.localize.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class JsonTest {
    @Test
    fun readsEveryKindOfValue() {
        val value = Json.parse(
            """ { "text": "a", "empty": "", "int": 12, "negative": -3, "decimal": 1.5, "exp": 2e3,
                  "big": 123456789012345678901234567890, "yes": true, "no": false, "nothing": null,
                  "list": [1, "two", [3], {}], "object": { "nested": { "deep": "x" } } } """,
        ) as Map<*, *>

        assertEquals("a", value["text"])
        assertEquals("", value["empty"])
        assertEquals(12L, value["int"])
        assertEquals(-3L, value["negative"])
        assertEquals(1.5, value["decimal"])
        assertEquals(2000.0, value["exp"])
        assertEquals(1.2345678901234568E29, value["big"])
        assertEquals(true, value["yes"])
        assertEquals(false, value["no"])
        assertNull(value["nothing"])
        assertEquals(listOf(1L, "two", listOf(3L), emptyMap<String, Any?>()), value["list"])
        assertEquals(mapOf("nested" to mapOf("deep" to "x")), value["object"])
        assertEquals(
            listOf("text", "empty", "int", "negative", "decimal", "exp", "big", "yes", "no", "nothing", "list", "object"),
            value.keys.toList(),
        )
    }

    @Test
    fun readsStringEscapes() {
        assertEquals("\"\\/\b\u000C\n\r\t", Json.parse(""""\"\\\/\b\f\n\r\t""""))
        assertEquals("é€😀", Json.parse(""""é€😀""""))
        assertEquals("é€😀 raw", Json.parse("\"é€😀 raw\""))
        assertEquals("a.b", (Json.parse("""{ "a.b": "a.b" }""") as Map<*, *>).keys.single())
    }

    @Test
    fun aRepeatedNameKeepsItsLastValue() {
        assertEquals(mapOf("a" to "second"), Json.parse("""{ "a": "first", "a": "second" }"""))
    }

    @Test
    fun aByteOrderMarkAndSurroundingWhitespaceAreIgnored() {
        assertEquals(mapOf("a" to 1L), Json.parse("﻿ \n\t{ \"a\" : 1 }\r\n"))
    }

    @Test
    fun malformedTextIsRefusedWithAJsonException() {
        val malformed = listOf(
            "", " ", "{", "}", "[", "]", "{]", "[}", "{\"a\"}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[,1]", "{,}",
            "{a:1}", "{'a':1}", "\"unterminated", "\"bad \\x escape\"", "\"bad \\u12 escape\"", "\"\\u12", "\"\\",
            "tru", "nul", "falsy", "True", "01x", "-", "1.", "1e", "1e+", ".5", "+1", "0x10", "NaN", "Infinity",
            "{} {}", "[] x", "1 2", "{\"a\":1}}", "\u0000", "<html>", "undefined",
        )
        for (text in malformed) {
            try {
                Json.parse(text)
                fail("\"$text\" was accepted")
            } catch (expected: JsonException) {
                // Any other exception fails the test.
            }
        }
    }

    @Test
    fun nestingDeeperThanTheLimitIsRefusedInsteadOfOverflowingTheStack() {
        val withinLimit = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH)
        Json.parse(withinLimit)

        for (text in listOf("[".repeat(500_000), "{\"a\":".repeat(500_000), "[".repeat(300) + "]".repeat(300))) {
            try {
                Json.parse(text)
                fail("deep nesting was accepted")
            } catch (expected: JsonException) {
                // Refused, as it should be.
            }
        }
    }

    @Test
    fun writesWhatItCanReadBack() {
        val value = linkedMapOf(
            "text" to "quote \" backslash \\ newline \n tab \t control \u0001 unicode é😀 separators   ",
            "empty" to "",
            "int" to 12L,
            "decimal" to 1.5,
            "yes" to true,
            "nothing" to null,
            "list" to listOf("a", 2L, null),
            "object" to linkedMapOf("a.b" to linkedMapOf("" to "empty name")),
        )

        val written = Json.write(value)

        assertEquals(value, Json.parse(written))
        assertEquals(
            """{"a":"\"\\\n\r\t\u0001\u001f","b":null,"c":[true,false],"d":{}}""",
            Json.write(linkedMapOf("a" to "\"\\\n\r\t\u0001\u001F", "b" to Double.NaN, "c" to listOf(true, false), "d" to emptyMap<String, Any?>())),
        )
    }
}
