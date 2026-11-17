package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.MiniJson
import com.rhshourav.tenchar.ScriptedRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate for the packet layer. mesh_vectors.json is produced by tools/gen_mesh_vectors.py, written from the layout in
 * docs/RHMESH.md and not from this code, using OpenSSL through Python. Every value is compared as exact hex. If one
 * differs, decide which side contradicts the specification before touching either.
 */
class RhVectorTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> = MiniJson.parse(
        javaClass.getResourceAsStream("/mesh_vectors.json")!!.readBytes().toString(Charsets.UTF_8),
    ) as Map<String, Any?>

    private fun str(k: String) = v[k] as String
    private fun hex(k: String) = Hex.decode(str(k))

    private val keys = RhInboxKeys.derive(hex("meshSecret"), str("recipientMemberId"))

    @Test
    fun inboxKeysMatchTheReference() {
        assertEquals(str("tagKey"), Hex.encode(keys.tagKey))
        assertEquals(str("sealKey"), Hex.encode(keys.sealKey))
    }

    @Test
    fun theSealedPacketIsByteForByteTheReference() {
        val created = v["createdAt"] as Long
        val random = ScriptedRandom(listOf(hex("nonce")), "seal")
        val packet = RhSealer.seal(hex("envelope"), keys, created * 1000L + 999L, random)
        random.assertDrained() // exactly one 12-byte draw, nothing else
        assertEquals(str("tag"), Hex.encode(packet.tag))
        assertEquals(str("packet"), Hex.encode(packet.toBytes()))
        assertEquals(str("packetId"), packet.id.toHex())
        // And the reference packet opens with the reference keys.
        val parsed = RhPacket.parse(hex("packet"))!!
        assertEquals(created, parsed.createdAtSec)
        assertEquals(str("envelope"), Hex.encode(RhSealer.open(parsed, keys)!!))
    }

    @Test
    fun theBloomFilterAndBeaconMatchTheReference() {
        @Suppress("UNCHECKED_CAST")
        val ids = (v["bloomIds"] as List<String>).map { PacketId.fromHex(it) }
        val salt = (v["bloomSalt"] as Long).toInt()
        val filter = RhBloom.forCapacity(ids.size, salt) // 5 entries: the minimum size, 4 words
        ids.forEach(filter::add)
        assertEquals(256, filter.bitCount)
        assertEquals(str("bloom"), Hex.encode(filter.encode()))
        assertEquals(str("beacon"), Hex.encode(RhSetFingerprint.of(ids).beaconBytes()))
        assertTrue(ids.all(filter::mightContain))
    }
}
