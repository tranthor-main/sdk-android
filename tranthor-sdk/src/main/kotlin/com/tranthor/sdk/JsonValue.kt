package com.tranthor.sdk

/**
 * An immutable JSON value. Numbers are IEEE 754 doubles: integers beyond ±(2^53−1) may lose
 * precision, so send large identifiers as strings. Non-finite numbers can be constructed but are
 * rejected before an event is admitted.
 */
public sealed class JsonValue {
    public data class ObjectValue(val value: Map<String, JsonValue>) : JsonValue()
    public data class ArrayValue(val value: List<JsonValue>) : JsonValue()
    public data class StringValue(val value: String) : JsonValue()
    public data class NumberValue(val value: Double) : JsonValue()
    public data class BooleanValue(val value: Boolean) : JsonValue()
    public object Null : JsonValue() {
        override fun toString(): String = "null"
    }

    public val objectValue: Map<String, JsonValue>? get() = (this as? ObjectValue)?.value
    public val arrayValue: List<JsonValue>? get() = (this as? ArrayValue)?.value
    public val stringValue: String? get() = (this as? StringValue)?.value
    public val numberValue: Double? get() = (this as? NumberValue)?.value
    public val booleanValue: Boolean? get() = (this as? BooleanValue)?.value
    public operator fun get(key: String): JsonValue? = objectValue?.get(key)

    /** Compact JSON text. Throws [IllegalArgumentException] for non-finite numbers. */
    public fun toJson(): String = StringBuilder().also { JsonCodec.encode(this, it) }.toString()

    public companion object {
        /**
         * Converts Kotlin/Java values: [JsonValue], null, String, Boolean, Number, Map with String
         * keys, Iterable and arrays. Throws [IllegalArgumentException] for anything else.
         */
        @JvmStatic
        public fun of(value: Any?): JsonValue = when (value) {
            null -> Null
            is JsonValue -> value
            is String -> StringValue(value)
            is Boolean -> BooleanValue(value)
            is Number -> NumberValue(value.toDouble())
            is Char -> StringValue(value.toString())
            is Map<*, *> -> ObjectValue(LinkedHashMap<String, JsonValue>(value.size).also { map ->
                for ((key, child) in value) {
                    require(key is String) { "JSON object keys must be strings" }
                    map[key] = of(child)
                }
            })
            is Iterable<*> -> ArrayValue(value.map { of(it) })
            is Array<*> -> ArrayValue(value.map { of(it) })
            is IntArray -> ArrayValue(value.map { NumberValue(it.toDouble()) })
            is LongArray -> ArrayValue(value.map { NumberValue(it.toDouble()) })
            is DoubleArray -> ArrayValue(value.map { NumberValue(it) })
            is BooleanArray -> ArrayValue(value.map { BooleanValue(it) })
            else -> throw IllegalArgumentException("Unsupported JSON value type ${value.javaClass.name}")
        }

        /** Converts a String-keyed map into a JSON object map, see [of]. */
        @JvmStatic
        public fun objectOf(map: Map<String, *>): Map<String, JsonValue> =
            (of(map) as ObjectValue).value

        /** Parses JSON text. Throws [IllegalArgumentException] on malformed input. */
        @JvmStatic
        public fun parse(text: String): JsonValue = JsonCodec.decode(text)
    }
}

/** Builds a JSON object map from pairs, converting each value with [JsonValue.of]. */
public fun jsonObjectOf(vararg pairs: Pair<String, Any?>): Map<String, JsonValue> =
    LinkedHashMap<String, JsonValue>(pairs.size).also { map -> for ((key, value) in pairs) map[key] = JsonValue.of(value) }

/** Builds a JSON array from values, converting each with [JsonValue.of]. */
public fun jsonArrayOf(vararg values: Any?): List<JsonValue> = values.map { JsonValue.of(it) }

/** A small dependency-free JSON codec; org.json is a stub on the JVM unit-test host. */
internal object JsonCodec {
    /**
     * Read limit for any JSON the SDK parses (state file, push markers). It must stay strictly
     * greater than [MAX_ADMISSION_DEPTH] + 2 (state object + queue array) so that every admitted
     * event can always be re-read after a restart.
     */
    const val MAX_DEPTH = 128

    /** Deepest nesting admitted in one event (the event object itself counts as 1). */
    const val MAX_ADMISSION_DEPTH = 64

