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
 *   type 3, profile:   the payload is [color 1][avatar length 1][avatar, one emoji][name]; how the sender
 *                      appears to the other person. Not a message: it is never shown in the chat
 *   type 5, reply text: the payload is [who 1][hash 3][up to 60 bytes of UTF-8]: a whole message that answers an earlier
 *                      one. [who] is 0 if the answered message was written by the sender of this reply and 1 if by its
 *                      receiver. [hash] is the first 3 bytes of a hash of that message (see [quoteHash]); the receiver finds
 *                      the message in its own copy of the chat, so no quoted text is ever sent and none can be invented
 *   type 6, reply fragment: [who 1][hash 3][group 2][index 1][count 1][up to 56 bytes of UTF-8]; the reference is repeated
 *                      in every piece, so a missing first piece costs the reply its quote, not its text
 *   type 4, attachment: the payload is [kind 1][key 32][size 4][width 2][height 2][duration 2][waveform 0 or 20];
 *                      a photo (1), voice message (2) or short video (3) waiting on the relay, encrypted with that
 *                      key (see [BlobCrypto]). Sizes are big endian, duration is in tenths of a second, the
 *                      waveform is 40 bars of 4 bits. Always one frame, so there is nothing to reassemble
 *
 * Envelope (what the relay stores, always 135 bytes, 180 base64 characters):
 *   [version 1][ratchet public key 32][previous chain length 4][n 4]   header, 41 bytes
 *   [nonce 12][AES-GCM ciphertext of the frame 66][tag 16]
 */
object Wire {
    const val VERSION: Byte = 1
    const val TYPE_TEXT = 1
    const val TYPE_FRAGMENT = 2
    const val TYPE_PROFILE = 3
    const val TYPE_ATTACHMENT = 4
    const val TYPE_REPLY_TEXT = 5
    const val TYPE_REPLY_FRAGMENT = 6
    private const val ATTACHMENT_FIXED = 43

    private val encoder = Base64.getEncoder().withoutPadding()
    private val decoder = Base64.getDecoder()

    /** What one frame holds. */
    sealed interface Decoded {
        /** A whole message. [reply] is what it answers, if it is a reply. */
        class Text(val text: String, val reply: Quote? = null) : Decoded

        /** One piece of a longer message. [data] is raw UTF-8 and may end in the middle of a character. */
        class Fragment(val group: Int, val index: Int, val count: Int, val data: ByteArray, val reply: Quote? = null) : Decoded

        /** How the sender wants to appear. Strictly well formed but not yet cleaned for display: see Profile.of. */
        class ProfileUpdate(val name: String, val avatar: String, val color: Int) : Decoded

        /** An attachment on the relay. [key] opens it; [size] is the content length; [durationDs] is in tenths of a second. */
        class AttachmentDescriptor(
            val kind: Int,
            val key: ByteArray,
            val size: Int,
            val width: Int,
            val height: Int,
            val durationDs: Int,
            val wave: ByteArray,
        ) : Decoded
    }

    /** The descriptor frame for an attachment, or null if the values are not ones [decodeAttachment] would accept. */
    fun encodeAttachment(
        kind: Int,
        key: ByteArray,
        size: Int,
        width: Int,
        height: Int,
        durationDs: Int,
        wave: ByteArray,
    ): ByteArray? {
        if (key.size != 32 || !attachmentFieldsOk(kind, size, width, height, durationDs, wave.size)) return null
        val len = ATTACHMENT_FIXED + wave.size
        val frame = ByteArray(Constants.FRAME_BYTES)
        frame[0] = TYPE_ATTACHMENT.toByte()
        frame[1] = len.toByte()
        val buf = ByteBuffer.wrap(frame, 2, len)
        buf.put(kind.toByte()).put(key).putInt(size).putShort(width.toShort()).putShort(height.toShort())
            .putShort(durationDs.toShort()).put(wave)
        return frame
    }

