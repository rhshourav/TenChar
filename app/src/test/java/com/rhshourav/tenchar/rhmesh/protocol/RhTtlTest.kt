package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants as K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RhTtlTest {
    private val created = 1_800_000_000L // seconds
    private val t0 = created * 1000L

    @Test
    fun theHourIsExactlyOneHour() {
        assertEquals(3_600_000L, K.TTL_MS)
        assertEquals(t0 + 3_600_000L, RhTtl.expiresAtMs(created))
    }

    @Test
    fun aCourierKeepsAPacketUntilTheLastMillisecondOfItsHour() {
        assertTrue(RhTtl.shouldCarry(created, t0))
        assertTrue(RhTtl.shouldCarry(created, t0 + K.TTL_MS - 1))
        assertFalse(RhTtl.shouldCarry(created, t0 + K.TTL_MS))
        assertTrue(RhTtl.isExpired(created, t0 + K.TTL_MS))
        assertFalse(RhTtl.isExpired(created, t0 + K.TTL_MS - 1))
    }

    @Test
    fun aPacketFromTheFutureIsRefusedBeyondTheSkew() {
        assertTrue(RhTtl.shouldCarry(created, t0 - K.FUTURE_SKEW_MS))
        assertFalse(RhTtl.isFromFuture(created, t0 - K.FUTURE_SKEW_MS))
        assertFalse(RhTtl.shouldCarry(created, t0 - K.FUTURE_SKEW_MS - 1))
        assertTrue(RhTtl.isFromFuture(created, t0 - K.FUTURE_SKEW_MS - 1))
        assertFalse(RhTtl.shouldAccept(created, t0 - K.FUTURE_SKEW_MS - 1))
    }

    @Test
    fun noPacketCanLiveLongerThanTheHourPlusTheSkewOnAnHonestClock() {
        // Whatever creation time a sender writes, an honest node refuses it unless it is at most the skew ahead,
        // and stops carrying it one hour after that stamp: the latest possible end is TTL + skew after "now".
        val stampedAsFarAheadAsAllowed = (t0 + K.FUTURE_SKEW_MS) / 1000
        val lastMomentCarried = RhTtl.expiresAtMs(stampedAsFarAheadAsAllowed) - 1
        assertEquals(t0 + K.FUTURE_SKEW_MS + K.TTL_MS - 1, lastMomentCarried)
        assertFalse(RhTtl.shouldCarry(stampedAsFarAheadAsAllowed, lastMomentCarried + 1))
    }

    @Test
    fun onlyTheRecipientForgivesASlowCouriersClock() {
        val justPast = t0 + K.TTL_MS
        assertFalse(RhTtl.shouldCarry(created, justPast))
        assertTrue(RhTtl.shouldAccept(created, justPast))
        assertTrue(RhTtl.shouldAccept(created, justPast + K.ACCEPT_GRACE_MS - 1))
        assertFalse(RhTtl.shouldAccept(created, justPast + K.ACCEPT_GRACE_MS))
    }

    @Test
    fun theCountdownRingIsClamped() {
        assertEquals(K.TTL_MS, RhTtl.remainingMs(created, t0))
        assertEquals(K.TTL_MS / 2, RhTtl.remainingMs(created, t0 + K.TTL_MS / 2))
        assertEquals(0L, RhTtl.remainingMs(created, t0 + K.TTL_MS + 5))
        assertEquals(K.TTL_MS, RhTtl.remainingMs(created, t0 - 60_000)) // a little ahead of me: still a full ring
        assertEquals(1f, RhTtl.remainingFraction(created, t0), 0f)
        assertEquals(0.5f, RhTtl.remainingFraction(created, t0 + K.TTL_MS / 2), 0.0001f)
        assertEquals(0f, RhTtl.remainingFraction(created, t0 + K.TTL_MS), 0f)
    }

    @Test
    fun admissionFollowsTheSameBoundaries() {
        val inbox = randomInbox()
        val p = RhSealer.seal(fakeEnvelope(), inbox, t0, com.rhshourav.tenchar.crypto.SystemRandom)
        assertEquals(RhVerdict.CARRY, RhAdmission.judge(p, t0))
        assertEquals(RhVerdict.CARRY, RhAdmission.judge(p, t0 + K.TTL_MS - 1))
        assertEquals(RhVerdict.DELIVER_ONLY, RhAdmission.judge(p, t0 + K.TTL_MS))
        assertEquals(RhVerdict.EXPIRED, RhAdmission.judge(p, t0 + K.TTL_MS + K.ACCEPT_GRACE_MS))
        assertEquals(RhVerdict.FROM_FUTURE, RhAdmission.judge(p, t0 - K.FUTURE_SKEW_MS - 1))
        // The hop limit: a copy that would arrive at the last hop can be delivered to its owner but not carried on.
        var q = p
        repeat(K.MAX_HOPS - 2) { q = q.withHopsIncremented() }
        assertEquals(RhVerdict.CARRY, RhAdmission.judge(q, t0))
        q = q.withHopsIncremented()
        assertEquals(RhVerdict.DELIVER_ONLY, RhAdmission.judge(q, t0))
        q = q.withHopsIncremented()
        assertEquals(RhVerdict.HOP_LIMIT, RhAdmission.judge(q, t0))
    }
}
