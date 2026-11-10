package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.RelayAddress
import com.rhshourav.tenchar.crypto.RandomSource
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Reassembler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The plaintext that [Vault] encrypts. A plain binary format, written and read with hard limits, so a
 * damaged file fails cleanly instead of eating memory.
 *
 * Strings are a big-endian u16 byte count followed by standard UTF-8 (see [writeStr]). Version 1 used
 * DataOutputStream.writeUTF, which is Java's modified UTF-8 (U+0000 as C0 80, emoji as two 3-byte surrogates);
 * it is still read, never written.
 *
 * Versions
 *   1  one chat; strings with writeUTF
 *   2  one chat; standard UTF-8
 *   3  one chat; messages up to 2,048 bytes; pieces of long messages in progress
 *   4  up to 20 chats, each with its own profiles, notification topic and unread count; a default profile
 *   5  messages carry a time and may carry an attachment; the notification topic became a watch token
 *
 * **Every version is read.** Changing the format must never be the reason someone's chats disappear: a file
 * from any earlier version opens and is saved back as version 5. Only a file that is genuinely damaged, or from
 * a newer app than this one, is refused (and even then nothing is deleted: see Vault).
 */
object StateCodec {
    private const val MAGIC = 0x54435331 // "TCS1"
    const val VERSION = 5
    private const val OLDEST = 1
    private const val MAX_OUTBOX = 2000
    private const val MAX_RATCHET_BYTES = 64 * 1024

    /** What [RelayAddress.normalise] can produce, so no valid relay address is ever refused here. */
    private const val MAX_URL_BYTES = RelayAddress.MAX_NORMALISED_BYTES
    private const val VERIFICATION_BYTES = 9 // "dddd dddd"

    /** A u16 byte count, then standard UTF-8. Throws [IOException] if the text is over [maxBytes]. */
    internal fun writeStr(out: DataOutputStream, text: String, maxBytes: Int) {
        val bytes = try {
            Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
                .let { buf -> ByteArray(buf.remaining()).also { buf.get(it) } }
        } catch (e: CharacterCodingException) {
            throw IOException("text is not valid Unicode", e)
        }
        if (bytes.size > maxBytes) throw IOException("string of ${bytes.size} bytes is over the limit of $maxBytes")
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    /** The inverse of [writeStr]. Refuses a count over [maxBytes] and any malformed UTF-8. */
    internal fun readStr(input: DataInputStream, maxBytes: Int): String {
        val length = input.readUnsignedShort()
        if (length > maxBytes) throw IOException("string of $length bytes is over the limit of $maxBytes")
        val bytes = ByteArray(length).also(input::readFully)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw IOException("string is not valid UTF-8", e)
        }
    }

    /** Version 1 strings are Java's modified UTF-8. Read with the same size limit. */
    private fun readLegacyStr(input: DataInputStream, maxBytes: Int): String {
        val s = input.readUTF()
        if (s.toByteArray(Charsets.UTF_8).size > maxBytes) throw IOException("string over the limit of $maxBytes")
        return s
    }

    private fun str(input: DataInputStream, version: Int, maxBytes: Int): String =
        if (version == 1) readLegacyStr(input, maxBytes) else readStr(input, maxBytes)

    // ---- writing (always the newest version) ------------------------------------------------------------

    fun encode(state: AppState): ByteArray {
        if (state.conversations.size > AppState.MAX_CHATS) throw IOException("too many chats")
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        writeProfile(out, state.defaultProfile)
        out.writeByte(state.conversations.size)
        for (c in state.conversations) writeConversation(out, c)
        out.flush()
        return bytes.toByteArray()
    }

    private fun writeProfile(out: DataOutputStream, p: Profile) {
        out.writeByte(p.color)
        writeStr(out, p.name, Profile.MAX_NAME_BYTES)
        writeStr(out, p.avatar, Profile.MAX_AVATAR_BYTES)
    }

