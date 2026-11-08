package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Reassembler
import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.store.AppState
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.StateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** A long message from the composer to the screen: split, fragment, encrypt, shuffle, decrypt, reassemble. */
class LongMessageTest {
    private val rnd = SystemRandom

    private class Pair(val a: Ratchet, val b: Ratchet, val conversationB: Conversation)

    private fun pair(): Pair {
        val ma = LocalMember.create(rnd)
        val mb = LocalMember.create(rnd)
        val key = ByteArray(32).also(rnd::nextBytes)
        val ga = Handshake.agree(ma.keys, ma.memberId, mb.keys.ik.pub, mb.keys.ek.pub, mb.memberId, key)
        val gb = Handshake.agree(mb.keys, mb.memberId, ma.keys.ik.pub, ma.keys.ek.pub, ma.memberId, key)
        fun build(me: LocalMember, peer: LocalMember, g: Handshake.Agreement) =
            if (g.initiator) Ratchet.forInitiator(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
            else Ratchet.forResponder(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
        val ra = build(ma, mb, ga)
        val rb = build(mb, ma, gb)
        val conv = Conversation(
            room = Hex.encode(ByteArray(16) { 1 }), memberId = mb.memberId, memberToken = mb.memberToken,
            endpoints = listOf("https://relay.example.com"), ratchet = rb, verification = gb.verification, verified = true,
        )
        return Pair(ra, rb, conv)
    }

    /** Everything the sender puts on the wire for [text]: one envelope per frame, tagged with its message. */
    private fun send(sender: Ratchet, text: String, group: Int): List<ByteArray> =
        MessageLimits.split(text).flatMapIndexed { n, piece ->
            Wire.encodeMessage(piece, group + n)!!.map { sender.encrypt(it) }
        }

    /** What the receiver shows: each envelope is decrypted, and pieces are joined as they complete. */
    private fun receive(ratchet: Ratchet, reassembler: Reassembler, envelopes: List<ByteArray>): List<String> =
        envelopes.mapNotNull { env ->
            val frame = ratchet.decrypt(env) ?: return@mapNotNull null
            when (val d = Wire.decodeFrame(frame)) {
                is Wire.Decoded.Text -> d.text
                is Wire.Decoded.Fragment -> reassembler.accept(d)
                is Wire.Decoded.ProfileUpdate -> null // not a message
                null -> null
            }
        }

    @Test
    fun aFiveHundredCharacterMessageArrivesWhole() {
        val p = pair()
        val text = ("word " * 100).trim()
        assertEquals(499, text.length)
        val envelopes = send(p.a, text, 1)
        assertEquals(9, envelopes.size)
        assertTrue(envelopes.all { it.size == 135 })
        assertEquals(listOf(text), receive(p.b, Reassembler(), envelopes))
    }

    private operator fun String.times(n: Int) = repeat(n)

    @Test
    fun theSameMessageArrivesWholeInAnyOrder() {
        val text = ("héllo wörld " * 40).trim()
        val rnd = Random(7)
        repeat(10) {
            val p = pair()
            val envelopes = send(p.a, text, 11).shuffled(rnd)
            assertEquals(listOf(text), receive(p.b, Reassembler(), envelopes))
        }
    }

    @Test
    fun aLongerTextArrivesAsSeveralMessagesInTheRightOrder() {
        val p = pair()
        val text = (1..400).joinToString(" ") { "w$it" } // about 1,900 characters
        val pieces = MessageLimits.split(text)
        assertTrue(pieces.size >= 3)
        assertTrue(pieces.all { MessageLimits.fits(it) })
        val shown = receive(p.b, Reassembler(), send(p.a, text, 20))
        assertEquals(pieces, shown) // in order, nothing lost, nothing joined
    }

    @Test
    fun aFullComposerOfBanglaIsDeliveredInOrderAndWhenReorderedLocally() {
        val text = "ক্ষ স্ত্রী বাংলা ".repeat(300) // enough to fill the composer
        val clamped = MessageLimits.clamp(text)
        val pieces = MessageLimits.split(clamped)
        assertTrue(pieces.size >= 5)

        // In order, however many frames: nothing is skipped, so the 200-key skip limit does not matter.
        val p = pair()
        val envelopes = send(p.a, clamped, 40)
        assertTrue("expected more than 200 frames, got ${envelopes.size}", envelopes.size > 200)
        assertEquals(pieces, receive(p.b, Reassembler(), envelopes))

        // Reordered inside windows of 40 (what a failover to a second relay can do): still all delivered.
        val q = pair()
        val rnd = Random(3)
        val shuffled = send(q.a, clamped, 40).chunked(40).flatMap { it.shuffled(rnd) }
        assertEquals(pieces.toSet(), receive(q.b, Reassembler(), shuffled).toSet())
    }

    @Test
    fun aFrameMoreThanTwoHundredPlacesEarlyIsRefusedAndHarmless() {
        // The ratchet keeps at most 200 skipped keys. A frame that arrives further ahead than that cannot be
        // opened, and the refusal changes nothing: everything before it still arrives, in order.
        val p = pair()
        val envelopes = (0 until 250).map { p.a.encrypt(Wire.encodeText("m$it")!!) }
        assertEquals(null, p.b.decrypt(envelopes.last()))
        val shown = envelopes.dropLast(1).map { Wire.decodeText(p.b.decrypt(it)!!) }
        assertEquals((0 until 249).map { "m$it" }, shown)
        // Now that the earlier ones are in, the last is only one place ahead and opens normally.
        assertEquals("m249", Wire.decodeText(p.b.decrypt(envelopes.last())!!))
    }

    @Test
    fun aLostPieceMeansTheMessageNeverAppearsButTheNextOneDoes() {
        val p = pair()
        val first = send(p.a, "a".repeat(300), 1)
        val second = send(p.a, "after", 2)
        val shown = receive(p.b, Reassembler(), first.filterIndexed { i, _ -> i != 2 } + second)
        assertEquals(listOf("after"), shown)
    }

    @Test
    fun aMessageCompletesAcrossALockAndRestart() {
        val p = pair()
        val text = "restart ".repeat(60).trim()
        val envelopes = send(p.a, text, 5)
        val half = envelopes.size / 2

        // The receiver gets some pieces, then the app is locked: the whole conversation is saved.
        val conv = p.conversationB
        assertEquals(emptyList<String>(), receive(conv.ratchet, conv.reassembler, envelopes.take(half)))
        val saved = StateCodec.encode(AppState(mutableListOf(conv)))

        // Later it is opened again from the saved bytes and the rest arrives.
        val restored = StateCodec.decode(saved, rnd).conversations.single()
        assertEquals(1, restored.reassembler.pending.size)
        assertEquals(listOf(text), receive(restored.ratchet, restored.reassembler, envelopes.drop(half)))
    }
}