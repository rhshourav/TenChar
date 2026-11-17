package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants as K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

class RhBloomTest {
    private fun ids(n: Int, seed: Long): List<PacketId> {
        val r = Random(seed)
        return List(n) { PacketId(r.nextLong()) }
    }

    @Test
    fun neverAFalseNegative() {
        val held = ids(5000, 1)
        val filter = RhBloom.of(held, salt = 99)
        assertTrue(held.all(filter::mightContain))
    }

    @Test
    fun falsePositivesStayNearTheDesignRate() {
        val held = ids(5000, 2)
        val filter = RhBloom.of(held, salt = 7)
        val strangers = ids(50_000, 3)
        val positives = strangers.count(filter::mightContain)
        val rate = positives.toDouble() / strangers.size
        val expected = filter.expectedFalsePositiveRate(held.size)
        println("false positives: %.3f %% (design %.3f %%)".format(rate * 100, expected * 100))
        assertTrue("expected about $expected, got $rate", rate < 0.02)
        assertTrue(expected in 0.004..0.015)
        assertEquals(0.5, filter.fillRatio(), 0.05) // an honestly sized filter is about half full
        assertTrue(filter.isPlausible())
    }

    @Test
    fun aFalsePositiveInOneMeetingDoesNotRepeatInTheNext() {
        val held = ids(5000, 4)
        val strangers = ids(100_000, 5)
        val first = RhBloom.of(held, salt = 1)
        val second = RhBloom.of(held, salt = 2)
        val blockedOnce = strangers.filter(first::mightContain)
        val blockedTwice = blockedOnce.count(second::mightContain)
        println("blocked in meeting 1: ${blockedOnce.size}, blocked again in meeting 2: $blockedTwice")
        assertTrue(blockedOnce.size > 100)
        // Independent filters: about the same 0.8 % of the blocked ones are blocked again, nowhere near all of them.
        assertTrue(blockedTwice < blockedOnce.size / 10)
    }

    @Test
    fun theOfferIsExactlyWhatThePeerLacks() {
        val shared = ids(300, 6)
        val onlyMine = ids(200, 7)
        val onlyTheirs = ids(100, 8)
        val peer = RhBloom.of(shared + onlyTheirs, salt = 11)
        val mine = (shared + onlyMine)
        val missing = mine.filter { !peer.mightContain(it) }
        // Nothing the peer holds is ever offered; nearly everything it lacks is.
        assertTrue(missing.none { it in shared })
        assertTrue(missing.size >= (onlyMine.size * 0.97).toInt())
        assertTrue(missing.all { it in onlyMine })
    }

    @Test
    fun theSizeFollowsTheCountWithinBounds() {
        assertEquals(K.BLOOM_MIN_WORDS * 64, RhBloom.forCapacity(0, 1).bitCount)
        assertEquals(K.BLOOM_MIN_WORDS * 64, RhBloom.forCapacity(10, 1).bitCount)
        assertEquals(157 * 64, RhBloom.forCapacity(1000, 1).bitCount) // 10,000 bits rounded up to whole words
        assertEquals(K.BLOOM_MAX_WORDS * 64, RhBloom.forCapacity(10_000_000, 1).bitCount)
        assertTrue(K.BLOOM_MAX_WORDS * 8 + K.BLOOM_HEADER_BYTES <= 512 * 1024 + 12)
        // About 1.25 bytes a packet, so even the full 50 MB cache is a few hundred KB.
        assertEquals(1.25, RhBloom.forCapacity(100_000, 1).encode().size / 100_000.0, 0.01)
    }

    @Test
    fun encodeThenDecodeAnswersTheSame() {
        val held = ids(1234, 9)
        val filter = RhBloom.of(held, salt = -12345)
        val back = RhBloom.decode(filter.encode())!!
        assertEquals(filter.salt, back.salt)
        assertEquals(filter.hashes, back.hashes)
        assertEquals(filter.bitCount, back.bitCount)
        assertTrue(held.all(back::mightContain))
        val strangers = ids(2000, 10)
        assertEquals(strangers.map(filter::mightContain), strangers.map(back::mightContain))
        assertTrue(filter.encode().contentEquals(back.encode()))
    }