    private fun writeConversation(out: DataOutputStream, c: Conversation) {
        out.write(Hex.decode(c.room))
        out.write(Hex.decode(c.memberId))
        out.write(Hex.decode(c.memberToken))
        out.writeByte(c.endpoints.size)
        for (e in c.endpoints) writeStr(out, e, MAX_URL_BYTES)
        out.writeBoolean(c.verified)
        writeStr(out, c.verification, VERIFICATION_BYTES)
        val ratchet = c.ratchet.serialize()
        out.writeInt(ratchet.size)
        out.write(ratchet)
        ratchet.fill(0)
        out.writeInt(c.lastIds.size)
        for ((k, v) in c.lastIds) {
            writeStr(out, k, MAX_URL_BYTES)
            out.writeLong(v)
        }
        out.writeInt(c.outbox.size)
        for (item in c.outbox) {
            out.writeLong(item.msgId)
            out.write(item.envelope)
        }
        out.writeInt(c.messages.size)
        for (m in c.messages) {
            out.writeLong(m.id)
            writeStr(out, m.text, Constants.MAX_MESSAGE_BYTES)
            out.writeBoolean(m.mine)
            out.writeByte(m.delivery)
            // Added in version 5.
            out.writeLong(m.at)
            val att = m.attachment
            out.writeBoolean(att != null)
            if (att != null) writeAttachment(out, att)
        }
        out.writeLong(c.nextMsgId)
        val pending = c.reassembler.pending
        out.writeByte(pending.size)
        for (g in pending) {
            out.writeShort(g.id)
            out.writeByte(g.count)
            for (part in g.parts) {
                out.writeBoolean(part != null)
                if (part != null) {
                    out.writeByte(part.size)
                    out.write(part)
                }
            }
        }
        // Added in version 4.
        out.write(Hex.decode(c.localId))
        writeProfile(out, c.me)
        out.writeBoolean(c.themShared != null)
        c.themShared?.let { writeProfile(out, it) }
        out.writeBoolean(c.themLocal != null)
        c.themLocal?.let { writeProfile(out, it) }
        // Version 5: the watch token (version 4 had a push address here).
        out.writeBoolean(c.watchToken != null)
        c.watchToken?.let { out.write(Hex.decode(it)) }
        out.writeShort(c.unread.coerceIn(0, 0xFFFF))
    }

    private fun writeAttachment(out: DataOutputStream, a: Attachment) {
        out.writeByte(a.kind)
        out.write(a.key)
        out.writeInt(a.size)
        out.writeShort(a.width)
        out.writeShort(a.height)
        out.writeShort(a.durationDs)
        out.writeByte(a.wave.size)
        out.write(a.wave)
        out.writeByte(a.state)
    }

    private fun readAttachment(input: DataInputStream): Attachment {
        val kind = input.readUnsignedByte()
        if (kind !in Constants.KIND_IMAGE..Constants.KIND_VIDEO) throw IOException("bad attachment kind")
        val key = ByteArray(32).also(input::readFully)
        val size = input.readInt()
        if (size !in 1..Constants.MAX_MEDIA_BYTES) throw IOException("bad attachment size")
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        val duration = input.readUnsignedShort()
        val waveSize = input.readUnsignedByte()
        if (waveSize != 0 && waveSize != Constants.WAVE_BYTES) throw IOException("bad waveform")
        val wave = ByteArray(waveSize).also(input::readFully)
        val state = input.readUnsignedByte()
        if (state > Attachment.FAILED) throw IOException("bad attachment state")
        return Attachment(kind, key, size, width, height, duration, wave, state)
    }

    // ---- reading (every version) ------------------------------------------------------------------------

    /** Throws [IOException] for anything malformed or from a version this app does not know. Never returns partial data. */
    fun decode(data: ByteArray, random: RandomSource): AppState {
        try {
            val input = DataInputStream(ByteArrayInputStream(data))
            if (input.readInt() != MAGIC) throw IOException("not a TenChar state file")
            val version = input.readUnsignedByte()
            if (version < OLDEST || version > VERSION) throw IOException("state version $version is not known to this app")

            val state = AppState()
            if (version >= 4) {
                state.defaultProfile = readProfile(input)
                val count = input.readUnsignedByte()
                if (count > AppState.MAX_CHATS) throw IOException("bad chat count")
                repeat(count) { state.conversations += readConversation(input, version, random) }
            } else {
                // Versions 1 to 3 held at most one chat.
                if (input.readBoolean()) state.conversations += readConversation(input, version, random)
            }
            if (input.available() != 0) throw IOException("trailing bytes")
            return state
        } catch (e: EOFException) {
            throw IOException("truncated state", e)
        }
    }

    private fun readProfile(input: DataInputStream): Profile {
        val color = input.readUnsignedByte()
        if (color >= Profile.COLORS) throw IOException("bad colour")
        return Profile(readStr(input, Profile.MAX_NAME_BYTES), readStr(input, Profile.MAX_AVATAR_BYTES), color)
    }

