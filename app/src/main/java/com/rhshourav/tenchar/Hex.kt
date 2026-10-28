package com.rhshourav.tenchar

object Hex {
    private const val DIGITS = "0123456789abcdef"

    /** Lowercase hex. java.util.HexFormat is not available on API 30. */
    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return out.concatToString()
    }

    /** Strict lowercase or uppercase hex. Throws IllegalArgumentException on odd length or a bad digit. */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = digit(hex[i * 2])
            val lo = digit(hex[i * 2 + 1])
            require(hi >= 0 && lo >= 0) { "bad hex digit" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** ASCII only. Character.digit would also accept look-alike digits from other scripts. */
    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
