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

    /** PBKDF2 output length in bytes, before truncation to hex prefixes. */
    const val PBKDF2_DK_BYTES = 32

    /** Auto-lock delays. The default is AUTO_LOCK_SHORT_MS. */
    const val AUTO_LOCK_SHORT_MS = 30_000L
    const val AUTO_LOCK_LONG_MS = 300_000L

    /** Characters per group when a code is displayed: XXXX-XXXX-XXXX-XXXX. */
    const val CODE_GROUP_LEN = 4

    /** PBKDF2 salts. Distinct, so the room id and the auth token are unrelated. */
    const val SALT_ROOM = "tenchar-room-v1"
    const val SALT_AUTH = "tenchar-auth-v1"

    /** Hex characters kept from each PBKDF2 output. */
    const val ROOM_HEX_CHARS = 32
    const val CODE_TOKEN_HEX_CHARS = 48

    val USER_AGENT: String = "TenChar-android/${BuildConfig.VERSION_NAME}"
}
