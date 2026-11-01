package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.MessageLimits
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The two fixed-size layouts that cross the wire.
 *
 * Frame (the encrypted part, always 66 bytes so length leaks nothing):
 *   [type 1][len 1][payload 64, zero padded]
 *
 * Envelope (what the relay stores, always 135 bytes, 180 base64 characters):
 *   [version 1][ratchet public key 32][previous chain length 4][n 4]   header, 41 bytes
 *   [nonce 12][AES-GCM ciphertext of the frame 66][tag 16]
 */
object Wire {
    const val VERSION: Byte = 1
    const val TYPE_TEXT = 1

    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    /**
     * A frame carrying [text], or null if it is empty or over the limits (10 user-perceived
     * characters, 64 bytes). This is where the length rule is enforced: on the way out.
     */
    fun encodeText(text: String): ByteArray? {
        if (text.isBlank() || !MessageLimits.fits(text) || !isDisplayable(text)) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(Constants.FRAME_BYTES)
        frame[0] = TYPE_TEXT.toByte()
        frame[1] = bytes.size.toByte()
        bytes.copyInto(frame, 2)
        return frame
    }

    /**
     * The text in a frame, or null if the frame is not well formed: right type and length, zero
     * padding, strict UTF-8, not blank, no control or bidirectional-override characters.
     *
     * There is deliberately no 10-character check here. The ratchet has already advanced by the
     * time this runs, so a frame refused here is a message lost for good (the relay is told it
     * was handled). Two platforms can count the same Bangla string differently, because their
     * Unicode text-boundary rules differ by version, and a valid message must never vanish over
     * that. The limit is the sender's rule, enforced by [encodeText] and the composer; the 64-byte
     * frame already bounds how much text can arrive.
     */
    fun decodeText(frame: ByteArray): String? {
        if (frame.size != Constants.FRAME_BYTES) return null
        if (frame[0].toInt() != TYPE_TEXT) return null
        val len = frame[1].toInt() and 0xFF
        if (len < 1 || len > Constants.MAX_UTF8_BYTES) return null
        for (i in 2 + len until frame.size) if (frame[i].toInt() != 0) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(frame, 2, len))
                .toString()
        } catch (e: CharacterCodingException) {
            return null
        }
        if (text.isBlank() || !isDisplayable(text)) return null
        return text
    }

    /** No control characters and no bidirectional overrides, which can make text lie about its order. */
    private fun isDisplayable(text: String): Boolean = text.none { c ->
        Character.isISOControl(c) || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F'
    }

    fun toBase64(envelope: ByteArray): String {
        require(envelope.size == Constants.ENVELOPE_BYTES) { "bad envelope size" }
        return encoder.encodeToString(envelope)
    }

    fun fromBase64(text: String): ByteArray? {
        if (text.length != Constants.ENVELOPE_B64_CHARS) return null
        return try {
            decoder.decode(text).takeIf { it.size == Constants.ENVELOPE_BYTES }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}