package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Reassembler
import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.store.Attachment
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.Quotes
import com.rhshourav.tenchar.store.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Replying to a chosen message: the frames, the 3-byte reference, and finding the answered message again. */
class ReplyTest {
    private val rnd = SystemRandom
    private val hash = byteArrayOf(1, 2, 3)

    private fun quote(who: Int = 1) = Wire.Quote(who, hash)

    private fun text(msg: String, group: Int = 7, who: Int = 1): List<ByteArray> = Wire.encodeMessage(msg, group, quote(who))!!

    /** Decodes every frame and joins the pieces, as the app does. Returns (text, reply). */
    private fun roundTrip(frames: List<ByteArray>): Pair<String?, Wire.Quote?> {
        val reassembler = Reassembler()
        var out: String? = null
        var ref: Wire.Quote? = null
        for (f in frames) {
            when (val d = Wire.decodeFrame(f)) {
                is Wire.Decoded.Text -> { out = d.text; ref = d.reply }
                is Wire.Decoded.Fragment -> { d.reply?.let { ref = it }; reassembler.accept(d)?.let { out = it } }
                else -> {}
            }
        }
        return out to ref
    }

    @Test
    fun aShortReplyIsOneFrameCarryingItsReference() {
        val frames = text("yes, 8 pm", who = 1)
        assertEquals(1, frames.size)
        assertEquals(Constants.FRAME_BYTES, frames[0].size)
        val d = Wire.decodeFrame(frames[0]) as Wire.Decoded.Text
        assertEquals("yes, 8 pm", d.text)
        assertEquals(1, d.reply!!.who)
        assertTrue(hash.contentEquals(d.reply!!.hash))
    }

    @Test
    fun theLengthWhereOneFrameBecomesTwo() {
        assertEquals(1, text("a".repeat(60)).size) // 60 bytes: the most one reply frame holds
        val two = text("a".repeat(61))
        assertEquals(2, two.size)
        val (back, ref) = roundTrip(two)
        assertEquals("a".repeat(61), back)
        assertTrue(hash.contentEquals(ref!!.hash))
    }

    @Test
    fun aLongReplyArrivesWholeInAnyOrderAndKeepsItsReferenceEvenIfThePiecesAreOutOfOrder() {
        val msg = ("héllo wörld " * 40).trim()
        val frames = text(msg, group = 300, who = 0)
        assertTrue(frames.size > 2)
        assertTrue(frames.all { it.size == Constants.FRAME_BYTES })
        val random = Random(5)
        repeat(10) {
            val (back, ref) = roundTrip(frames.shuffled(random))
            assertEquals(msg, back)
            assertEquals(0, ref!!.who)
            assertTrue(hash.contentEquals(ref.hash))
        }
        // Losing the first piece loses the message (as for any long message) but never garbles another one.
        assertNull(roundTrip(frames.drop(1)).first)
    }

    private operator fun String.times(n: Int) = repeat(n)

    @Test
    fun theLongestReplyAndOneByteMore() {
        val longest = "a".repeat(Constants.MAX_REPLY_MESSAGE_BYTES)
        // The character limit applies first (500), so test the byte limit with 4-byte emoji below the character limit.
        assertNull(Wire.encodeMessage("a".repeat(501), 1, quote()))
        val emoji = "😀".repeat(490) // 490 characters, 1,960 bytes: exactly the limit
        assertEquals(1_960, emoji.toByteArray().size)
        val frames = Wire.encodeMessage(emoji, 1, quote())
        assertNotNull(frames)
        assertEquals(35, frames!!.size)
        assertEquals(emoji, roundTrip(frames).first)
        assertNull(Wire.encodeMessage(emoji + "a", 1, quote())) // 1,961 bytes
        assertEquals(1_960, longest.length) // the constant is what the comment says
    }