    private fun attachmentFieldsOk(kind: Int, size: Int, width: Int, height: Int, durationDs: Int, waveSize: Int): Boolean {
        if (size !in 1..Constants.MAX_MEDIA_BYTES) return false
        if (width !in 0..0xFFFF || height !in 0..0xFFFF || durationDs !in 0..0xFFFF) return false
        return when (kind) {
            Constants.KIND_IMAGE -> width > 0 && height > 0 && durationDs == 0 && waveSize == 0
            Constants.KIND_VIDEO -> width > 0 && height > 0 && waveSize == 0
            Constants.KIND_VOICE -> width == 0 && height == 0 && (waveSize == 0 || waveSize == Constants.WAVE_BYTES)
            else -> false
        }
    }

    private fun decodeAttachment(frame: ByteArray): Decoded.AttachmentDescriptor? {
        val len = frame[1].toInt() and 0xFF
        if (len < ATTACHMENT_FIXED || len > Constants.MAX_UTF8_BYTES) return null
        if (!zeroFrom(frame, 2 + len)) return null
        val buf = ByteBuffer.wrap(frame, 2, len)
        val kind = buf.get().toInt() and 0xFF
        val key = ByteArray(32).also { buf.get(it) }
        val size = buf.int
        val width = buf.short.toInt() and 0xFFFF
        val height = buf.short.toInt() and 0xFFFF
        val durationDs = buf.short.toInt() and 0xFFFF
        val wave = ByteArray(len - ATTACHMENT_FIXED).also { buf.get(it) }
        if (!attachmentFieldsOk(kind, size, width, height, durationDs, wave.size)) return null
        return Decoded.AttachmentDescriptor(kind, key, size, width, height, durationDs, wave)
    }

    /** What a reply points at: [who] wrote it (0 the replier, 1 the person being replied to) and a short [hash] of it. */
    class Quote(val who: Int, val hash: ByteArray)

    /**
     * The 3 bytes that identify a message inside a reply: SHA-256 of a label and the message's [canonical] form (its
     * text, or "att:" and its attachment id), cut to 3 bytes. Both phones hold the same text, so both get the same value;
     * 24 bits is enough to tell apart the messages of one chat, and two identical texts are the same message to a reader.
     */
    fun quoteHash(canonical: String): ByteArray =
        Kdf.sha256("tenchar-reply-v1".toByteArray(Charsets.UTF_8), canonical.toByteArray(Charsets.UTF_8))
            .copyOf(Constants.REPLY_HASH_BYTES)

