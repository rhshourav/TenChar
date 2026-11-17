package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
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

class RhSealerTest {
    private val now = 1_800_000_000_000L
    private val rnd = SystemRandom

    // ---- keys ---------------------------------------------------------------------------------------------------

    @Test
    fun bothPhonesDeriveTheSameTwoPairsWithTheArgumentsSwapped() {
        val p = pairPhones()
        val packetToB = RhSealer.seal(fakeEnvelope(1), p.a.channel.outbound, now, rnd)
        val packetToA = RhSealer.seal(fakeEnvelope(2), p.b.channel.outbound, now, rnd)
        assertArrayEquals(fakeEnvelope(1), RhSealer.open(packetToB, p.b.channel.inbound))
        assertArrayEquals(fakeEnvelope(2), RhSealer.open(packetToA, p.a.channel.inbound))
    }

    @Test
    fun theTwoDirectionsDoNotShareKeys() {
        val p = pairPhones()
        val toB = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd)
        // A packet meant for B is not addressed to A, and A's own copy of it does not open: no reflection.
        assertFalse(RhSealer.isAddressedTo(toB, p.a.channel.inbound))
        assertNull(RhSealer.open(toB, p.a.channel.inbound))
    }

    @Test
    fun aDifferentConversationOrSecretOpensNothing() {
        val p = pairPhones()
        val other = pairPhones()
        val packet = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd)
        assertFalse(RhSealer.isAddressedTo(packet, other.b.channel.inbound))
        assertNull(RhSealer.open(packet, other.b.channel.inbound))
        assertNull(RhSealer.open(packet, randomInbox()))
    }

    @Test
    fun keyArgumentsAreChecked() {
        val secret = ByteArray(32)
        assertThrows(IllegalArgumentException::class.java) { RhInboxKeys.derive(ByteArray(31), "11".repeat(16)) }
        assertThrows(IllegalArgumentException::class.java) { RhInboxKeys.derive(secret, "11".repeat(15)) }
        assertThrows(IllegalArgumentException::class.java) { MeshChannel.derive(secret, "11".repeat(16), "11".repeat(16)) }
        assertThrows(IllegalArgumentException::class.java) { MeshChannel.derive(secret, "11".repeat(16), "11".repeat(16).uppercase()) }
    }

    @Test
    fun wipingZeroesTheKeysAndToStringShowsNone() {
        val keys = RhInboxKeys.derive(ByteArray(32) { 1 }, "22".repeat(16))
        assertFalse(keys.tagKey.all { it.toInt() == 0 })
        assertEquals("RhInboxKeys(redacted)", keys.toString())
        keys.wipe()
        assertTrue(keys.tagKey.all { it.toInt() == 0 } && keys.sealKey.all { it.toInt() == 0 })
    }

    // ---- what a courier can and cannot learn ----------------------------------------------------------------------

    @Test
    fun twoPacketsForTheSamePersonShareNothingADevicePassingBothCouldUse() {
        val p = pairPhones()
        val packets = (0 until 200).map { RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd) }
        assertEquals("tags must all differ", 200, packets.map { it.tag.toList() }.toSet().size)
        assertEquals("nonces must all differ", 200, packets.map { it.nonce.toList() }.toSet().size)
        assertEquals("ciphertexts must all differ even for the same envelope", 200, packets.map { it.sealed.toList() }.toSet().size)
        assertEquals(200, packets.map { it.id }.toSet().size)
        // ...yet the one person they are for recognises every one.
        assertTrue(packets.all { RhSealer.isAddressedTo(it, p.b.channel.inbound) })
    }

    @Test
    fun theRatchetHeaderIsHiddenFromCouriers() {
        // The relay path sends the ratchet envelope as is, so its public key is visible there and links a conversation's
        // messages. On the mesh every courier would see it, so the outer layer must hide it.
        val p = pairPhones()
        val envelope = p.a.ratchet.encrypt(Wire.encodeText("hello")!!)
        val ratchetPublicKey = envelope.copyOfRange(1, 33)
        val packet = RhSealer.seal(envelope, p.a.channel.outbound, now, rnd)
        assertFalse(packet.toBytes().containsSlice(ratchetPublicKey))
        assertFalse(packet.toBytes().containsSlice(envelope.copyOfRange(41, 53))) // nor the inner nonce
        assertFalse(packet.toBytes().containsSlice("hello".toByteArray()))
    }

    @Test
    fun aTagThatIsNotMineIsRejectedAndATagThatIsMineIsAccepted() {
        val p = pairPhones()
        val packet = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd)
        val expected = RhSealer.tagFor(p.b.channel.inbound, packet.nonce)
        assertArrayEquals(expected, packet.tag)
        assertNotEquals(expected.toList(), RhSealer.tagFor(randomInbox(), packet.nonce).toList())
        assertThrows(IllegalArgumentException::class.java) { RhSealer.tagFor(p.b.channel.inbound, ByteArray(11)) }
    }

    // ---- tampering ----------------------------------------------------------------------------------------------------

    @Test
    fun alteringTheTimeOrTheCiphertextIsDetectedByTheRecipient() {
        val p = pairPhones()
        val good = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd).toBytes()
        // A courier rewriting the creation time to extend a packet's life breaks authentication.
        val newerTime = good.copyOf().also { it[K.OFF_CREATED + 3] = (it[K.OFF_CREATED + 3] + 1).toByte() }
        assertNull(RhSealer.open(RhPacket.parse(newerTime)!!, p.b.channel.inbound))
        // Every ciphertext byte is covered.
        for (pos in K.OFF_SEALED until K.OFF_RESERVED step 7) {
            val bad = good.copyOf().also { it[pos] = (it[pos].toInt() xor 0x01).toByte() }
            assertNull("sealed byte $pos", RhSealer.open(RhPacket.parse(bad)!!, p.b.channel.inbound))
        }
        // The nonce is both the GCM nonce and the tag input: changing it makes the packet not addressed to anyone.
        val newNonce = good.copyOf().also { it[K.OFF_NONCE] = (it[K.OFF_NONCE].toInt() xor 1).toByte() }
        assertFalse(RhSealer.isAddressedTo(RhPacket.parse(newNonce)!!, p.b.channel.inbound))
        // Untouched, it still opens.
        assertNotNull(RhSealer.open(RhPacket.parse(good)!!, p.b.channel.inbound))
    }

    @Test
    fun theRealRatchetOpensWhatTheMeshDelivers() {
        val p = pairPhones()
        val envelope = p.a.ratchet.encrypt(Wire.encodeText("on my way")!!)
        val packet = RhSealer.seal(envelope, p.a.channel.outbound, now, rnd)
        val inner = RhSealer.open(packet, p.b.channel.inbound)!!
        val frame = p.b.ratchet.decrypt(inner)!!
        assertEquals("on my way", Wire.decodeText(frame))
    }
}
