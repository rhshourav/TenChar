package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Aead
import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.Kdf
import com.rhshourav.tenchar.crypto.KeyPair
import com.rhshourav.tenchar.crypto.LocalKeys
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate. vectors.json is produced by vectors/gen_vectors.py, which was written from the
 * protocol specification and not from this code. Every value here is compared as exact hex.
 * If one differs, do not touch the vector file or this code to make it pass: decide which side
 * contradicts the specification first.
 */
class ReferenceVectorTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> = MiniJson.parse(
        javaClass.getResourceAsStream("/vectors.json")!!.readBytes().toString(Charsets.UTF_8),
    ) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun obj(m: Map<String, Any?>, k: String) = m[k] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun list(m: Map<String, Any?>, k: String) = m[k] as List<Any?>

    private fun str(m: Map<String, Any?>, k: String) = m[k] as String
    private fun hex(m: Map<String, Any?>, k: String) = Hex.decode(str(m, k))

    private class Who(val id: String, val keys: LocalKeys, val ikp: ByteArray, val ekp: ByteArray, val commit: String)

    private fun who(name: String): Who {
        val m = obj(obj(v, "members"), name)
        val ik = KeyPair(hex(m, "ik"), X25519.publicKey(hex(m, "ik")))
        val ek = KeyPair(hex(m, "ek"), X25519.publicKey(hex(m, "ek")))
        return Who(str(m, "id"), LocalKeys(ik, ek), hex(m, "ikp"), hex(m, "ekp"), str(m, "commit"))
    }

    @Test
    fun publishedKnownAnswers() {
        val p = obj(v, "published")
        val h = obj(p, "hkdf_rfc5869_case1")
        assertEquals(
            str(h, "okm"),
            Hex.encode(Kdf.hkdf(hex(h, "salt"), hex(h, "ikm"), hex(h, "info"), (h["length"] as Long).toInt())),
        )
        val g = obj(p, "aes256gcm_case16")
        val sealed = Aead.seal(hex(g, "key"), hex(g, "iv"), hex(g, "aad"), hex(g, "plaintext"))
        assertEquals(str(g, "ciphertext_and_tag"), Hex.encode(sealed))
        assertEquals(str(g, "plaintext"), Hex.encode(Aead.open(hex(g, "key"), hex(g, "iv"), hex(g, "aad"), sealed)!!))
    }

    @Test
    fun pairingDerivations() {
        val p = obj(v, "pairing")
        val code = str(p, "code").toCharArray()
        assertEquals(str(p, "room"), Pairing.hexPrefix(code, Constants.SALT_ROOM, Constants.ROOM_HEX_CHARS))
        assertEquals(str(p, "codeToken"), Pairing.hexPrefix(code, Constants.SALT_AUTH, Constants.CODE_TOKEN_HEX_CHARS))
        val key = Pairing.pbkdf2Sha256(code, Constants.SALT_KEY.toByteArray(), Constants.PBKDF2_ITERATIONS, Constants.PBKDF2_DK_BYTES)
        assertEquals(str(p, "codeKey"), Hex.encode(key))
    }

    @Test
    fun memberKeysAndCommitments() {
        for (name in listOf("A", "B")) {
            val m = obj(obj(v, "members"), name)
            val w = who(name)
            assertEquals(str(m, "ikp"), Hex.encode(w.keys.ik.pub))
            assertEquals(str(m, "ekp"), Hex.encode(w.keys.ek.pub))
            assertEquals(str(m, "commit"), Hex.encode(Handshake.commit(w.keys.ik.pub, w.keys.ek.pub, Hex.decode(w.id))))
        }
    }

    private fun agreement(me: Who, peer: Who): Handshake.Agreement =
        Handshake.agree(me.keys, me.id, peer.ikp, peer.ekp, peer.id, hex(obj(v, "pairing"), "codeKey"))

    @Test
    fun handshakeFromBothSides() {
        val h = obj(v, "handshake")
        val a = who("A")
        val b = who("B")
        for ((me, peer, initiator) in listOf(Triple(a, b, true), Triple(b, a, false))) {
            val g = agreement(me, peer)
            assertEquals(initiator, g.initiator)
            assertEquals(str(h, "SK"), Hex.encode(g.sk))
            assertEquals(str(h, "AD"), Hex.encode(g.ad))
            assertEquals(str(h, "verification"), g.verification)
        }
        assertEquals("A", str(h, "initiator"))
    }

    @Test
    fun ratchetTranscriptByteForByte() {
        val r = obj(v, "ratchet")
        val inj = obj(r, "injected")
        val nonces = list(inj, "nonces_in_send_order").map { Hex.decode(it as String) }
        fun key(name: String) = Hex.decode(str(inj, name))

        // Draw order per side: the order the specification says each phone needs randomness.
        val randomI = ScriptedRandom(
            listOf(nonces[0], key("I_ratchet2_priv"), nonces[2], nonces[3], key("I_ratchet4_priv")), "initiator A",
        )
        val randomR = ScriptedRandom(
            listOf(key("R_first_ratchet_priv"), nonces[1], key("R_ratchet3_priv"), nonces[4]), "responder B",
        )

        val a = who("A")
        val b = who("B")
        val ga = agreement(a, b)
        val gb = agreement(b, a)
        val ratchetA = Ratchet.forInitiator(ga.sk, ga.ad, a.keys.ek, b.ekp, randomI)
        val ratchetB = Ratchet.forResponder(gb.sk, gb.ad, b.keys.ek, a.ekp, randomR)

        @Suppress("UNCHECKED_CAST")
        val steps = list(r, "steps") as List<Map<String, Any?>>
        assertEquals(5, steps.size)
        assertEquals(listOf(1L, 2L, 4L, 3L, 5L), list(r, "delivery_order"))
        val sent = HashMap<Int, ByteArray>()

        fun send(k: Int) {
            val step = steps[k - 1]
            val sender = if (str(step, "from") == "A") ratchetA else ratchetB
            val frame = Wire.encodeText(str(step, "text"))!!
            assertEquals("frame $k", str(step, "frame_hex"), Hex.encode(frame))
            val envelope = sender.encrypt(frame)
            assertEquals("envelope $k", str(step, "envelope_hex"), Hex.encode(envelope))
            sent[k] = envelope
        }

        fun deliver(k: Int) {
            val step = steps[k - 1]
            val receiver = if (str(step, "from") == "A") ratchetB else ratchetA
            val frame = receiver.decrypt(sent.getValue(k))
            assertNotNull("step $k did not decrypt", frame)
            assertEquals("text $k", str(step, "text"), Wire.decodeText(frame!!))
        }

        // A sender encrypts when it is its turn, so encryption follows send order; each receiver
        // then gets the envelopes in delivery order. Step 4 overtakes step 3, so step 3 is
        // served from the skipped-key store.
        send(1); deliver(1)
        send(2); deliver(2)
        send(3); send(4)
        deliver(4); deliver(3)
        send(5); deliver(5)

        // Step 3's key is gone: a second delivery is refused and changes nothing.
        val before = ratchetB.serialize()
        assertNull(ratchetB.decrypt(sent.getValue(3)))
        assertTrue(before.contentEquals(ratchetB.serialize()))

        randomI.assertDrained()
        randomR.assertDrained()
    }
}