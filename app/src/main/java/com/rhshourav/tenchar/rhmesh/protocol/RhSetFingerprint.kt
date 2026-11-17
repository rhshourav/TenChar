package com.rhshourav.tenchar.rhmesh.protocol

import java.nio.ByteBuffer

/**
 * A six-byte summary of a set of packet ids, small enough for a BLE beacon: how many, and an order-independent hash.
 * Two nodes whose summaries are equal hold the same packets and need not wake a second radio. When they differ, they
 * might: the exact comparison is the [RhBloom] exchange that follows.
 *
 * Adding and removing an id is O(1) (XOR), so a node keeps it current as packets arrive and expire. Not a security
 * feature: a hostile node can lie about it, and the worst that does is one wasted meeting (the caller's cooldown bounds it).
 */
class RhSetFingerprint {
    var count: Int = 0
        private set
    private var acc: Long = 0L

    fun add(id: PacketId) {
        acc = acc xor RhMix.mix64(id.raw)
        count++
    }

    fun remove(id: PacketId) {
        acc = acc xor RhMix.mix64(id.raw)
        count--
    }

    fun clear() {
        acc = 0L
        count = 0
    }

    /** [count] (saturating at 65,535) then the top 32 bits of the hash. */
    fun beaconBytes(): ByteArray = ByteBuffer.allocate(BEACON_BYTES)
        .putShort(count.coerceIn(0, 0xFFFF).toShort())
        .putInt((acc ushr 32).toInt())
        .array()

    companion object {
        const val BEACON_BYTES = 6

        fun of(ids: Collection<PacketId>): RhSetFingerprint = RhSetFingerprint().also { f -> for (id in ids) f.add(id) }
    }
}
