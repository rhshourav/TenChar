package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class RatchetTest {
    private val rnd = SecureRandom()

    /** Two phones, set up exactly as pairing does it. */
    private class Pair(val a: Ratchet, val b: Ratchet)

    private fun pair(): Pair {
        val ma = LocalMember.create(rnd)
        val mb = LocalMember.create(rnd)
        val codeKey = ByteArray(32).also(rnd::nextBytes)
        val ga = Handshake.agree(ma.keys, ma.memberId, mb.keys.ik.pub, mb.keys.ek.pub, mb.memberId, codeKey)
        val gb = Handshake.agree(mb.keys, mb.memberId, ma.keys.ik.pub, ma.keys.ek.pub, ma.memberId, codeKey)
        fun build(me: LocalMember, peer: LocalMember, g: Handshake.Agreement) =
            if (g.initiator) Ratchet.forInitiator(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
            else Ratchet.forResponder(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
        return Pair(build(ma, mb, ga), build(mb, ma, gb))
    }

    private fun send(r: Ratchet, text: String): ByteArray = r.encrypt(Wire.encodeText(text)!!)
    private fun read(r: Ratchet, env: ByteArray): String? = r.decrypt(env)?.let { Wire.decodeText(it) }

    @Test
    fun envelopeIsExactlyTheSizeTheRelayAccepts() {
        val p = pair()
        val env = send(p.a, "hi")
        assertEquals(135, env.size)
        assertEquals(180, Wire.toBase64(env).length)
    }

    @Test
    fun eitherSideCanSendFirst() {
        repeat(6) {
            val p = pair()
            assertEquals("one", read(p.b, send(p.a, "one")))
            assertEquals("two", read(p.a, send(p.b, "two")))
        }
        // Same, but the second phone speaks first.
        repeat(6) {
            val p = pair()
            assertEquals("first", read(p.a, send(p.b, "first")))
            assertEquals("reply", read(p.b, send(p.a, "reply")))
            assertEquals("again", read(p.a, send(p.b, "again")))
        }
    }

    @Test
    fun longConversationWithChangingDirection() {
        val p = pair()
        for (round in 0 until 40) {
            val from = if (round % 3 == 0) p.b else p.a
            val to = if (from === p.a) p.b else p.a
            for (i in 0 until 1 + round % 4) {
                val text = "r${round}m$i"
                assertEquals(text, read(to, send(from, text)))
            }
        }
    }

    @Test
    fun bothSendAtTheSameTime() {
        val p = pair()
        val fromA = (0 until 3).map { send(p.a, "a$it") }
        val fromB = (0 until 3).map { send(p.b, "b$it") }
        for (i in 0 until 3) {
            assertEquals("a$i", read(p.b, fromA[i]))
            assertEquals("b$i", read(p.a, fromB[i]))
        }
        assertEquals("after", read(p.b, send(p.a, "after")))
        assertEquals("back", read(p.a, send(p.b, "back")))
    }

    @Test
    fun outOfOrderMessagesAreAllDelivered() {
        val p = pair()
        val envs = (0 until 6).map { send(p.a, "m$it") }
        for (i in listOf(3, 0, 5, 1, 4, 2)) assertEquals("m$i", read(p.b, envs[i]))
        // A reply and a late message across a ratchet step.
        val late = send(p.a, "late")
        val early = send(p.a, "early")
        assertEquals("early", read(p.b, early))
        assertEquals("answer", read(p.a, send(p.b, "answer")))
        val next = send(p.a, "next")
        assertEquals("next", read(p.b, next))
        assertEquals("late", read(p.b, late))
    }

    @Test
    fun replayedMessagesAreRejected() {
        val p = pair()
        val env = send(p.a, "once")
        assertEquals("once", read(p.b, env))
        assertNull(p.b.decrypt(env))
        // Also after the ratchet has moved on.
        assertEquals("r", read(p.a, send(p.b, "r")))
        val env2 = send(p.a, "two")
        assertEquals("two", read(p.b, env2))
        assertNull(p.b.decrypt(env))
        assertNull(p.b.decrypt(env2))
    }

    @Test
    fun aLateMessageCanBeReadOnlyOnce() {
        val p = pair()
        val first = send(p.a, "first")
        val second = send(p.a, "second")
        assertEquals("second", read(p.b, second))
        assertEquals("first", read(p.b, first))
        assertNull(p.b.decrypt(first))
    }

    @Test
    fun tamperingAnywhereIsRejectedAndChangesNothing() {
        val p = pair()
        val env = send(p.a, "secret")
        for (i in env.indices) {
            val bad = env.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("byte $i", p.b.decrypt(bad))
        }
        // None of the failures disturbed the state: the real message still opens.
        assertEquals("secret", read(p.b, env))
    }

    @Test
    fun aForgedNewRatchetKeyDoesNotBreakTheConversation() {
        val p = pair()
        assertEquals("a", read(p.b, send(p.a, "a")))
        val forged = send(p.a, "b").copyOf()
        // New public key in the header: forces a ratchet step on a copy, then fails.
        for (i in 1 until 33) forged[i] = (i * 7).toByte()
        assertNull(p.b.decrypt(forged))
        assertEquals("c", read(p.b, send(p.a, "c")))
    }

    @Test
    fun associatedDataBindsTheConversation() {
        val p1 = pair()
        val p2 = pair()
        val env = send(p1.a, "x")
        assertNull(p2.b.decrypt(env))
    }

    @Test
    fun skippingTooFarIsRefusedButTheConversationSurvives() {
        val p = pair()
        val envs = (0 until 205).map { send(p.a, "m") }
        assertNull(p.b.decrypt(envs[204])) // 204 skipped, over the limit of 200
        assertNotNull(p.b.decrypt(envs[0]))
        assertNotNull(p.b.decrypt(envs[150]))
    }

    @Test
    fun stateSurvivesSerialization() {
        val p = pair()
        assertEquals("1", read(p.b, send(p.a, "1")))
        val held = send(p.a, "held")
        val after = send(p.a, "after")
        assertEquals("after", read(p.b, after)) // leaves a skipped key for "held"
        assertEquals("2", read(p.a, send(p.b, "2")))

        val restoredA = Ratchet.deserialize(p.a.serialize(), rnd)
        val restoredB = Ratchet.deserialize(p.b.serialize(), rnd)
        assertEquals("held", read(restoredB, held))
        assertEquals("3", read(restoredB, send(restoredA, "3")))
        assertEquals("4", read(restoredA, send(restoredB, "4")))
        assertTrue(restoredA.serialize().isNotEmpty())
    }

    @Test
    fun corruptStateIsRefused() {
        val state = pair().a.serialize()
        var refused = 0
        for (cut in listOf(0, 1, 50, state.size - 1)) {
            try {
                Ratchet.deserialize(state.copyOf(cut), rnd)
            } catch (e: java.io.IOException) {
                refused++
            }
        }
        assertEquals(4, refused)
        try {
            Ratchet.deserialize(state + byteArrayOf(0), rnd)
            assertFalse("trailing byte accepted", true)
        } catch (e: java.io.IOException) {
        }
    }

    @Test
    fun ciphertextsDifferEvenForTheSameText() {
        val p = pair()
        val e1 = send(p.a, "same")
        val e2 = send(p.a, "same")
        assertFalse(e1.contentEquals(e2))
    }
}