    /** True when [value] nests deeper than [limit] containers (iterative; safe on small stacks). */
    fun exceedsDepth(value: JsonValue, limit: Int): Boolean {
        val stack = ArrayDeque<Pair<JsonValue, Int>>()
        stack.addLast(value to 1)
        while (stack.isNotEmpty()) {
            val (current, depth) = stack.removeLast()
            val children: Collection<JsonValue> = when (current) {
                is JsonValue.ObjectValue -> current.value.values
                is JsonValue.ArrayValue -> current.value
                else -> continue
            }
            if (depth > limit) return true
            for (child in children) if (child is JsonValue.ObjectValue || child is JsonValue.ArrayValue) stack.addLast(child to depth + 1)
        }
        return false
    }

    fun encode(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.ObjectValue -> {
                out.append('{')
                var first = true
                for ((key, child) in value.value) {
                    if (!first) out.append(',')
                    first = false
                    encodeString(key, out)
                    out.append(':')
                    encode(child, out)
                }
                out.append('}')
            }
            is JsonValue.ArrayValue -> {
                out.append('[')
                value.value.forEachIndexed { index, child ->
                    if (index > 0) out.append(',')
                    encode(child, out)
                }
                out.append(']')
            }
            is JsonValue.StringValue -> encodeString(value.value, out)
            is JsonValue.NumberValue -> out.append(formatNumber(value.value))
            is JsonValue.BooleanValue -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    fun encodeObject(map: Map<String, JsonValue>): String =
        StringBuilder().also { encode(JsonValue.ObjectValue(map), it) }.toString()

    fun formatNumber(number: Double): String {
        require(number.isFinite()) { "Non-finite JSON number" }
        if (number == Math.rint(number) && Math.abs(number) < 1e15) {
            val integral = number.toLong()
            return if (integral == 0L && 1.0 / number < 0) "-0" else integral.toString()
        }
        return number.toString()
    }

    private fun encodeString(text: String, out: StringBuilder) {
        out.append('"')
        for (char in text) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                else -> if (char < ' ') {
                    out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", char.code))
                } else {
                    out.append(char)
                }
            }
        }
        out.append('"')
    }

    fun decode(text: String): JsonValue = try {
        decodeChecked(text)
    } catch (_: StackOverflowError) {
        // Small-stack threads (exported entry points) must get a clean rejection, never a crash.
        throw IllegalArgumentException("JSON nesting too deep for this thread")
    }

    private fun decodeChecked(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue(0)
        parser.skipWhitespace()
        require(parser.atEnd()) { "Trailing JSON content" }
        return value
    }

    private class Parser(private val text: String) {
        private var index = 0
        fun atEnd() = index >= text.length
        fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) index++
        }
        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at offset $index")
        private fun peek(): Char = if (index < text.length) text[index] else fail("Unexpected end of JSON")

        fun readValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("JSON nesting too deep")
            return when (peek()) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> JsonValue.StringValue(readString())
                't' -> literal("true", JsonValue.BooleanValue(true))
                'f' -> literal("false", JsonValue.BooleanValue(false))
                'n' -> literal("null", JsonValue.Null)
                else -> readNumber()
            }
        }
        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, index)) fail("Invalid literal")
            index += word.length
            return value
        }
        private fun readObject(depth: Int): JsonValue {
            index++
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { index++; return JsonValue.ObjectValue(map) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected object key")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("Expected ':'")
                index++
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return JsonValue.ObjectValue(map) }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }
        private fun readArray(depth: Int): JsonValue {
            index++
            val list = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { index++; return JsonValue.ArrayValue(list) }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return JsonValue.ArrayValue(list) }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }
        private fun readString(): String {
            index++ // opening quote
            val out = StringBuilder()
            while (true) {
                val char = peek()
                index++
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> {
                        when (val escape = peek().also { index++ }) {
                            '"' -> out.append('"'); '\\' -> out.append('\\'); '/' -> out.append('/')
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("Short unicode escape")
                                val code = text.substring(index, index + 4).toIntOrNull(16) ?: fail("Invalid unicode escape")
                                index += 4
                                out.append(code.toChar())
                            }
                            else -> fail("Invalid escape '$escape'")
                        }
                    }
                    char < ' ' -> fail("Unescaped control character")
                    else -> out.append(char)
                }
            }
        }
        private fun readNumber(): JsonValue {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length) fail("Invalid number")
            if (text[index] == '0') index++
            else if (text[index] in '1'..'9') while (index < text.length && text[index].isAsciiDigit()) index++
            else fail("Invalid value")
            if (index < text.length && text[index] == '.') {
                index++
                if (index >= text.length || !text[index].isAsciiDigit()) fail("Invalid fraction")
                while (index < text.length && text[index].isAsciiDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                if (index >= text.length || !text[index].isAsciiDigit()) fail("Invalid exponent")
                while (index < text.length && text[index].isAsciiDigit()) index++
            }
            val number = text.substring(start, index).toDouble()
            if (!number.isFinite()) fail("Number out of range")
            return JsonValue.NumberValue(number)
        }
        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