    @Test
    fun aReplyThatCouldNeverBeRealIsRefused() {
        assertNull(Wire.encodeMessage("   ", 1, quote()))
        assertNull(Wire.encodeMessage("hi", 1, Wire.Quote(2, hash)))
        assertNull(Wire.encodeMessage("hi", 1, Wire.Quote(0, ByteArray(2))))
        assertNull(Wire.encodeMessage("hi\u202Eevil", 1, quote())) // bidirectional override characters stay out
    }

    @Test
    fun aDamagedReplyFrameIsIgnoredNotHalfRead() {
        val one = text("hello")[0]
        assertNull(Wire.decodeFrame(one.copyOf().also { it[2] = 2 })) // who must be 0 or 1
        assertNull(Wire.decodeFrame(one.copyOf().also { it[60] = 1 })) // padding must be zero
        assertNull(Wire.decodeFrame(one.copyOf().also { it[1] = 4 })) // no text at all
        assertNull(Wire.decodeFrame(one.copyOf().also { it[6] = 0 })) // a control character in the text
        val pieces = text("b".repeat(130))
        assertTrue(pieces.size >= 3)
        assertNull(Wire.decodeFrame(pieces[0].copyOf().also { it[1] = 30 })) // a piece that is not last must be full
        assertNull(Wire.decodeFrame(pieces[0].copyOf().also { it[9] = 36 })) // more pieces than any message has
        assertNull(Wire.decodeFrame(pieces[0].copyOf().also { it[8] = it[9] })) // index not below count
    }

    @Test
    fun plainMessagesAreExactlyAsBefore() {
        val a = Wire.encodeMessage("hello", 3)!!
        val b = Wire.encodeMessage("hello", 3, null)!!
        assertEquals(1, a.size)
        assertTrue(a[0].contentEquals(b[0]))
        assertEquals(Wire.TYPE_TEXT, a[0][0].toInt())
        assertNull((Wire.decodeFrame(a[0]) as Wire.Decoded.Text).reply)
    }

    // ---- the reference and finding the answered message ----------------------------------------------

    @Test
    fun theHashIsThreeBytesAndDependsOnTheText() {
        assertEquals(3, Wire.quoteHash("hello").size)
        assertTrue(Wire.quoteHash("hello").contentEquals(Wire.quoteHash("hello")))
        assertNotEquals(Wire.quoteHash("hello").toList(), Wire.quoteHash("hello!").toList())
    }

    private fun msg(id: Long, text: String, mine: Boolean, att: Attachment? = null) =
        StoredMessage(id, text, mine, Conversation.DELIVERY_NONE, 0L, att)

    @Test
    fun bothPhonesFindTheSameMessage() {
        // Rafi's phone: his own "see you at 8" (mine) and Nadia's "ok" (theirs).
        val rafi = listOf(msg(1, "see you at 8", true), msg(2, "ok", false), msg(3, "", true) /* the reply, filled in below */)
        // Nadia's phone has the same conversation from the other side.
        val nadia = listOf(msg(1, "see you at 8", false), msg(2, "ok", true), msg(3, "", false))

        // Rafi replies to Nadia's "ok" (theirs on his phone).
        val ref = Quotes.refTo(rafi[1])
        assertEquals(false, ref.quotedMine)
        val wire = Quotes.toWire(ref)
        assertEquals(1, wire.who) // "written by the person I am sending this to"

        // On Nadia's phone the same reference means her own "ok".
        val received = Quotes.fromWire(wire)
        assertEquals(true, received.quotedMine)
        assertEquals(2L, Quotes.resolve(nadia, 2, received)!!.id)

        // And Nadia replying to Rafi's "see you at 8" resolves back on his phone.
        val back = Quotes.fromWire(Quotes.toWire(Quotes.refTo(nadia[0])))
        assertEquals(true, back.quotedMine)
        assertEquals(1L, Quotes.resolve(rafi, 2, back)!!.id)
    }

