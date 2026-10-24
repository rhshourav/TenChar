package com.rhshourav.tenchar

/** The only place protocol and policy numbers are defined. */
object Constants {
    /** Maximum message length in user-perceived characters (grapheme clusters). */
    const val MAX_CHARS = 10

    /** Maximum message length in UTF-8 bytes. */
    const val MAX_UTF8_BYTES = 64

    /** Fixed frame size: [type 1][len 1][payload MAX_UTF8_BYTES], zero padded. */
    const val FRAME_BYTES = 2 + MAX_UTF8_BYTES

    /** Pairing code length. 32 symbols x 16 = 80 bits. */
    const val CODE_LEN = 16

    /** Pairing code alphabet. 32 symbols, so each symbol is exactly 5 bits. */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    const val PAIRING_WINDOW_HOURS = 24
    const val RETENTION_DAYS = 7

    /** Maximum skipped message keys kept by the ratchet. */
    const val MAX_SKIP = 200

    /** Relay request body cap in bytes. */
    const val MAX_BODY_BYTES = 512

    const val PBKDF2_ITERATIONS = 600_000

    val USER_AGENT: String = "TenChar-android/${BuildConfig.VERSION_NAME}"
}