    /**
     * The frames of a reply: one frame if the text is at most 60 bytes, otherwise fragments of 56. Null if [text] is
     * blank, not displayable, over MAX_CHARS characters, or needs more than MAX_FRAGMENTS pieces (1,960 bytes).
     */
    private fun encodeReply(text: String, group: Int, reply: Quote): List<ByteArray>? {
        if (text.isBlank() || !isDisplayable(text) || reply.who !in 0..1 || reply.hash.size != Constants.REPLY_HASH_BYTES) return null
        if (MessageLimits.graphemeCount(text) > Constants.MAX_CHARS) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > Constants.MAX_REPLY_MESSAGE_BYTES) return null
        fun header(frame: ByteArray) {
            frame[2] = reply.who.toByte()
            reply.hash.copyInto(frame, 3)
        }
        if (bytes.size <= Constants.REPLY_TEXT_BYTES) {
            val frame = ByteArray(Constants.FRAME_BYTES)
            frame[0] = TYPE_REPLY_TEXT.toByte()
            frame[1] = (4 + bytes.size).toByte()
            header(frame)
            bytes.copyInto(frame, 6)
            return listOf(frame)
        }
        val count = (bytes.size + Constants.REPLY_FRAGMENT_DATA - 1) / Constants.REPLY_FRAGMENT_DATA
        return List(count) { index ->
            val from = index * Constants.REPLY_FRAGMENT_DATA
            val to = minOf(bytes.size, from + Constants.REPLY_FRAGMENT_DATA)
            val frame = ByteArray(Constants.FRAME_BYTES)
            frame[0] = TYPE_REPLY_FRAGMENT.toByte()
            frame[1] = (8 + (to - from)).toByte()
            header(frame)
            frame[6] = (group ushr 8).toByte()
            frame[7] = group.toByte()
            frame[8] = index.toByte()
            frame[9] = count.toByte()
            bytes.copyInto(frame, 10, from, to)
            frame
        }
    }

    private fun decodeReplyText(frame: ByteArray): Decoded.Text? {
        val len = frame[1].toInt() and 0xFF
        if (len < 5 || len > Constants.MAX_UTF8_BYTES) return null
        if (!zeroFrom(frame, 2 + len)) return null
        val who = frame[2].toInt() and 0xFF
        if (who > 1) return null
        val text = validText(frame, 6, len - 4) ?: return null
        return Decoded.Text(text, Quote(who, frame.copyOfRange(3, 6)))
    }

    private fun decodeReplyFragment(frame: ByteArray): Decoded.Fragment? {
        val len = frame[1].toInt() and 0xFF
        if (len <= 8 || len > Constants.MAX_UTF8_BYTES) return null
        if (!zeroFrom(frame, 2 + len)) return null
        val who = frame[2].toInt() and 0xFF
        if (who > 1) return null
        val group = ((frame[6].toInt() and 0xFF) shl 8) or (frame[7].toInt() and 0xFF)
        val index = frame[8].toInt() and 0xFF
        val count = frame[9].toInt() and 0xFF
        if (count < 2 || count > Constants.MAX_FRAGMENTS || index >= count) return null
        val dataLength = len - 8
        // Canonical form, as for plain fragments: every piece but the last is full.
        if (index < count - 1 && dataLength != Constants.REPLY_FRAGMENT_DATA) return null
        return Decoded.Fragment(group, index, count, frame.copyOfRange(10, 2 + len), Quote(who, frame.copyOfRange(3, 6)))
    }

    /** A profile frame: [name] and [avatar] (an emoji, or empty) and a colour number. Null if it cannot fit or is malformed. */
    fun encodeProfile(name: String, avatar: String, color: Int): ByteArray? {
        if (color !in 0 until Constants.AVATAR_COLORS) return null
        if (!isDisplayable(name) || !isDisplayable(avatar)) return null
        val n = name.toByteArray(Charsets.UTF_8)
        val a = avatar.toByteArray(Charsets.UTF_8)
        if (n.size > Constants.PROFILE_NAME_BYTES || a.size > Constants.PROFILE_AVATAR_BYTES) return null
        val frame = ByteArray(Constants.FRAME_BYTES)
        frame[0] = TYPE_PROFILE.toByte()
        frame[1] = (2 + a.size + n.size).toByte()
        frame[2] = color.toByte()
        frame[3] = a.size.toByte()
        a.copyInto(frame, 4)
        n.copyInto(frame, 4 + a.size)
        return frame
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
    fun encodeMessage(text: String, group: Int, reply: Quote? = null): List<ByteArray>? {
        if (reply != null) return encodeReply(text, group, reply)
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
            TYPE_PROFILE -> decodeProfile(frame)
            TYPE_ATTACHMENT -> decodeAttachment(frame)
            TYPE_REPLY_TEXT -> decodeReplyText(frame)
            TYPE_REPLY_FRAGMENT -> decodeReplyFragment(frame)
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

    private fun decodeProfile(frame: ByteArray): Decoded.ProfileUpdate? {
        val len = frame[1].toInt() and 0xFF
        if (len < 2 || len > Constants.MAX_UTF8_BYTES) return null
        if (!zeroFrom(frame, 2 + len)) return null
        val color = frame[2].toInt() and 0xFF
        val avatarLen = frame[3].toInt() and 0xFF
        val nameLen = len - 2 - avatarLen
        if (color >= Constants.AVATAR_COLORS) return null
        if (avatarLen > Constants.PROFILE_AVATAR_BYTES || nameLen < 0 || nameLen > Constants.PROFILE_NAME_BYTES) return null
        val avatar = strict(frame, 4, avatarLen) ?: return null
        val name = strict(frame, 4 + avatarLen, nameLen) ?: return null
        if (!isDisplayable(avatar) || !isDisplayable(name)) return null
        return Decoded.ProfileUpdate(name, avatar, color)
    }

    /** Strict UTF-8, empty allowed. */
    private fun strict(bytes: ByteArray, offset: Int, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length)).toString()
    } catch (e: CharacterCodingException) {
        null
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
    internal fun isDisplayable(text: String): Boolean = text.none { c ->
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