package org.futo.inputmethod.latin.tapswipe

/**
 * Builds one JSON object as a single line of text.
 *
 * Hand-written on purpose. `org.json` is a stub in JVM unit tests, and the recorder calls this from
 * the touch path, where a small builder with no reflection is the cheapest option.
 */
class TypingTestJson {
    private val sb = StringBuilder(128).append('{')
    private var first = true

    private fun key(name: String): StringBuilder {
        if (!first) sb.append(',')
        first = false
        quote(sb, name)
        return sb.append(':')
    }

    fun put(name: String, value: String?): TypingTestJson {
        val b = key(name)
        if (value == null) b.append("null") else quote(b, value)
        return this
    }

    fun put(name: String, value: Int): TypingTestJson { key(name).append(value); return this }
    fun put(name: String, value: Long): TypingTestJson { key(name).append(value); return this }
    fun put(name: String, value: Boolean): TypingTestJson { key(name).append(value); return this }

    /** Writes a float with at most two decimals. Touch positions need no more. */
    fun put(name: String, value: Float): TypingTestJson {
        key(name).append(round2(value))
        return this
    }

    fun putInts(name: String, values: IntArray, count: Int = values.size): TypingTestJson {
        val b = key(name).append('[')
        for (i in 0 until count) {
            if (i > 0) b.append(',')
            b.append(values[i])
        }
        b.append(']')
        return this
    }

    fun putFloats(name: String, values: FloatArray, count: Int = values.size): TypingTestJson {
        val b = key(name).append('[')
        for (i in 0 until count) {
            if (i > 0) b.append(',')
            b.append(round2(values[i]))
        }
        b.append(']')
        return this
    }

    fun putLongs(name: String, values: LongArray, count: Int = values.size): TypingTestJson {
        val b = key(name).append('[')
        for (i in 0 until count) {
            if (i > 0) b.append(',')
            b.append(values[i])
        }
        b.append(']')
        return this
    }

    fun putStrings(name: String, values: List<String>): TypingTestJson {
        val b = key(name).append('[')
        values.forEachIndexed { i, v ->
            if (i > 0) b.append(',')
            quote(b, v)
        }
        b.append(']')
        return this
    }

    /** Inserts already-built JSON (an object or an array) as the value. */
    fun putRaw(name: String, json: String): TypingTestJson {
        key(name).append(json)
        return this
    }

    fun build(): String = sb.toString() + "}"

    companion object {
        fun quote(b: StringBuilder, s: String) {
            b.append('"')
            for (c in s) {
                when (c) {
                    '"' -> b.append("\\\"")
                    '\\' -> b.append("\\\\")
                    '\n' -> b.append("\\n")
                    '\r' -> b.append("\\r")
                    '\t' -> b.append("\\t")
                    else -> if (c < ' ') {
                        b.append("\\u").append(String.format("%04x", c.code))
                    } else b.append(c)
                }
            }
            b.append('"')
        }

        private fun round2(v: Float): String {
            if (v.isNaN() || v.isInfinite()) return "null"
            val r = Math.round(v * 100.0) / 100.0
            return if (r == Math.floor(r) && Math.abs(r) < 1e15) r.toLong().toString() else r.toString()
        }
    }
}