    @Test
    fun onlyTheRightPersonsMessageCountsAndRepeatsResolveToTheNewestEarlierOne() {
        val list = listOf(msg(1, "ok", false), msg(2, "ok", true), msg(3, "ok", false), msg(4, "later", true), msg(5, "", false))
        val theirOk = Quotes.refTo(list[0])
        assertEquals(3L, Quotes.resolve(list, 4, theirOk)!!.id) // newest earlier "ok" written by them
        val myOk = Quotes.refTo(list[1])
        assertEquals(2L, Quotes.resolve(list, 4, myOk)!!.id) // their "ok" never stands in for mine
        // Nothing matches once the message is gone.
        assertNull(Quotes.resolve(list.filter { it.id != 4L }, 3, Quotes.refTo(list[3])))
    }

    @Test
    fun anAttachmentIsQuotedByItsIdNotItsText() {
        val key = ByteArray(32) { it.toByte() }
        val photo = msg(1, "", true, Attachment(Constants.KIND_IMAGE, key, 1000, 10, 10, 0, ByteArray(0), Attachment.READY))
        val other = msg(2, "", true, Attachment(Constants.KIND_IMAGE, key.copyOf().also { it[0] = 9 }, 1000, 10, 10, 0, ByteArray(0), Attachment.READY))
        val list = listOf(photo, other, msg(3, "", false))
        assertEquals(1L, Quotes.resolve(list, 2, Quotes.refTo(photo))!!.id)
        assertEquals(2L, Quotes.resolve(list, 2, Quotes.refTo(other))!!.id)
    }

    // ---- through the real ratchet ----------------------------------------------------------------------

    @Test
    fun aReplySentThroughTheRatchetIsAnsweredToTheRightMessageOnTheOtherPhone() {
        val ma = LocalMember.create(rnd)
        val mb = LocalMember.create(rnd)
        val key = ByteArray(32).also(rnd::nextBytes)
        val ga = Handshake.agree(ma.keys, ma.memberId, mb.keys.ik.pub, mb.keys.ek.pub, mb.memberId, key)
        val gb = Handshake.agree(mb.keys, mb.memberId, ma.keys.ik.pub, ma.keys.ek.pub, ma.memberId, key)
        fun build(me: LocalMember, peer: LocalMember, g: Handshake.Agreement) =
            if (g.initiator) Ratchet.forInitiator(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
            else Ratchet.forResponder(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
        val a = build(ma, mb, ga)
        val b = build(mb, ma, gb)

        // A's phone and B's phone hold the same chat from opposite sides.
        val aList = mutableListOf(msg(1, "dinner at 8?", true), msg(2, "can we make it 9, my train is late", false))
        val bList = mutableListOf(msg(1, "dinner at 8?", false), msg(2, "can we make it 9, my train is late", true))

        // A answers B's long message (which is "theirs" on A's phone) with a reply long enough to be fragmented.
        val answer = ("sure, nine works for me " * 8).trim()
        val ref = Quotes.refTo(aList[1])
        val envelopes = Wire.encodeMessage(answer, 40, Quotes.toWire(ref))!!.map { a.encrypt(it) }
        assertTrue(envelopes.size > 1)
        assertTrue(envelopes.all { it.size == Constants.ENVELOPE_BYTES })

        val reassembler = Reassembler()
        var shown: String? = null
        var reply: Wire.Quote? = null
        for (env in envelopes.shuffled(Random(2))) {
            when (val d = Wire.decodeFrame(b.decrypt(env)!!)) {
                is Wire.Decoded.Fragment -> { d.reply?.let { reply = it }; reassembler.accept(d)?.let { shown = it } }
                else -> {}
            }
        }
        assertEquals(answer, shown)
        bList.add(StoredMessage(3, shown!!, false, Conversation.DELIVERY_NONE, 0L, null, Quotes.fromWire(reply!!)))

        val target = Quotes.resolve(bList, 2, bList[2].reply!!)
        assertNotNull(target)
        assertEquals("can we make it 9, my train is late", target!!.text)
        assertEquals(true, target.mine) // on B's phone it is B's own message that was answered
    }
}
