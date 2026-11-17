package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants as K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhCourierTest {
    private val t0 = 1_800_000_000_000L
    private var now = t0
    private val rnd = SystemRandom

    private fun courier(max: Int = 1000, carry: Boolean = true, channels: Map<String, MeshChannel> = emptyMap()) =
        RhCourier({ channels }, { now }, max, carry)

    private fun strangers(at: Long = now, fill: Int = 1) = RhSealer.seal(fakeEnvelope(fill), randomInbox(), at, rnd)

    private fun drop(r: RhReceive): RhDropReason = (r as RhReceive.Dropped).reason

    @Test
    fun aStrangersPacketIsCarriedOnceAndRememberedAsSeen() {
        val c = courier()
        val p = strangers()
        val first = c.receive(p.toBytes())
        assertTrue(first is RhReceive.Stored)
        assertEquals(1, c.size)
        assertEquals(1, c.packets().single().hops) // one transfer: from whoever handed it over
        assertTrue(c.receive(p.toBytes()) is RhReceive.Duplicate)
        assertEquals(1, c.size)
        // A copy that took a longer route is the same packet.
        assertTrue(c.receive(p.withHopsIncremented().toBytes()) is RhReceive.Duplicate)
    }

    @Test
    fun everyWayToBeRefusedHasItsOwnReason() {
        val c = courier()
        assertEquals(RhDropReason.MALFORMED, drop(c.receive(ByteArray(10))))
        assertEquals(RhDropReason.MALFORMED, drop(c.receive(strangers().toBytes().also { it[K.OFF_RESERVED] = 1 })))
        assertEquals(RhDropReason.EXPIRED, drop(c.receive(strangers(now - K.TTL_MS - K.ACCEPT_GRACE_MS).toBytes())))
        assertEquals(RhDropReason.FROM_FUTURE, drop(c.receive(strangers(now + 5 * 60_000).toBytes())))
        var atLimit = strangers()
        repeat(K.MAX_HOPS) { atLimit = atLimit.withHopsIncremented() }
        assertEquals(RhDropReason.HOP_LIMIT, drop(c.receive(atLimit.toBytes())))
        // The last hop that is still legal: accepted by its owner, but a stranger will not keep it.
        var lastLegal = strangers(fill = 9)
        repeat(K.MAX_HOPS - 1) { lastLegal = lastLegal.withHopsIncremented() }
        assertEquals(RhDropReason.NOT_FORWARDABLE, drop(c.receive(lastLegal.toBytes())))
        assertEquals(0, c.size)
        // Refusals for being expired or from the future do not mark the id as seen: it may become valid.
        val later = strangers(now + 90_000)
        assertTrue(c.receive(later.toBytes()) is RhReceive.Stored)
    }

    @Test
    fun courierModeOffCarriesNothingForStrangersButStillSendsItsOwn() {
        val own = pairPhones()
        val c = courier(carry = false, channels = mapOf("chat" to own.a.channel))
        assertEquals(RhDropReason.COURIER_OFF, drop(c.receive(strangers().toBytes())))
        assertEquals(0, c.size)
        val mine = RhSealer.seal(fakeEnvelope(), own.a.channel.outbound, now, rnd)
        assertTrue(c.inject(mine))
        assertEquals(listOf(mine.id), c.plan(null, 10).map { it.id })
        // Switch it on, take a stranger's packet, switch it off: it keeps the packet but stops offering it.
        c.carryForOthers = true
        assertTrue(c.receive(strangers(fill = 5).toBytes()) is RhReceive.Stored)
        c.carryForOthers = false
        assertEquals(listOf(mine.id), c.plan(null, 10).map { it.id })
    }

    @Test
    fun deliveryToTheOwnerKeepsNothingAndIsNeverOfferedAgain() {
        val p = pairPhones()
        val d = courier(channels = mapOf("chat" to p.b.channel))
        val packet = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd)
        val r = d.receive(packet.toBytes()) as RhReceive.Delivered
        assertEquals("chat", r.channelKey)
        assertEquals(1, r.hops)
        assertEquals(0, d.size) // the recipient does not ferry its own mail onward
        assertTrue(d.receive(packet.toBytes()) is RhReceive.Duplicate)
        // But it tells peers it has handled it, so none re-sends it.
        assertTrue(d.bloom(1).mightContain(packet.id))
    }

    @Test
    fun aForgedPacketForMeIsDroppedAndNeverCarried() {
        val p = pairPhones()
        val d = courier(channels = mapOf("chat" to p.b.channel))
        val good = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd).toBytes()
        val forged = good.copyOf().also { it[K.OFF_SEALED + 40] = (it[K.OFF_SEALED + 40].toInt() xor 1).toByte() }
        assertEquals(RhDropReason.FORGED, drop(d.receive(forged)))
        assertEquals(0, d.size)
        // The genuine one still arrives afterwards.
        assertTrue(d.receive(good) is RhReceive.Delivered)
    }

    @Test
    fun aCourierCannotTellATamperedPacketFromAGenuineOne() {
        // The price of blind carrying: with no key, a courier only sees 192 bytes. It carries a damaged packet like any
        // other; the recipient is the one that finds it forged. Spam is bounded by the cache limit, not by recognition.
        val p = pairPhones()
        val courier = courier()
        val good = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, now, rnd).toBytes()
        val damaged = good.copyOf().also { it[K.OFF_SEALED] = (it[K.OFF_SEALED].toInt() xor 1).toByte() }
        assertTrue(courier.receive(damaged) is RhReceive.Stored)
        assertTrue(courier.receive(good) is RhReceive.Stored)
    }

    // ---- limits ------------------------------------------------------------------------------------------------------

    @Test
    fun aFullCacheEvictsWhatDiesSoonestAndNeverMyOwnMessages() {
        val own = pairPhones()
        val c = courier(max = 3, channels = mapOf("chat" to own.a.channel))
        val mine = RhSealer.seal(fakeEnvelope(), own.a.channel.outbound, now - 1_000_000, rnd) // dies first of all
        assertTrue(c.inject(mine))
        val s1 = strangers(now - 600_000, 1)
        val s2 = strangers(now - 300_000, 2)
        assertTrue(c.receive(s1.toBytes()) is RhReceive.Stored)
        assertTrue(c.receive(s2.toBytes()) is RhReceive.Stored)
        assertEquals(3, c.size)
        // A newer stranger pushes out the stranger that dies soonest (s1), not my older packet.
        val s3 = strangers(now, 3)
        assertTrue(c.receive(s3.toBytes()) is RhReceive.Stored)
        assertTrue(c.holds(mine.id) && c.holds(s2.id) && c.holds(s3.id) && !c.holds(s1.id))
        // A stranger that would die sooner than everything held is not worth a slot.
        assertEquals(RhDropReason.CACHE_FULL, drop(c.receive(strangers(now - 3_000_000, 4).toBytes())))
        assertEquals(3, c.size)
        // And a flood cannot push my packet out.
        repeat(50) { c.receive(strangers(now + it * 1000L, 10 + it).toBytes()) }
        assertTrue(c.holds(mine.id))
        assertEquals(3, c.size)
        assertEquals(1, c.ownCount)
    }

    @Test
    fun shrinkingTheLimitEvictsDownToIt() {
        val c = courier(max = 10)
        repeat(10) { c.receive(strangers(now + it * 1000L, it).toBytes()) }
        assertEquals(10, c.size)
        c.maxPackets = 4
        assertEquals(4, c.size)
        c.maxPackets = 0
        assertEquals(0, c.size)
        assertEquals(RhDropReason.CACHE_FULL, drop(c.receive(strangers(fill = 77).toBytes())))
    }

    @Test
    fun theHourEndsOnTheMillisecondAndEverythingIsForgotten() {
        val c = courier()
        val p = strangers(t0)
        c.receive(p.toBytes())
        now = t0 + K.TTL_MS - 1
        assertEquals(0, c.purgeExpired())
        assertEquals(1, c.plan(null, 10).size)
        now = t0 + K.TTL_MS
        // Even before the sweep runs, an expired packet is not offered.
        assertTrue(c.plan(null, 10).isEmpty())
        assertEquals(1, c.purgeExpired())
        assertEquals(0, c.size)
        assertEquals(0, c.packets().size)
        assertTrue(c.beacon().take(2).all { it.toInt() == 0 })
        // The seen set is held a little longer (the recipient's grace), then it goes too.
        assertEquals(1, c.seenCount)
        now = t0 + K.TTL_MS + K.ACCEPT_GRACE_MS
        c.purgeExpired()
        assertEquals(0, c.seenCount)
    }

    @Test
    fun theOfferIsOrderedFewestHopsThenNewestAndLimited() {
        val c = courier()
        val own = pairPhones()
        val ownOld = RhSealer.seal(fakeEnvelope(1), randomInbox(), now - 500_000, rnd)
        val ownNew = RhSealer.seal(fakeEnvelope(2), randomInbox(), now - 100_000, rnd)
        c.inject(ownOld)
        c.inject(ownNew)
        val oneHop = strangers(now - 50_000, 3)
        c.receive(oneHop.toBytes()) // stored at hops 1
        val order = c.plan(null, 10).map { it.id }
        assertEquals(listOf(ownNew.id, ownOld.id, oneHop.id), order)
        assertEquals(listOf(ownNew.id), c.plan(null, 1).map { it.id })
        assertTrue(c.plan(null, 0).isEmpty())
        assertNotNull(own)
    }

    @Test
    fun thePacketsAKnownPeerAlreadyHasAreNeverOffered() {
        val a = courier()
        val b = courier()
        val shared = strangers(fill = 1)
        val onlyA = strangers(fill = 2)
        a.receive(shared.toBytes()); b.receive(shared.toBytes()); a.receive(onlyA.toBytes())
        val offered = a.plan(RhBloom.decode(b.bloom(5).encode()), 10).map { it.id }
        assertEquals(listOf(onlyA.id), offered)
        // The beacons differ before the meeting and agree after it.
        assertFalse(a.beacon().contentEquals(b.beacon()))
        meet(a, b)
        assertTrue(a.beacon().contentEquals(b.beacon()))
        assertEquals(0, meet(a, b).offered)
    }

    @Test
    fun wipeEmptiesEverything() {
        val c = courier()
        c.inject(strangers(fill = 1))
        c.receive(strangers(fill = 2).toBytes())
        c.wipe()
        assertEquals(0, c.size)
        assertEquals(0, c.seenCount)
        assertEquals(0, c.ownCount)
        assertTrue(c.beacon().all { it.toInt() == 0 })
    }
}
