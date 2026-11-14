package com.rhshourav.tenchar

/** The only place protocol and policy numbers are defined. */
object Constants {
    /** Maximum length of one message in user-perceived characters (grapheme clusters). Longer text is split. */
    const val MAX_CHARS = 500

    /**
     * Maximum length of one message in UTF-8 bytes. 500 characters of 4-byte emoji are 2,000 bytes, so this
     * leaves room for them; it also bounds a single character with many combining marks.
     */
    const val MAX_MESSAGE_BYTES = 2048

    /** The most text a person can have in the composer at once: it is split into up to ten messages on send. */
    const val MAX_DRAFT_CHARS = 5000
    const val MAX_DRAFT_BYTES = 20_000

    /**
     * Payload bytes in one frame. The name is kept because the relay's contract test reads it: a frame is
     * [type 1][len 1][payload MAX_UTF8_BYTES], zero padded, and one frame is one fixed-size envelope.
     */
    const val MAX_UTF8_BYTES = 64

    /** Fixed frame size: [type 1][len 1][payload MAX_UTF8_BYTES], zero padded. */
    const val FRAME_BYTES = 2 + MAX_UTF8_BYTES

    /**
     * A message of up to MAX_UTF8_BYTES bytes is one text frame (type 1). A longer one is sent as fragments
     * (type 2): the payload starts with [group 2][index 1][count 1], leaving this many bytes of text per frame.
     */
    const val FRAGMENT_HEADER_BYTES = 4
    const val MAX_FRAGMENT_DATA = MAX_UTF8_BYTES - FRAGMENT_HEADER_BYTES

    /** ceil(MAX_MESSAGE_BYTES / MAX_FRAGMENT_DATA): the most frames one message can need. */
    const val MAX_FRAGMENTS = (MAX_MESSAGE_BYTES + MAX_FRAGMENT_DATA - 1) / MAX_FRAGMENT_DATA

    /** A profile (name, avatar emoji, colour) travels in one frame: [color 1][avatarLen 1][avatar][name]. */
    const val AVATAR_COLORS = 8
    const val PROFILE_NAME_BYTES = 32
    const val PROFILE_AVATAR_BYTES = 28

    /**
     * A reply carries a 4-byte reference to the message it answers: [who 1][hash 3]. That leaves 60 bytes of text in a
     * one-frame reply, and 56 per piece when a longer reply is fragmented (a piece also repeats the reference, so
     * losing the first one does not lose it). The longest reply is therefore MAX_FRAGMENTS pieces of 56 bytes.
     */
    const val REPLY_HASH_BYTES = 3
    const val REPLY_TEXT_BYTES = MAX_UTF8_BYTES - 4
    const val REPLY_FRAGMENT_DATA = MAX_UTF8_BYTES - 8
    const val MAX_REPLY_MESSAGE_BYTES = MAX_FRAGMENTS * REPLY_FRAGMENT_DATA

    /** Partly received messages kept per conversation. The oldest is dropped beyond this. */
    const val MAX_PENDING_MESSAGES = 16

    /** Envelope: [version 1][ratchet pub 32][prevChainLen 4][n 4] + nonce 12 + frame 66 + tag 16 = 135. */
    const val HEADER_BYTES = 41
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val ENVELOPE_BYTES = HEADER_BYTES + NONCE_BYTES + FRAME_BYTES + TAG_BYTES

    /** Base64 characters of one envelope, no padding. The relay checks this exact length. */
    const val ENVELOPE_B64_CHARS = ENVELOPE_BYTES / 3 * 4

    /** Pairing code length. 32 symbols x 16 = 80 bits. */
    const val CODE_LEN = 16

    /** Pairing code alphabet. 32 symbols, so each symbol is exactly 5 bits. */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    const val PAIRING_WINDOW_HOURS = 24
    const val RETENTION_DAYS = 7

    /** Maximum skipped message keys kept by the ratchet. */
    const val MAX_SKIP = 200

    /** Relay request body cap in bytes (everything except an attachment upload). */
    const val MAX_BODY_BYTES = 512

    // ---- Attachments ---------------------------------------------------------------------------------------------

    /** One uploaded blob, ciphertext included: 9 MiB. The relay refuses more. Must equal MAX_BLOB_BYTES in relay/src/constants.js. */
    const val MAX_BLOB_BYTES = 9_437_184

    /** The most plaintext one attachment may hold: 8 MiB. Padding and tags still fit under MAX_BLOB_BYTES. */
    const val MAX_MEDIA_BYTES = 8_388_608

    /** Attachment encryption works in chunks of this many plaintext bytes (each gets its own AES-GCM tag). */
    const val BLOB_CHUNK_BYTES = 65_536

    /** Plaintext is padded with zeros to a multiple of this, so the relay sees sizes in 16 KiB steps, not exact ones. */
    const val BLOB_PAD_BYTES = 16_384

    /** Attachment kinds, as carried in the encrypted descriptor. */
    const val KIND_IMAGE = 1
    const val KIND_VOICE = 2
    const val KIND_VIDEO = 3

    /** Photos: longest side, and JPEG quality from 75 down to 70, then smaller, until the file is under the target. */
    const val IMAGE_MAX_EDGE = 1280
    const val IMAGE_QUALITY_START = 75
    const val IMAGE_QUALITY_MIN = 70
    const val IMAGE_TARGET_BYTES = 400_000

    /** Voice messages: AAC, mono, 16 kHz, 24 kbit/s is about 180 KB a minute. */
    const val VOICE_MAX_MS = 120_000
    const val VOICE_MIN_MS = 800
    const val VOICE_BITRATE = 24_000
    const val VOICE_SAMPLE_RATE = 16_000

    /** The waveform in a voice descriptor: 40 bars of 4 bits. */
    const val WAVE_BARS = 40
    const val WAVE_BYTES = WAVE_BARS / 2

    /** Short video: up to 30 seconds, shortest side 480 px, about 0.8 Mbit/s video. */
    const val VIDEO_MAX_MS = 30_000
    const val VIDEO_SHORT_SIDE = 480
    const val VIDEO_BITRATE = 800_000

    // ---- Alerts --------------------------------------------------------------------------------------------------

    /** How often the background service asks the relays whether a message is waiting. */
    const val ALERT_INTERVAL_SHORT_S = 30
    const val ALERT_INTERVAL_MEDIUM_S = 60
    const val ALERT_INTERVAL_LONG_S = 300

    /** The relay used until the person picks another one in Settings. */
    const val DEFAULT_RELAY_URL = "https://tenchar-relay.onrender.com"

    /** The optional backup relay. Used when the main relay cannot be reached. */
    const val FALLBACK_RELAY_URL = "https://tenchar-relay.tenchar.workers.dev"

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

    /** Third derivation. It never leaves the phone: it keys the handshake, so the relay cannot join in. */
    const val SALT_KEY = "tenchar-key-v1"

    /** Hex characters kept from each PBKDF2 output. */
    const val ROOM_HEX_CHARS = 32
    const val CODE_TOKEN_HEX_CHARS = 48

    val USER_AGENT: String = "TenChar-android/${BuildConfig.VERSION_NAME}"
}