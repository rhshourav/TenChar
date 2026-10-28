package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.crypto.Ratchet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.security.SecureRandom

/**
 * The plaintext that [Vault] encrypts. A plain binary format, written and read
 * with hard limits, so a damaged file fails cleanly instead of eating memory.
 */
object StateCodec {
    private const val MAGIC = 0x54435331 // "TCS1"
    private const val VERSION = 1
    private const val MAX_OUTBOX = 2000
    private const val MAX_RATCHET_BYTES = 64 * 1024

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
            for (e in conversation.endpoints) out.writeUTF(e)
            out.writeBoolean(conversation.verified)
            out.writeUTF(conversation.verification)
            val ratchet = conversation.ratchet.serialize()
            out.writeInt(ratchet.size)
            out.write(ratchet)
            ratchet.fill(0)
            out.writeInt(conversation.lastIds.size)
            for ((k, v) in conversation.lastIds) {
                out.writeUTF(k)
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
                out.writeUTF(m.text)
                out.writeBoolean(m.mine)
                out.writeByte(m.delivery)
            }
            out.writeLong(conversation.nextMsgId)
        }
        out.flush()
        return bytes.toByteArray()
    }

    /** Null means "no conversation yet". Throws IOException for anything malformed. */
    fun decode(data: ByteArray, random: SecureRandom): Conversation? {
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
            val endpoints = List(endpointCount) { input.readUTF() }
            val verified = input.readBoolean()
            val verification = input.readUTF()
            if (!Regex("""\d{4} \d{4}""").matches(verification)) throw IOException("bad verification number")
            val ratchetSize = input.readInt()
            if (ratchetSize !in 1..MAX_RATCHET_BYTES) throw IOException("bad ratchet size")
            val ratchetBytes = ByteArray(ratchetSize).also(input::readFully)
            val ratchet = Ratchet.deserialize(ratchetBytes, random)
            ratchetBytes.fill(0)

            val idCount = input.readInt()
            if (idCount !in 0..Conversation.MAX_ENDPOINTS) throw IOException("bad id count")
            val lastIds = LinkedHashMap<String, Long>()
            repeat(idCount) { lastIds[input.readUTF()] = input.readLong() }

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
                val text = input.readUTF()
                val mine = input.readBoolean()
                val delivery = input.readUnsignedByte()
                if (delivery > Conversation.DELIVERY_SENT) throw IOException("bad delivery state")
                messages.add(StoredMessage(id, text, mine, delivery))
            }
            val nextId = input.readLong()
            if (input.available() != 0) throw IOException("trailing bytes")
            return Conversation(room, memberId, token, endpoints, ratchet, verification, verified, lastIds, outbox, messages, nextId)
        } catch (e: EOFException) {
            throw IOException("truncated state", e)
        }
    }
}