    private fun readConversation(input: DataInputStream, version: Int, random: RandomSource): Conversation {
        val room = Hex.encode(ByteArray(16).also(input::readFully))
        val memberId = Hex.encode(ByteArray(16).also(input::readFully))
        val token = Hex.encode(ByteArray(32).also(input::readFully))
        val endpointCount = input.readUnsignedByte()
        if (endpointCount !in 1..Conversation.MAX_ENDPOINTS) throw IOException("bad endpoint count")
        val endpoints = List(endpointCount) { str(input, version, MAX_URL_BYTES) }
        val verified = input.readBoolean()
        val verification = str(input, version, VERIFICATION_BYTES)
        if (verification.length != VERIFICATION_BYTES || !Regex("""\d{4} \d{4}""").matches(verification)) {
            throw IOException("bad verification number")
        }
        val ratchetSize = input.readInt()
        if (ratchetSize !in 1..MAX_RATCHET_BYTES) throw IOException("bad ratchet size")
        val ratchetBytes = ByteArray(ratchetSize).also(input::readFully)
        val ratchet = Ratchet.deserialize(ratchetBytes, random)
        ratchetBytes.fill(0)

        val idCount = input.readInt()
        if (idCount !in 0..Conversation.MAX_ENDPOINTS) throw IOException("bad id count")
        val lastIds = LinkedHashMap<String, Long>()
        repeat(idCount) { lastIds[str(input, version, MAX_URL_BYTES)] = input.readLong() }

        val outboxCount = input.readInt()
        if (outboxCount !in 0..MAX_OUTBOX) throw IOException("bad outbox count")
        val outbox = ArrayList<OutboxItem>(outboxCount)
        repeat(outboxCount) {
            val id = input.readLong()
            outbox.add(OutboxItem(id, ByteArray(Constants.ENVELOPE_BYTES).also(input::readFully)))
        }

        val messageCount = input.readInt()
        if (messageCount !in 0..Conversation.MAX_MESSAGES) throw IOException("bad message count")
        val messages = ArrayList<StoredMessage>(messageCount)
        repeat(messageCount) {
            val id = input.readLong()
            val text = str(input, version, Constants.MAX_MESSAGE_BYTES)
            val mine = input.readBoolean()
            val delivery = input.readUnsignedByte()
            if (delivery > Conversation.DELIVERY_SENT) throw IOException("bad delivery state")
            var at = 0L
            var attachment: Attachment? = null
            if (version >= 5) {
                at = input.readLong()
                if (input.readBoolean()) attachment = readAttachment(input)
            }
            messages.add(StoredMessage(id, text, mine, delivery, at, attachment))
        }
        val nextId = input.readLong()

        val groups = ArrayList<Reassembler.Group>()
        if (version >= 3) {
            val pendingCount = input.readUnsignedByte()
            if (pendingCount > Constants.MAX_PENDING_MESSAGES) throw IOException("bad pending count")
            repeat(pendingCount) {
                val id = input.readUnsignedShort()
                val count = input.readUnsignedByte()
                if (count !in 2..Constants.MAX_FRAGMENTS) throw IOException("bad fragment count")
                val parts = arrayOfNulls<ByteArray>(count)
                for (i in 0 until count) {
                    if (input.readBoolean()) {
                        val size = input.readUnsignedByte()
                        if (size !in 1..Constants.MAX_FRAGMENT_DATA) throw IOException("bad fragment size")
                        parts[i] = ByteArray(size).also(input::readFully)
                    }
                }
                groups += Reassembler.Group(id, count, parts)
            }
        }

        var localId = Conversation.newLocalId(random)
        var me = Profile.EMPTY
        var shared: Profile? = null
        var local: Profile? = null
        var watch: String? = null
        var unread = 0
        if (version >= 4) {
            localId = Hex.encode(ByteArray(8).also(input::readFully))
            me = readProfile(input)
            if (input.readBoolean()) shared = readProfile(input)
            if (input.readBoolean()) local = readProfile(input)
            if (version >= 5) {
                if (input.readBoolean()) watch = Hex.encode(ByteArray(32).also(input::readFully))
            } else if (input.readBoolean()) {
                readStr(input, Conversation.MAX_PUSH_URL_BYTES) // a version 4 push address: alerts work differently now, so it is dropped
            }
            unread = input.readUnsignedShort()
        }

        val conversation = Conversation(
            room = room, memberId = memberId, memberToken = token, endpoints = endpoints, ratchet = ratchet,
            verification = verification, verified = verified, lastIds = lastIds, outbox = outbox, messages = messages,
            nextMsgId = nextId, localId = localId, me = me, themShared = shared, themLocal = local, watchToken = watch,
            unread = unread,
        )
        conversation.reassembler.restore(groups)
        return conversation
    }
}