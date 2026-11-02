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
 * Frame (the encrypted part, always 66 bytes):
 *   [type 1][len 1][payload 64, zero padded]
 *   type 1, text:      the payload is up to 64 bytes of UTF-8, a whole message
 *   type 2, fragment:  the payload is [group 2][index 1][count 1][up to 60 bytes of UTF-8]; the pieces of
 *                      one longer message share a group and are joined in index order by [Reassembler]
 *
 * Envelope (what the relay stores, always 135 bytes, 180 base64 characters):
 *   [version 1][ratchet public key 32][previous chain length 4][n 4]   header, 41 bytes
 *   [nonce 12][AES-GCM ciphertext of the frame 66][tag 16]
 */
object Wire {
    const val VERSION: Byte = 1
    const val TYPE_TEXT = 1
    const val TYPE_FRAGMENT = 2

    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    /** What one frame holds. */
    sealed interface Decoded {
        /** A whole message. */
        class Text(val text: String) : Decoded

        /** One piece of a longer message. [data] is raw UTF-8 and may end in the middle of a character. */
        class Fragment(val group: Int, val index: Int, val count: Int, val data: ByteArray) : Decoded
    }

    /**
     * A single text frame carrying [text], or null if it is blank, not displayable, or needs more than
     * 64 bytes (use [encodeMessage] for those).
     */
    fun encodeText(text: String): ByteArray? {
        if (text.isBlank() || !isDisplayable(text)) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Constants.MAX_UTF8_BYTES) return null
        val frame = ByteArray(Constants.FRAME_BYTES)
        frame[0] = TYPE_TEXT.toByte()
        frame[1] = bytes.size.toByte()
        bytes.copyInto(frame, 2)
        return frame
    }

    /**
     * The frames that carry one message: a single text frame if it fits in 64 bytes (identical to
     * [encodeText], so short messages look exactly as they always did), otherwise fragments. [group] ties the
     * fragments together; only its low 16 bits are used. Null if [text] is not one message ([MessageLimits.fits])
     * or is blank or not displayable. The length rule is enforced here, on the way out.
     */
    fun encodeMessage(text: String, group: Int): List<ByteArray>? {
        if (text.isBlank() || !MessageLimits.fits(text) || !isDisplayable(text)) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= Constants.MAX_UTF8_BYTES) return listOf(encodeText(text) ?: return null)
        val count = (bytes.size + Constants.MAX_FRAGMENT_DATA - 1) / Constants.MAX_FRAGMENT_DATA
        if (count > Constants.MAX_FRAGMENTS) return null
        return List(count) { index ->
            val from = index * Constants.MAX_FRAGMENT_DATA
            val to = minOf(bytes.size, from + Constants.MAX_FRAGMENT_DATA)
            val frame = ByteArray(Constants.FRAME_BYTES)
            frame[0] = TYPE_FRAGMENT.toByte()
            frame[1] = (Constants.FRAGMENT_HEADER_BYTES + (to - from)).toByte()
            frame[2] = (group ushr 8).toByte()
            frame[3] = group.toByte()
            frame[4] = index.toByte()
            frame[5] = count.toByte()
            bytes.copyInto(frame, 2 + Constants.FRAGMENT_HEADER_BYTES, from, to)
            frame
        }
    }

    /** How many frames [encodeMessage] makes for [text]: 1 up to 64 bytes, then one per 60 bytes. */
    fun frameCount(text: String): Int {
        val size = text.toByteArray(Charsets.UTF_8).size
        return if (size <= Constants.MAX_UTF8_BYTES) 1 else (size + Constants.MAX_FRAGMENT_DATA - 1) / Constants.MAX_FRAGMENT_DATA
    }

    /**
     * What a frame holds, or null if it is not exactly what [encodeMessage] makes: right type, length and
     * zero padding; for a fragment, a plausible group, index and count, full pieces except the last.
     * Unknown types are ignored (null), so a later version can add more.
     */
    fun decodeFrame(frame: ByteArray): Decoded? {
        if (frame.size != Constants.FRAME_BYTES) return null
        return when (frame[0].toInt()) {
            TYPE_TEXT -> decodeText(frame)?.let { Decoded.Text(it) }
            TYPE_FRAGMENT -> decodeFragment(frame)
            else -> null
        }
    }

    /**
     * The text in a type-1 frame, or null if the frame is not well formed: right length, zero padding,
     * strict UTF-8, not blank, no control or bidirectional-override characters.
     *
     * There is deliberately no character-count check here. The ratchet has already advanced by the
     * time this runs, so a frame refused here is a message lost for good (the relay is told it
     * was handled). Two platforms can count the same Bangla string differently, because their
     * Unicode text-boundary rules differ by version, and a valid message must never vanish over
     * that. The limit is the sender's rule, enforced by [encodeMessage] and the composer.
     */
    fun decodeText(frame: ByteArray): String? {
        if (frame.size != Constants.FRAME_BYTES) return null
        if (frame[0].toInt() != TYPE_TEXT) return null
        val len = frame[1].toInt() and 0xFF
        if (len < 1 || len > Constants.MAX_UTF8_BYTES) return null
        if (!zeroFrom(frame, 2 + len)) return null
        return validText(frame, 2, len)
    }

    private fun decodeFragment(frame: ByteArray): Decoded.Fragment? {
        val len = frame[1].toInt() and 0xFF
        if (len <= Constants.FRAGMENT_HEADER_BYTES || len > Constants.MAX_UTF8_BYTES) return null
        if (!zeroFrom(frame, 2 + len)) return null
        val group = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val index = frame[4].toInt() and 0xFF
        val count = frame[5].toInt() and 0xFF
        if (count < 2 || count > Constants.MAX_FRAGMENTS || index >= count) return null
        val dataLength = len - Constants.FRAGMENT_HEADER_BYTES
        // Canonical form: every piece but the last is full, so the message length follows from count and the last piece.
        if (index < count - 1 && dataLength != Constants.MAX_FRAGMENT_DATA) return null
        return Decoded.Fragment(group, index, count, frame.copyOfRange(2 + Constants.FRAGMENT_HEADER_BYTES, 2 + len))
    }

    private fun zeroFrom(frame: ByteArray, from: Int): Boolean {
        for (i in from until frame.size) if (frame[i].toInt() != 0) return false
        return true
    }

    /** Strict UTF-8, not blank, displayable; otherwise null. Used for whole frames and reassembled messages. */
    internal fun validText(bytes: ByteArray, offset: Int, length: Int): String? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
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