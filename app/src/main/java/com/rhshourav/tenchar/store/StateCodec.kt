package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.RelayAddress
import com.rhshourav.tenchar.crypto.Ratchet
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
import com.rhshourav.tenchar.crypto.RandomSource
import com.rhshourav.tenchar.crypto.Reassembler

/**
 * The plaintext that [Vault] encrypts. A plain binary format, written and read
 * with hard limits, so a damaged file fails cleanly instead of eating memory.
 *
 * Strings are a big-endian u16 byte count followed by standard UTF-8 (see [writeStr]). They are
 * deliberately not DataOutputStream.writeUTF, which is Java's modified UTF-8 (U+0000 as C0 80 and
 * emoji as two 3-byte surrogates), a quirk no other implementation would reproduce.
 *
 * Version 3 (messages up to 2,048 bytes; pieces of long messages in progress). Versions 1 and 2 were
 * development formats that were never released, so there is no migration: any other version is refused,
 * which sends the person to the "Keys were reset" screen.
 */
object StateCodec {
    private const val MAGIC = 0x54435331 // "TCS1"
    private const val VERSION = 3
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

    fun encode(conversation: Conversation?): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeInt(MAGIC)
        out.writeByte(VERSION)
        out.writeBoolean(conversation != null)
        if (conversation != null) {
            out.write(Hex.decode(conversation.room))
            out.write(Hex.decode(conversation.memberId))
            out.write(Hex.decode(conversation.memberToken))
            out.writeByte(conversation.endpoints.size)
            for (e in conversation.endpoints) writeStr(out, e, MAX_URL_BYTES)
            out.writeBoolean(conversation.verified)
            writeStr(out, conversation.verification, VERIFICATION_BYTES)
            val ratchet = conversation.ratchet.serialize()
            out.writeInt(ratchet.size)
            out.write(ratchet)
            ratchet.fill(0)
            out.writeInt(conversation.lastIds.size)
            for ((k, v) in conversation.lastIds) {
                writeStr(out, k, MAX_URL_BYTES)
                out.writeLong(v)
            }
            out.writeInt(conversation.outbox.size)
            for (item in conversation.outbox) {
                out.writeLong(item.msgId)
                out.write(item.envelope)
            }
            out.writeInt(conversation.messages.size)
            for (m in conversation.messages) {
                out.writeLong(m.id)
                writeStr(out, m.text, Constants.MAX_MESSAGE_BYTES)
                out.writeBoolean(m.mine)
                out.writeByte(m.delivery)
            }
            out.writeLong(conversation.nextMsgId)
            val pending = conversation.reassembler.pending
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
        }
        out.flush()
        return bytes.toByteArray()
    }

    /** Null means "no conversation yet". Throws IOException for anything malformed. */
    fun decode(data: ByteArray, random: RandomSource): Conversation? {
        try {
            val input = DataInputStream(ByteArrayInputStream(data))
            if (input.readInt() != MAGIC) throw IOException("not a TenChar state file")
            if (input.readUnsignedByte() != VERSION) throw IOException("unknown state version")
            if (!input.readBoolean()) {
                if (input.available() != 0) throw IOException("trailing bytes")
                return null
            }
            val room = Hex.encode(ByteArray(16).also(input::readFully))
            val memberId = Hex.encode(ByteArray(16).also(input::readFully))
            val token = Hex.encode(ByteArray(32).also(input::readFully))
            val endpointCount = input.readUnsignedByte()
            if (endpointCount !in 1..Conversation.MAX_ENDPOINTS) throw IOException("bad endpoint count")
            val endpoints = List(endpointCount) { readStr(input, MAX_URL_BYTES) }
            val verified = input.readBoolean()
            val verification = readStr(input, VERIFICATION_BYTES)
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
            repeat(idCount) { lastIds[readStr(input, MAX_URL_BYTES)] = input.readLong() }

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
                val text = readStr(input, Constants.MAX_MESSAGE_BYTES)
                val mine = input.readBoolean()
                val delivery = input.readUnsignedByte()
                if (delivery > Conversation.DELIVERY_SENT) throw IOException("bad delivery state")
                messages.add(StoredMessage(id, text, mine, delivery))
            }
            val nextId = input.readLong()
            val groups = ArrayList<Reassembler.Group>()
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
            if (input.available() != 0) throw IOException("trailing bytes")
            val conversation = Conversation(room, memberId, token, endpoints, ratchet, verification, verified, lastIds, outbox, messages, nextId)
            conversation.reassembler.restore(groups)
            return conversation
        } catch (e: EOFException) {
            throw IOException("truncated state", e)
        }
    }
}