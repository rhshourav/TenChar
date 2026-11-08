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
 * version 2 (standard UTF-8) and version 3 (long messages), produced by checking out those commits.
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
        assertNull(c.themShared); assertNull(c.themLocal); assertNull(c.pushUrl)
        assertEquals(0, c.unread)
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

    @Test
    fun theOldFilesAreReallyOldFormats() {
        for (v in 1..3) assertEquals(v, resource("state_v$v.bin")[4].toInt())
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