    private fun header(format: Int = 1, hashes: Int = 7, reserved: Int = 0, salt: Int = 5, words: Int, body: Int = words): ByteArray {
        val buf = ByteBuffer.allocate(12 + body * 8)
        buf.put(format.toByte()).put(hashes.toByte()).putShort(reserved.toShort()).putInt(salt).putInt(words)
        return buf.array()
    }

    @Test
    fun decodingIsStrictAndBounded() {
        assertNotNull(RhBloom.decode(header(words = 4)))
        assertNotNull(RhBloom.decode(header(words = K.BLOOM_MAX_WORDS)))
        assertNull(RhBloom.decode(ByteArray(0)))
        assertNull(RhBloom.decode(ByteArray(11)))
        assertNull(RhBloom.decode(header(format = 2, words = 4)))
        assertNull(RhBloom.decode(header(hashes = 0, words = 4)))
        assertNull(RhBloom.decode(header(hashes = 17, words = 4)))
        assertNull(RhBloom.decode(header(reserved = 1, words = 4)))
        assertNull(RhBloom.decode(header(words = 3)))
        // One word too many is refused before anything that size is allocated.
        assertNull(RhBloom.decode(header(words = K.BLOOM_MAX_WORDS + 1, body = 4)))
        assertNull(RhBloom.decode(header(words = Int.MAX_VALUE, body = 4)))
        assertNull(RhBloom.decode(header(words = -1, body = 4)))
        // Length must match exactly.
        assertNull(RhBloom.decode(header(words = 4, body = 5)))
        assertNull(RhBloom.decode(header(words = 4, body = 3)))
        assertNull(RhBloom.decode(header(words = 4) + byteArrayOf(0)))
    }

    @Test
    fun aFilterThatClaimsToHoldEverythingIsNotBelieved() {
        val allOnes = RhBloom.decode(header(words = 64).also { for (i in 12 until it.size) it[i] = -1 })!!
        assertEquals(1.0, allOnes.fillRatio(), 0.0)
        assertFalse(allOnes.isPlausible())
        // The courier then offers that peer nothing, instead of believing it has everything.
        val mine = RhCourier({ emptyMap() }, { 1_800_000_000_000L })
        mine.inject(RhSealer.seal(fakeEnvelope(), randomInbox(), 1_800_000_000_000L, com.rhshourav.tenchar.crypto.SystemRandom))
        assertTrue(mine.plan(allOnes, 10).isEmpty())
        // An empty filter (nothing held) is believable and gets the packet.
        assertEquals(1, mine.plan(RhBloom.forCapacity(0, 1), 10).size)
    }

    // ---- the beacon fingerprint ---------------------------------------------------------------------------------

    @Test
    fun theFingerprintIgnoresOrderAndFollowsAddAndRemove() {
        val set = ids(500, 11)
        val a = RhSetFingerprint.of(set)
        val b = RhSetFingerprint.of(set.shuffled(Random(3)))
        assertTrue(a.beaconBytes().contentEquals(b.beaconBytes()))
        assertEquals(500, a.count)
        val before = a.beaconBytes()
        val extra = PacketId(0x1234_5678_9ABC_DEF0L)
        a.add(extra)
        assertFalse(a.beaconBytes().contentEquals(before))
        a.remove(extra)
        assertTrue(a.beaconBytes().contentEquals(before))
        assertEquals(6, before.size)
    }

    @Test
    fun differentSetsGetDifferentBeaconsAndTheCountSaturates() {
        assertFalse(RhSetFingerprint.of(ids(100, 1)).beaconBytes().contentEquals(RhSetFingerprint.of(ids(100, 2)).beaconBytes()))
        val empty = RhSetFingerprint()
        assertTrue(empty.beaconBytes().all { it.toInt() == 0 })
        val big = RhSetFingerprint.of(ids(70_000, 3))
        assertEquals(0xFFFF, ByteBuffer.wrap(big.beaconBytes()).short.toInt() and 0xFFFF)
        big.clear()
        assertEquals(0, big.count)
    }
}
