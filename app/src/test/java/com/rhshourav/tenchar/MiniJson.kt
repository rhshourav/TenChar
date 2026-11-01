package com.rhshourav.tenchar

/**
 * A strict little JSON reader for the vector file: objects, arrays, strings (with \uXXXX escapes),
 * and integers. The Android org.json stub returns empty values in unit tests, and the project
 * does not add a JSON dependency, so tests read their data with this.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val value = p.value()
        p.skipSpace()
        require(p.done()) { "trailing data at ${p.pos}" }
        return value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun done() = pos >= s.length

        fun skipSpace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipSpace()
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $pos")
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipSpace()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipSpace()
                val k = str()
                skipSpace()
                require(s[pos++] == ':') { "expected ':' at ${pos - 1}" }
                out[k] = value()
                skipSpace()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++
            skipSpace()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipSpace()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun str(): String {
            require(s[pos++] == '"') { "expected string at ${pos - 1}" }
            val sb = StringBuilder()
            while (true) {
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                        else -> error("bad escape \\$e at ${pos - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Long {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            return s.substring(start, pos).toLong()
        }
    }
}