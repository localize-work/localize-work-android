package work.localize.sdk

/** Thrown for text that is not valid JSON, or is nested too deeply to read safely. */
internal class JsonException(message: String) : Exception(message)

/**
 * A small JSON reader and writer, so the library needs nothing beyond the
 * Kotlin standard library and behaves the same on a device and in JVM tests.
 *
 * Objects are read as [LinkedHashMap] (a repeated name keeps its last value),
 * arrays as [ArrayList], whole numbers that fit as [Long], other numbers as
 * [Double].
 */
internal object Json {
    /** Deeper nesting than this is refused instead of risking a stack overflow. */
    const val MAX_DEPTH: Int = 200

    @Throws(JsonException::class)
    fun parse(text: String): Any? {
        val parser = Parser(text)
        return parser.parseDocument()
    }

    fun write(value: Any?): String {
        val out = StringBuilder()
        write(value, out)
        return out.toString()
    }

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(value, out)
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> if (value.isNaN() || value.isInfinite()) out.append("null") else out.append(value.toString())
            is Float -> if (value.isNaN() || value.isInfinite()) out.append("null") else out.append(value.toString())
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((name, member) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(name.toString(), out)
                    out.append(':')
                    write(member, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (element in value) {
                    if (!first) out.append(',')
                    first = false
                    write(element, out)
                }
                out.append(']')
            }
            else -> writeString(value.toString(), out)
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> {
                    out.append("\\u")
                    val hex = Integer.toHexString(char.code)
                    for (i in hex.length until 4) out.append('0')
                    out.append(hex)
                }
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun parseDocument(): Any? {
            // A byte order mark sometimes survives decoding; it is not part of the document.
            if (text.isNotEmpty() && text[0] == '﻿') position = 1
            skipWhitespace()
            val value = parseValue(0)
            skipWhitespace()
            if (position != text.length) throw error("unexpected text after the JSON value")
            return value
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw error("nested too deeply")
            if (position >= text.length) throw error("unexpected end of text")
            return when (val char = text[position]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char in '0'..'9') parseNumber() else throw error("unexpected character")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++ // {
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw error("expected a property name")
                val name = parseString()
                skipWhitespace()
                if (peek() != ':') throw error("expected ':'")
                position++
                skipWhitespace()
                result[name] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            val result = ArrayList<Any?>()
            position++ // [
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                result.add(parseValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            position++ // opening quote
            var out: StringBuilder? = null
            var runStart = position
            while (true) {
                if (position >= text.length) throw error("unterminated string")
                val char = text[position]
                if (char == '"') {
                    val result = if (out == null) {
                        text.substring(runStart, position)
                    } else {
                        out.append(text, runStart, position).toString()
                    }
                    position++
                    return result
                }
                if (char != '\\') {
                    position++
                    continue
                }
                val builder = out ?: StringBuilder().also { out = it }
                builder.append(text, runStart, position)
                position++
                if (position >= text.length) throw error("unterminated escape")
                when (text[position]) {
                    '"' -> builder.append('"')
                    '\\' -> builder.append('\\')
                    '/' -> builder.append('/')
                    'b' -> builder.append('\b')
                    'f' -> builder.append('\u000C')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'u' -> {
                        if (position + 4 >= text.length) throw error("incomplete \\u escape")
                        var code = 0
                        for (i in 1..4) {
                            val digit = Character.digit(text[position + i], 16)
                            if (digit < 0) throw error("invalid \\u escape")
                            code = code * 16 + digit
                        }
                        builder.append(code.toChar())
                        position += 4
                    }
                    else -> throw error("invalid escape")
                }
                position++
                runStart = position
            }
        }

        private fun parseNumber(): Number {
            val start = position
            if (peek() == '-') position++
            val digitsStart = position
            while (position < text.length && text[position] in '0'..'9') position++
            if (position == digitsStart) throw error("invalid number")
            var integral = true
            if (peek() == '.') {
                integral = false
                position++
                val fractionStart = position
                while (position < text.length && text[position] in '0'..'9') position++
                if (position == fractionStart) throw error("invalid number")
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false
                position++
                if (peek() == '+' || peek() == '-') position++
                val exponentStart = position
                while (position < text.length && text[position] in '0'..'9') position++
                if (position == exponentStart) throw error("invalid number")
            }
            val token = text.substring(start, position)
            if (integral) token.toLongOrNull()?.let { return it }
            return token.toDoubleOrNull() ?: throw error("invalid number")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) throw error("unexpected character")
            position += word.length
            return value
        }

        private fun peek(): Char = if (position < text.length) text[position] else '\u0000'

        private fun skipWhitespace() {
            while (position < text.length) {
                val char = text[position]
                if (char == ' ' || char == '\n' || char == '\r' || char == '\t') position++ else return
            }
        }

        private fun error(message: String) = JsonException("$message at character $position")
    }
}
