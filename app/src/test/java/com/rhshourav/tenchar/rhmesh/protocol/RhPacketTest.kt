package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants as K
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RhPacketTest {
    private val inbox = randomInbox()
    private val now = 1_800_000_000_000L

    private fun packet() = RhSealer.seal(fakeEnvelope(), inbox, now, SystemRandom)

    @Test
    fun layoutAddsUpAndFitsOneBleWrite() {
        assertEquals(192, K.PACKET_BYTES)
        assertEquals(135, Constants.ENVELOPE_BYTES)
        assertEquals(151, K.SEALED_BYTES)
        assertEquals(26, K.OFF_SEALED)
        assertEquals(177, K.OFF_RESERVED)
        assertEquals(15, K.RESERVED_BYTES)
        assertEquals(K.PACKET_BYTES, K.OFF_SEALED + K.SEALED_BYTES + K.RESERVED_BYTES)
        // A GATT write at MTU 247 carries 244 bytes: one packet per write.
        assertTrue(K.PACKET_BYTES <= 244)
        assertEquals(50 * 1024 * 1024 / 192, K.DEFAULT_CACHE_PACKETS)
    }

    @Test
    fun everyPacketIsTheSameSizeWhateverItCarries() {
        val sizes = (0 until 50).map { RhSealer.seal(fakeEnvelope(it), inbox, now + it * 1000L, SystemRandom).toBytes().size }
        assertEquals(setOf(192), sizes.toSet())
    }

    @Test
    fun fieldsReadBackWhatWasSealed() {
        val p = packet()
        assertEquals(1, p.version)
        assertEquals(0, p.hops)
        assertEquals(now / 1000, p.createdAtSec)
        assertEquals(12, p.nonce.size)
        assertEquals(8, p.tag.size)
        assertEquals(151, p.sealed.size)
        assertEquals(now + K.TTL_MS, p.expiresAtMs)
        assertTrue(p.canForward)
        val back = RhPacket.parse(p.toBytes())!!
        assertArrayEquals(p.toBytes(), back.toBytes())
        assertEquals(p.id, back.id)
    }

    @Test
    fun theBytesHandedOutAreACopy() {
        val p = packet()
        val before = p.id
        p.toBytes().fill(0)
        p.nonce.fill(0)
        assertEquals(before, p.id)
        assertNotNull(RhPacket.parse(p.toBytes()))
        val raw = p.toBytes()
        val parsed = RhPacket.parse(raw)!!
        raw.fill(0) // parse copied its input
        assertEquals(p.id, parsed.id)
    }

    @Test
    fun hopsChangeTheCopyButNotTheId() {
        val p = packet()
        val q = p.withHopsIncremented()
        assertEquals(0, p.hops)
        assertEquals(1, q.hops)
        assertEquals(p.id, q.id)
        // The only byte that differs is the hop count.
        val a = p.toBytes()
        val b = q.toBytes()
        assertEquals(listOf(K.OFF_HOPS), a.indices.filter { a[it] != b[it] })
        // A copy with a different hop count still opens: hops are not authenticated, so couriers may change them.
        assertNotNull(RhSealer.open(q, inbox))
    }

    @Test
    fun theHopLimitIsEnforced() {
        var p = packet()
        repeat(K.MAX_HOPS) { p = p.withHopsIncremented() }
        assertEquals(K.MAX_HOPS, p.hops)
        assertFalse(p.canForward)
        assertThrows(IllegalStateException::class.java) { p.withHopsIncremented() }
        assertNotNull(RhPacket.parse(p.toBytes())) // exactly at the limit is a valid thing to receive
        assertNull(RhPacket.parse(p.toBytes().also { it[K.OFF_HOPS] = (K.MAX_HOPS + 1).toByte() }))
    }

    @Test
    fun theIdTracksEveryImmutableByte() {
        val p = packet()
        val base = p.toBytes()
        for (pos in listOf(K.OFF_VERSION, K.OFF_CREATED, K.OFF_NONCE, K.OFF_TAG, K.OFF_SEALED, K.OFF_RESERVED - 1)) {
            val changed = base.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            val parsed = RhPacket.parse(changed)
            // Position 0 makes it an unknown version, which parse refuses; the others parse and get a new id.
            if (pos == K.OFF_VERSION) assertNull(parsed) else assertNotEquals("byte $pos", p.id, parsed!!.id)
        }
        assertNotEquals(p.id, packet().id)
    }

    @Test
    fun decodingIsStrictAndCanonical() {
        val good = packet().toBytes()
        assertNull(RhPacket.parse(ByteArray(0)))
        assertNull(RhPacket.parse(good.copyOf(191)))
        assertNull(RhPacket.parse(good + byteArrayOf(0)))
        assertNull(RhPacket.parse(good.copyOf().also { it[K.OFF_VERSION] = 2 }))
        assertNull(RhPacket.parse(good.copyOf().also { it[K.OFF_VERSION] = 0 }))
        // The 15 reserved bytes must be zero: anything else would give one packet many byte strings (and many ids).
        for (pos in K.OFF_RESERVED until K.PACKET_BYTES) {
            assertNull("reserved byte $pos", RhPacket.parse(good.copyOf().also { it[pos] = 1 }))
        }
    }

    @Test
    fun sealRefusesWhatCouldNeverBeReal() {
        assertThrows(IllegalArgumentException::class.java) { RhSealer.seal(ByteArray(134), inbox, now, SystemRandom) }
        assertThrows(IllegalArgumentException::class.java) { RhSealer.seal(ByteArray(136), inbox, now, SystemRandom) }
        assertThrows(IllegalArgumentException::class.java) { RhSealer.seal(fakeEnvelope(), inbox, -1L, SystemRandom) }
        assertThrows(IllegalArgumentException::class.java) { RhSealer.seal(fakeEnvelope(), inbox, 0xFFFF_FFFFL * 1000 + 1000, SystemRandom) }
    }
}
