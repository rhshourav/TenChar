package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class HandshakeTest {
    private val rnd = SecureRandom()
    private val codeKey = ByteArray(32) { it.toByte() }

    private fun agree(me: LocalMember, peer: LocalMember, key: ByteArray = codeKey) = Handshake.agree(
        me.keys, me.memberId, peer.keys.ik.pub, peer.keys.ek.pub, peer.memberId, key,
    )

    @Test
    fun bothSidesReachTheSameSecretAndNumber() {
        repeat(10) {
            val a = LocalMember.create(rnd)
            val b = LocalMember.create(rnd)
            val fromA = agree(a, b)
            val fromB = agree(b, a)
            assertTrue(fromA.sk.contentEquals(fromB.sk))
            assertTrue(fromA.ad.contentEquals(fromB.ad))
            assertEquals(fromA.verification, fromB.verification)
            assertTrue(fromA.initiator != fromB.initiator)
            assertTrue(Regex("""\d{4} \d{4}""").matches(fromA.verification))
        }
    }

    @Test
    fun aDifferentCodeGivesADifferentSecretAndNumber() {
        val a = LocalMember.create(rnd)
        val b = LocalMember.create(rnd)
        val right = agree(a, b)
        val wrong = agree(b, a, ByteArray(32) { 7 })
        assertFalse(right.sk.contentEquals(wrong.sk))
    }

    @Test
    fun aSwappedKeyGivesADifferentNumber() {
        // A relay that puts its own keys in place of B's cannot make both numbers match.
        val a = LocalMember.create(rnd)
        val b = LocalMember.create(rnd)
        val mallory = LocalMember.create(rnd)
        val seenByA = Handshake.agree(a.keys, a.memberId, mallory.keys.ik.pub, mallory.keys.ek.pub, b.memberId, codeKey)
        val seenByB = agree(b, a)
        assertTrue(seenByA.verification != seenByB.verification || !seenByA.sk.contentEquals(seenByB.sk))
        assertFalse(seenByA.sk.contentEquals(seenByB.sk))
    }

    @Test
    fun commitMatchesTheRelayFormula() {
        // The relay computes sha256("tenchar-commit-v1" || ik || ek || memberId). Same bytes in, same hash out.
        val m = LocalMember.create(rnd)
        val expected = Hex.encode(
            java.security.MessageDigest.getInstance("SHA-256").digest(
                "tenchar-commit-v1".toByteArray() + m.keys.ik.pub + m.keys.ek.pub + Hex.decode(m.memberId),
            ),
        )
        assertEquals(expected, m.commit)
        assertEquals(64, m.commit.length)
        assertEquals(32, m.memberId.length)
        assertEquals(64, m.memberToken.length)
        assertEquals(64, m.tokenHash.length)
    }

    @Test
    fun memberTokenHashIsSha256OfTheRawTokenBytes() {
        val m = LocalMember.create(rnd)
        val expected = Hex.encode(java.security.MessageDigest.getInstance("SHA-256").digest(Hex.decode(m.memberToken)))
        assertEquals(expected, m.tokenHash)
    }
}
