package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.store.AppState
import com.rhshourav.tenchar.store.Conversation
import com.rhshourav.tenchar.store.Profile
import com.rhshourav.tenchar.store.StateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved chats from every earlier version of the app must open in this one. The fixtures in
 * src/test/resources/fixtures are the real bytes written by the real encoders of version 1 (first release),
 * version 2 (standard UTF-8), version 3 (long messages), version 4 (several chats, profiles, a push address) and
 * version 5 (times, attachments, a watch token), produced by checking out those commits.
 * Changing the file format once made a saved chat unreadable, and the app then deleted it. This keeps that from
 * happening again.
 */
class StateMigrationTest {
    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    private fun info(version: Int): Map<String, String> =
        resource("state_v$version.info.txt").toString(Charsets.UTF_8).lines().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun envelopes(version: Int): List<ByteArray> =
        resource("state_v$version.envelopes.txt").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            .map { Hex.decode(it.trim()) }

    private fun open(version: Int): Conversation {
        val state = StateCodec.decode(resource("state_v$version.bin"), SystemRandom)
        assertEquals(1, state.conversations.size)
        assertEquals(Profile.EMPTY, state.defaultProfile)
        return state.conversations.single()
    }

    private fun checkContents(version: Int, c: Conversation) {
        val i = info(version)
        assertEquals(i["room"], c.room)
        assertEquals(i["memberId"], c.memberId)
        assertEquals(i["memberToken"], c.memberToken)
        assertEquals(i["verification"], c.verification)
        assertTrue(c.verified)
        assertEquals(listOf("https://tenchar-relay.onrender.com", "https://tenchar-relay.tenchar.workers.dev"), c.endpoints)
        assertEquals(42L, c.lastIds["https://tenchar-relay.onrender.com"])
        assertEquals(listOf("hi 👍", "আমি", "queued one"), c.messages.map { it.text })
        assertEquals(listOf(true, false, true), c.messages.map { it.mine })
        assertEquals(
            listOf(Conversation.DELIVERY_SENT, Conversation.DELIVERY_NONE, Conversation.DELIVERY_QUEUED),
            c.messages.map { it.delivery },
        )
        assertEquals(1, c.outbox.size)
        assertEquals(4L, c.nextMsgId)
        // Fields that did not exist yet start empty, and the chat gets an id of its own.
        assertEquals(Profile.EMPTY, c.me)
        assertNull(c.themShared); assertNull(c.themLocal); assertNull(c.watchToken)
        assertEquals(0, c.unread)
        // Messages from before version 5 have no time and no attachment, and none is a reply.
        assertTrue(c.messages.all { it.at == 0L && it.attachment == null && it.reply == null })
        assertEquals(16, c.localId.length)
    }

    private fun migrates(version: Int) {
        val c = open(version)
        checkContents(version, c)

        // The ratchet came through intact: a message the other phone sent after this file was written opens.
        val envs = envelopes(version)
        val frame = c.ratchet.decrypt(envs[0])
        assertNotNull("the migrated ratchet could not open the next message", frame)
        assertEquals(info(version)["afterText"], (Wire.decodeFrame(frame!!) as Wire.Decoded.Text).text)

        // Saved back as the newest version, it opens again unchanged, and the ratchet still works.
        val again = StateCodec.encode(AppState(mutableListOf(c)))
        assertEquals(StateCodec.VERSION, again[4].toInt())
        val reopened = StateCodec.decode(again, SystemRandom).conversations.single()
        assertEquals(c.localId, reopened.localId)
        assertEquals(c.messages.map { it.text }, reopened.messages.map { it.text })
        assertNotNull(Wire.encodeText("still works")?.let { reopened.ratchet.encrypt(it) })
    }

    @Test fun version1OpensWithJavasOldStringFormat() = migrates(1)

    @Test fun version2Opens() = migrates(2)

    @Test
    fun version3OpensAndAHalfReceivedLongMessageCompletes() {
        migrates(3)
        val c = open(3)
        checkContents(3, c)
        assertEquals(1, c.reassembler.pending.size)
        val envs = envelopes(3)
        assertNotNull(c.ratchet.decrypt(envs[0]))
        val last = Wire.decodeFrame(c.ratchet.decrypt(envs[1])!!) as Wire.Decoded.Fragment
        assertEquals(resource("state_v3.pending.txt").toString(Charsets.UTF_8), c.reassembler.accept(last))
    }

