package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.crypto.RandomSource
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_BITS_PER_ENTRY
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_FORMAT
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_HASHES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_HEADER_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_MAX_PLAUSIBLE_FILL
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_MAX_WORDS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.BLOOM_MIN_WORDS
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.pow

/**
 * "These are the packets I already have", in about 1.25 bytes per packet.
 *
 * A node sends this to a peer it has just met. The peer then offers only packets that are NOT in it. A Bloom filter has no
 * false negatives, so a peer is never sent something this node already holds (no wasted radio time). It can have false
 * positives: a packet this node lacks may look held, and is simply not offered this time.
 *
 * Every filter carries a fresh random [salt], mixed into the bit positions. So a false positive in one meeting is
 * independent of the next: a packet blocked once is offered normally the next time, and nobody can precompute packet ids
 * that collide in everyone's filter.
 *
 * Wire form: [format 1][hashes 1][reserved 2 = 0][salt 4][words 4][words * 8 bytes], all big endian. The decoder is strict
 * and bounded (at most 512 KiB), so a hostile peer cannot make this node allocate more than that.
 */
class RhBloom private constructor(val salt: Int, val hashes: Int, private val words: LongArray) {
    val bitCount: Int get() = words.size * 64

    fun add(id: PacketId) {
        val h = hash(id)
        val h1 = h and 0xFFFFFFFFL
        val h2 = (h ushr 32) or 1L
        val m = bitCount.toLong()
        for (i in 0 until hashes) {
            val idx = ((h1 + i * h2) % m).toInt()
            words[idx ushr 6] = words[idx ushr 6] or (1L shl (idx and 63))
        }
    }

    /** False means certainly absent. True means present, or (rarely) a false positive. */
    fun mightContain(id: PacketId): Boolean {
        val h = hash(id)
        val h1 = h and 0xFFFFFFFFL
        val h2 = (h ushr 32) or 1L
        val m = bitCount.toLong()
        for (i in 0 until hashes) {
            val idx = ((h1 + i * h2) % m).toInt()
            if ((words[idx ushr 6] ushr (idx and 63)) and 1L == 0L) return false
        }
        return true
    }

    private fun hash(id: PacketId): Long =
        RhMix.mix64(id.raw xor ((salt.toLong() and 0xFFFFFFFFL) * RhMix.GOLDEN))

    /** Fraction of bits set, 0 to 1. About 0.5 for an honestly sized filter at capacity. */
    fun fillRatio(): Double {
        var set = 0L
        for (w in words) set += java.lang.Long.bitCount(w)
        return set.toDouble() / bitCount
    }

    /**
     * False for a filter fuller than an honest node would send. Such a filter claims to hold almost everything, which would
     * make this node offer nothing: the caller should ignore it for this meeting rather than believe it.
     */
    fun isPlausible(): Boolean = fillRatio() <= BLOOM_MAX_PLAUSIBLE_FILL

    /** Expected false-positive rate once [entries] packets are in. */
    fun expectedFalsePositiveRate(entries: Int): Double =
        (1.0 - exp(-hashes.toDouble() * entries / bitCount)).pow(hashes)

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(BLOOM_HEADER_BYTES + words.size * 8)
        buf.put(BLOOM_FORMAT.toByte()).put(hashes.toByte()).putShort(0).putInt(salt).putInt(words.size)
        for (w in words) buf.putLong(w)
        return buf.array()
    }

    companion object {
        /** An empty filter sized for [expected] entries at [BLOOM_BITS_PER_ENTRY] bits each. */
        fun forCapacity(expected: Int, salt: Int, hashes: Int = BLOOM_HASHES): RhBloom {
            require(expected >= 0 && hashes in 1..16) { "bad filter parameters" }
            val bits = maxOf(1L, expected.toLong() * BLOOM_BITS_PER_ENTRY)
            val words = ((bits + 63) / 64).coerceIn(BLOOM_MIN_WORDS.toLong(), BLOOM_MAX_WORDS.toLong()).toInt()
            return RhBloom(salt, hashes, LongArray(words))
        }

        fun of(ids: Collection<PacketId>, salt: Int): RhBloom =
            forCapacity(ids.size, salt).also { f -> for (id in ids) f.add(id) }

        fun newSalt(random: RandomSource): Int {
            val b = ByteArray(4).also(random::nextBytes)
            return ByteBuffer.wrap(b).int
        }

        /** The filter in [bytes], or null if it is malformed, oversize or not exactly [encode]'s form. */
        fun decode(bytes: ByteArray): RhBloom? {
            if (bytes.size < BLOOM_HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes)
            if ((buf.get().toInt() and 0xFF) != BLOOM_FORMAT) return null
            val hashes = buf.get().toInt() and 0xFF
            if (hashes !in 1..16) return null
            if (buf.short.toInt() != 0) return null
            val salt = buf.int
            val count = buf.int
            if (count !in BLOOM_MIN_WORDS..BLOOM_MAX_WORDS) return null
            if (bytes.size.toLong() != BLOOM_HEADER_BYTES + count * 8L) return null
            val words = LongArray(count) { buf.long }
            return RhBloom(salt, hashes, words)
        }
    }
}
