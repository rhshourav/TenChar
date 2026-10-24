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
}
