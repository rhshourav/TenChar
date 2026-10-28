package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.OutboxItem
import com.rhshourav.tenchar.store.StateCodec
import com.rhshourav.tenchar.store.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.SecureRandom

class StateCodecTest {
    private val rnd = SecureRandom()

    private fun sample(): Pair<Conversation, Ratchet> {
        val a = LocalMember.create(rnd)
        val b = LocalMember.create(rnd)
        val key = ByteArray(32).also(rnd::nextBytes)
        val ga = Handshake.agree(a.keys, a.memberId, b.keys.ik.pub, b.keys.ek.pub, b.memberId, key)
        val gb = Handshake.agree(b.keys, b.memberId, a.keys.ik.pub, a.keys.ek.pub, a.memberId, key)
        val (ra, rb) = if (ga.initiator) {
            Ratchet.forInitiator(ga.sk, ga.ad, a.keys.ek, b.keys.ek.pub, rnd) to
                Ratchet.forResponder(gb.sk, gb.ad, b.keys.ek, a.keys.ek.pub, rnd)
        } else {
            Ratchet.forResponder(ga.sk, ga.ad, a.keys.ek, b.keys.ek.pub, rnd) to
                Ratchet.forInitiator(gb.sk, gb.ad, b.keys.ek, a.keys.ek.pub, rnd)
        }
        val conv = Conversation(
            room = Hex.encode(ByteArray(16) { 3 }),
            memberId = a.memberId,
            memberToken = a.memberToken,
            endpoints = listOf("https://tenchar-relay.onrender.com", "https://tenchar-relay.tenchar.workers.dev"),
            ratchet = ra,
            verification = ga.verification,
            verified = true,
        )
        conv.lastIds["https://tenchar-relay.onrender.com"] = 42
        conv.messages.add(StoredMessage(1, "hi 👍", true, Conversation.DELIVERY_SENT))
        conv.messages.add(StoredMessage(2, "আমি", false, Conversation.DELIVERY_NONE))
        conv.outbox.add(OutboxItem(1, ra.encrypt(Wire.encodeText("queued")!!)))
        conv.nextMsgId = 3
        return conv to rb
    }

    @Test
    fun roundTripKeepsEverything() {
        val (conv, peer) = sample()
        val back = StateCodec.decode(StateCodec.encode(conv), rnd)!!
        assertEquals(conv.room, back.room)
        assertEquals(conv.memberId, back.memberId)
        assertEquals(conv.memberToken, back.memberToken)
        assertEquals(conv.endpoints, back.endpoints)
        assertEquals(conv.verification, back.verification)
        assertEquals(true, back.verified)
        assertEquals(42L, back.lastIds["https://tenchar-relay.onrender.com"])
        assertEquals(listOf("hi 👍", "আমি"), back.messages.map { it.text })
        assertEquals(listOf(true, false), back.messages.map { it.mine })
        assertEquals(3L, back.nextMsgId)
        // The restored outbox envelope opens on the peer, and the restored ratchet keeps working.
        assertEquals("queued", Wire.decodeText(peer.decrypt(back.outbox[0].envelope)!!))
        assertEquals("next", Wire.decodeText(peer.decrypt(back.ratchet.encrypt(Wire.encodeText("next")!!))!!))
    }

    @Test
    fun noConversationRoundTrips() {
        assertNull(StateCodec.decode(StateCodec.encode(null), rnd))
    }

    @Test
    fun damagedDataIsRefused() {
        val bytes = StateCodec.encode(sample().first)
        var refused = 0
        for (cut in listOf(0, 3, 5, 20, bytes.size / 2, bytes.size - 1)) {
            try {
                StateCodec.decode(bytes.copyOf(cut), rnd)
            } catch (e: IOException) {
                refused++
            }
        }
        assertEquals(6, refused)
        try {
            StateCodec.decode(bytes + byteArrayOf(1), rnd)
            assertTrue("trailing byte accepted", false)
        } catch (e: IOException) {
        }
        try {
            StateCodec.decode(bytes.copyOf().also { it[0] = 0 }, rnd)
            assertTrue("bad magic accepted", false)
        } catch (e: IOException) {
        }
    }
}
