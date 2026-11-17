package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.crypto.Kdf
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.LABEL_ID
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.MAX_HOPS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.NONCE_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_CREATED
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_HOPS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_NONCE
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_RESERVED
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_SEALED
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_TAG
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.OFF_VERSION
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.PACKET_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.SEALED_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.TAG_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.VERSION

/**
 * One mesh packet, immutable, always [PACKET_BYTES] bytes. See [RhMeshConstants] for the layout.
 *
 * Decoding is strict and canonical: right length, version, hop count in range, reserved bytes zero. There is exactly one
 * byte string for each packet, so its [id] cannot be changed without changing the packet, and a courier cannot make two
 * "different" copies of the same message.
 *
 * Use [id], never `equals`, to compare packets: two copies of one packet that differ only in [hops] have the same id.
 */
class RhPacket private constructor(private val raw: ByteArray) {
    val version: Int get() = raw[OFF_VERSION].toInt() and 0xFF

    /** Transfers this copy has made. Set by whoever receives it ([withHopsIncremented]); nothing authenticates it. */
    val hops: Int get() = raw[OFF_HOPS].toInt() and 0xFF

    val createdAtSec: Long get() = readU32(raw, OFF_CREATED)

    val nonce: ByteArray get() = raw.copyOfRange(OFF_NONCE, OFF_NONCE + NONCE_BYTES)
    val tag: ByteArray get() = raw.copyOfRange(OFF_TAG, OFF_TAG + TAG_BYTES)
    val sealed: ByteArray get() = raw.copyOfRange(OFF_SEALED, OFF_SEALED + SEALED_BYTES)

    val expiresAtMs: Long get() = RhTtl.expiresAtMs(createdAtSec)

    /** May this copy be passed on? A copy at [MAX_HOPS] has gone as far as it may. */
    val canForward: Boolean get() = hops < MAX_HOPS

    /** Content id: independent of [hops], so every copy of a packet has the same one. */
    val id: PacketId by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val digest = Kdf.sha256(
            LABEL_ID.toByteArray(Charsets.UTF_8),
            byteArrayOf(raw[OFF_VERSION]),
            raw.copyOfRange(OFF_CREATED, OFF_RESERVED),
        )
        PacketId.fromBytes(digest)
    }

    /** The bytes to put on a radio. A copy: the packet itself cannot be changed through it. */
    fun toBytes(): ByteArray = raw.copyOf()

    /** The copy a node stores after receiving this one from a peer. */
    fun withHopsIncremented(): RhPacket {
        check(hops < MAX_HOPS) { "hop limit reached" }
        val copy = raw.copyOf()
        copy[OFF_HOPS] = (hops + 1).toByte()
        return RhPacket(copy)
    }

    override fun toString(): String = "RhPacket(id=${id.toHex()}, hops=$hops, createdAt=$createdAtSec)"

    companion object {
        /** The packet in [bytes], or null if it is not exactly what [RhSealer.seal] makes. [bytes] is copied. */
        fun parse(bytes: ByteArray): RhPacket? {
            if (bytes.size != PACKET_BYTES) return null
            if ((bytes[OFF_VERSION].toInt() and 0xFF) != VERSION) return null
            if ((bytes[OFF_HOPS].toInt() and 0xFF) > MAX_HOPS) return null
            for (i in OFF_RESERVED until PACKET_BYTES) if (bytes[i].toInt() != 0) return null
            return RhPacket(bytes.copyOf())
        }

        internal fun assemble(createdAtSec: Long, nonce: ByteArray, tag: ByteArray, sealed: ByteArray): RhPacket {
            require(createdAtSec in 0..0xFFFF_FFFFL) { "creation time does not fit 32 bits" }
            require(nonce.size == NONCE_BYTES && tag.size == TAG_BYTES && sealed.size == SEALED_BYTES) { "bad field size" }
            val raw = ByteArray(PACKET_BYTES)
            raw[OFF_VERSION] = VERSION.toByte()
            raw[OFF_HOPS] = 0
            writeU32(raw, OFF_CREATED, createdAtSec)
            nonce.copyInto(raw, OFF_NONCE)
            tag.copyInto(raw, OFF_TAG)
            sealed.copyInto(raw, OFF_SEALED)
            return RhPacket(raw)
        }

        /** The authenticated header: version, creation time and tag. Not the hop count, which couriers must be able to change. */
        internal fun aad(createdAtSec: Long, tag: ByteArray): ByteArray {
            val out = ByteArray(1 + 4 + TAG_BYTES)
            out[0] = VERSION.toByte()
            writeU32(out, 1, createdAtSec)
            tag.copyInto(out, 5)
            return out
        }

        private fun readU32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        private fun writeU32(b: ByteArray, at: Int, v: Long) {
            b[at] = (v ushr 24).toByte()
            b[at + 1] = (v ushr 16).toByte()
            b[at + 2] = (v ushr 8).toByte()
            b[at + 3] = v.toByte()
        }
    }
}
