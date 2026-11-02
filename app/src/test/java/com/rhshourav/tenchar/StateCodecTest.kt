package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.OutboxItem
import com.rhshourav.tenchar.store.StateCodec
import com.rhshourav.tenchar.store.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class StateCodecTest {
    private val rnd = SystemRandom

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

    // ---- standard UTF-8 strings (state version 2) ----------------------------------------------------

    private fun written(text: String, max: Int = 255): ByteArray {
        val bytes = ByteArrayOutputStream()
        StateCodec.writeStr(DataOutputStream(bytes), text, max)
        return bytes.toByteArray()
    }

    private fun read(bytes: ByteArray, max: Int = 255): String =
        StateCodec.readStr(DataInputStream(ByteArrayInputStream(bytes)), max)

    private fun hexOf(b: ByteArray) = Hex.encode(b)

    @Test
    fun stringsAreStandardUtf8WithAU16Length() {
        // Standard UTF-8 for U+1F600 is F0 9F 98 80. Java's writeUTF would write six bytes (ED A0 BD ED B8 80).
        assertEquals("0004f09f9880", hexOf(written("😀")))
        assertEquals("0003e0a695", hexOf(written("ক")))
        assertEquals("000100", hexOf(written("\u0000"))) // writeUTF would give C0 80
        assertEquals("0000", hexOf(written("")))
        assertEquals("000568656c6c6f", hexOf(written("hello")))
        // The count is two bytes, big endian.
        assertEquals("0100", hexOf(written("a".repeat(256), max = 300).copyOf(2)))
        for (t in listOf("😀", "ক্ষ", "hello", "\u0000", "", "a😀b")) assertEquals(t, read(written(t)))
    }

    @Test
    fun emojiBanglaAndTheLongestRelayAddressRoundTrip() {
        val host = listOf(63, 63, 63, 61).joinToString(".") { "a".repeat(it) }
        assertEquals(253, host.length)
        val longest = RelayAddress.normalise("$host:8")!!
        assertEquals(RelayAddress.MAX_NORMALISED_BYTES, longest.length)

        val (base, peer) = sample()
        val conv = Conversation(
            room = base.room, memberId = base.memberId, memberToken = base.memberToken,
            endpoints = listOf("https://tenchar-relay.onrender.com", longest),
            ratchet = base.ratchet, verification = base.verification, verified = true,
        )
        conv.lastIds[longest] = 7
        conv.messages.add(StoredMessage(1, "😀", true, Conversation.DELIVERY_SENT))
        conv.messages.add(StoredMessage(2, "বাংলা", false, Conversation.DELIVERY_NONE))
        conv.messages.add(StoredMessage(3, "ক্ষ".repeat(7), false, Conversation.DELIVERY_NONE)) // 63 bytes
        conv.nextMsgId = 4
        val back = StateCodec.decode(StateCodec.encode(conv), rnd)!!
        assertEquals(listOf("😀", "বাংলা", "ক্ষ".repeat(7)), back.messages.map { it.text })
        assertEquals(longest, back.endpoints[1])
        assertEquals(7L, back.lastIds[longest])
        assertNotNull(peer)
    }

    @Test
    fun overlongAndMalformedStringsAreRefused() {
        // Too long to write.
        try { written("a".repeat(65), max = 64); assertTrue("accepted 65 bytes", false) } catch (e: IOException) {}
        // 22 Bangla letters are 66 bytes: the limit is bytes, not characters.
        try { written("ক".repeat(22), max = 64); assertTrue("accepted 66 bytes", false) } catch (e: IOException) {}
        // A count over the limit on reading, even though the bytes would be valid.
        try { read(byteArrayOf(0, 65) + ByteArray(65) { 'a'.code.toByte() }, max = 64); assertTrue("accepted", false) } catch (e: IOException) {}
        // Malformed UTF-8: overlong NUL, a lone surrogate, a stray byte, a cut-off sequence.
        for (bad in listOf("c080", "eda080", "ff", "e0a6", "f0", "80")) {
            val body = Hex.decode(bad)
            try {
                read(byteArrayOf(0, body.size.toByte()) + body)
                assertTrue("accepted $bad", false)
            } catch (e: IOException) {}
        }
        // A count that promises more than the file holds.
        try { read(byteArrayOf(0, 5, 'a'.code.toByte())); assertTrue("accepted a short string", false) } catch (e: java.io.EOFException) {}
        // A message over the 2,048-byte limit never reaches the file, and one at the limit does.
        val (conv, _) = sample()
        conv.messages.add(StoredMessage(9, "a".repeat(2049), true, Conversation.DELIVERY_SENT))
        try { StateCodec.encode(conv); assertTrue("encoded 2049 bytes", false) } catch (e: IOException) {}
        conv.messages.removeAt(conv.messages.lastIndex)
        conv.messages.add(StoredMessage(9, "a".repeat(2048), true, Conversation.DELIVERY_SENT))
        assertEquals("a".repeat(2048), StateCodec.decode(StateCodec.encode(conv), rnd)!!.messages.last().text)
    }

    @Test
    fun onlyVersionThreeIsAccepted() {
        val bytes = StateCodec.encode(sample().first)
        assertEquals(3, bytes[4].toInt()) // magic (4 bytes), then the version
        for (version in listOf(0, 1, 2, 4, 255)) {
            try {
                StateCodec.decode(bytes.copyOf().also { it[4] = version.toByte() }, rnd)
                assertTrue("accepted version $version", false)
            } catch (e: IOException) {}
        }
        assertNotNull(StateCodec.decode(bytes, rnd))
    }
}