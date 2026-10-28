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

    /** A frame carrying [text], or null if it does not fit the limits or is empty. */
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
     * The text in a frame, or null if the frame is not exactly what [encodeText]
     * makes. Strict on purpose: the peer is trusted to be honest, but a bug or a
     * hostile peer must not be able to put odd content on this screen.
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
        if (text.isBlank() || !MessageLimits.fits(text) || !isDisplayable(text)) return null
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