    /**
     * Version 4 is the last format that stored a push address (the ntfy topic). Alerts are built in now, so that
     * address is dropped when the file is read and alerts start off. Everything else the file held must survive.
     */
    @Test
    fun version4OpensAndItsPushAddressIsDropped() {
        val i = info(4)
        val state = StateCodec.decode(resource("state_v4.bin"), SystemRandom)
        assertEquals(Profile.of("Default me", "😀", 2), state.defaultProfile)
        val c = state.conversations.single()
        assertEquals(i["room"], c.room)
        assertEquals(i["memberId"], c.memberId)
        assertEquals(i["memberToken"], c.memberToken)
        assertEquals(i["verification"], c.verification)
        assertEquals(i["localId"], c.localId)
        assertTrue(c.verified)
        assertEquals(listOf("https://tenchar-relay.onrender.com", "https://tenchar-relay.tenchar.workers.dev"), c.endpoints)
        assertEquals(42L, c.lastIds["https://tenchar-relay.onrender.com"])
        assertEquals(listOf("hi 👍", "আমি", "queued one"), c.messages.map { it.text })
        assertEquals(listOf(true, false, true), c.messages.map { it.mine })
        assertEquals(1, c.outbox.size)
        assertEquals(4L, c.nextMsgId)
        // What version 4 added beyond version 3.
        assertEquals(Profile.of("Rafi", "🦊", 3), c.me)
        assertEquals(Profile.of("Nadia", "🌸", 5), c.themShared)
        assertEquals(Profile.of("Nadia (work)", "", 1), c.themLocal)
        assertEquals(7, c.unread)
        // The push address is gone and alerts start off; times and attachments did not exist yet.
        assertNull(c.watchToken)
        assertTrue(c.messages.all { it.at == 0L && it.attachment == null && it.reply == null })

        // The ratchet came through intact.
        val frame = c.ratchet.decrypt(envelopes(4)[0])
        assertNotNull("the migrated ratchet could not open the next message", frame)
        assertEquals(i["afterText"], (Wire.decodeFrame(frame!!) as Wire.Decoded.Text).text)

        // Saved as the newest version it opens again, with a watch token now possible, and the ratchet still works.
        c.watchToken = "ab".repeat(32)
        val again = StateCodec.encode(AppState(mutableListOf(c), state.defaultProfile))
        assertEquals(StateCodec.VERSION, again[4].toInt())
        val reopened = StateCodec.decode(again, SystemRandom).conversations.single()
        assertEquals("ab".repeat(32), reopened.watchToken)
        assertEquals(c.localId, reopened.localId)
        assertEquals(c.messages.map { it.text }, reopened.messages.map { it.text })
        assertEquals(Profile.of("Rafi", "🦊", 3), reopened.me)
        assertNotNull(Wire.encodeText("still works")?.let { reopened.ratchet.encrypt(it) })
    }

    /** Version 5 is the first with message times and attachments, and the last before replies. Nothing may be lost. */
    @Test
    fun version5OpensAndKeepsEverything() {
        val i = info(5)
        val state = StateCodec.decode(resource("state_v5.bin"), SystemRandom)
        assertEquals(Profile.of("Default me", "😀", 2), state.defaultProfile)
        val c = state.conversations.single()
        assertEquals(i["room"], c.room)
        assertEquals(i["memberId"], c.memberId)
        assertEquals(i["memberToken"], c.memberToken)
        assertEquals(i["verification"], c.verification)
        assertEquals(i["localId"], c.localId)
        assertEquals(listOf("hi 👍", "আমি", "", "", "queued one"), c.messages.map { it.text })
        assertEquals(listOf(true, false, true, false, true), c.messages.map { it.mine })
        val t0 = 1_800_000_000_000L
        assertEquals(listOf(t0, t0 + 1_000, t0 + 2_000, t0 + 3_000, t0 + 4_000), c.messages.map { it.at })
        val photo = c.messages[2].attachment!!
        assertEquals(Constants.KIND_IMAGE, photo.kind)
        assertEquals(183_000, photo.size)
        assertEquals(com.rhshourav.tenchar.store.Attachment.READY, photo.state)
        assertEquals(i["attachmentId"], photo.id)
        val voice = c.messages[3].attachment!!
        assertEquals(Constants.KIND_VOICE, voice.kind)
        assertEquals(431, voice.durationDs)
        assertEquals(Constants.WAVE_BYTES, voice.wave.size)
        assertEquals(com.rhshourav.tenchar.store.Attachment.PENDING, voice.state)
        assertEquals("ab".repeat(32), c.watchToken)
        assertEquals(Profile.of("Nadia (work)", "", 1), c.themLocal)
        assertEquals(7, c.unread)
        assertEquals(1, c.outbox.size)
        assertTrue("version 5 has no replies", c.messages.all { it.reply == null })

        val frame = c.ratchet.decrypt(envelopes(5)[0])
        assertNotNull("the migrated ratchet could not open the next message", frame)
        assertEquals(i["afterText"], (Wire.decodeFrame(frame!!) as Wire.Decoded.Text).text)

        // Saved as the newest version it opens again, now with a reply on one message.
        val ref = com.rhshourav.tenchar.store.Quotes.refTo(c.messages[1])
        val withReply = com.rhshourav.tenchar.store.StoredMessage(6, "answer", true, Conversation.DELIVERY_SENT, t0 + 5_000, null, ref)
        c.messages.add(withReply)
        val again = StateCodec.encode(AppState(mutableListOf(c), state.defaultProfile))
        assertEquals(StateCodec.VERSION, again[4].toInt())
        val reopened = StateCodec.decode(again, SystemRandom).conversations.single()
        assertEquals(c.messages.map { it.text }, reopened.messages.map { it.text })
        assertEquals(i["attachmentId"], reopened.messages[2].attachment!!.id)
        val back = reopened.messages.last().reply!!
        assertEquals(false, back.quotedMine)
        assertEquals("আমি", com.rhshourav.tenchar.store.Quotes.resolve(reopened.messages, reopened.messages.lastIndex, back)!!.text)
        assertNotNull(Wire.encodeText("still works")?.let { reopened.ratchet.encrypt(it) })
    }

    @Test
    fun theOldFilesAreReallyOldFormats() {
        for (v in 1..5) assertEquals(v, resource("state_v$v.bin")[4].toInt())
        // Version 1 wrote strings with writeUTF: the emoji is two 3-byte surrogates (6 bytes), not F0 9F 91 8D.
        val v1 = Hex.encode(resource("state_v1.bin"))
        assertTrue(v1.contains("eda0bd" + "edb18d"))
        assertTrue(!v1.contains("f09f918d"))
        assertTrue(Hex.encode(resource("state_v2.bin")).contains("f09f918d"))
    }

    @Test
    fun aFileFromANewerAppIsRefusedNotMisread() {
        val bytes = resource("state_v3.bin").copyOf().also { it[4] = 9 }
        try {
            StateCodec.decode(bytes, SystemRandom)
            assertTrue("accepted version 9", false)
        } catch (e: java.io.IOException) {
        }
    }
}