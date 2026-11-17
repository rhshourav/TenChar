package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.Hex

/**
 * What a packet is called on the mesh: 8 bytes of a hash of its immutable content (see [RhPacket.id]). Because it is
 * content addressed, nobody can claim an id without holding the exact packet, so a hostile peer cannot "squat" an id
 * and make honest nodes believe they already have a packet they have not seen.
 */
@JvmInline
value class PacketId(val raw: Long) {
    fun toBytes(): ByteArray = ByteArray(BYTES) { i -> (raw ushr (56 - 8 * i)).toByte() }

    fun toHex(): String = Hex.encode(toBytes())

    override fun toString(): String = "PacketId(${toHex()})"

    companion object {
        const val BYTES = 8

        fun fromBytes(bytes: ByteArray, offset: Int = 0): PacketId {
            require(offset >= 0 && bytes.size - offset >= BYTES) { "a packet id is $BYTES bytes" }
            var v = 0L
            for (i in 0 until BYTES) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
            return PacketId(v)
        }

        fun fromHex(hex: String): PacketId {
            require(hex.length == BYTES * 2) { "a packet id is ${BYTES * 2} hex characters" }
            return fromBytes(Hex.decode(hex))
        }
    }
}
