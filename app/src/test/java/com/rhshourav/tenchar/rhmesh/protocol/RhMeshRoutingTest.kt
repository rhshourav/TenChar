package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.MessageLimits
import com.rhshourav.tenchar.crypto.Reassembler
import com.rhshourav.tenchar.crypto.SystemRandom
import com.rhshourav.tenchar.crypto.Wire
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants as K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The whole path with the app's real Double Ratchet: Sender A -> Courier B -> Courier C -> Recipient D, where B and C
 * hold no keys at all, plus clock drift, long messages, the relay-and-mesh double delivery, and a 24-node crowd.
 */
class RhMeshRoutingTest {
    private val t0 = 1_800_000_000_000L
    private val rnd = SystemRandom

    private fun text(r: RhReceive, ratchetOf: Phone): String? {
        val d = r as RhReceive.Delivered
        val frame = ratchetOf.ratchet.decrypt(d.envelope) ?: return null
        d.envelope.fill(0)
        return Wire.decodeText(frame)
    }

    @Test
    fun aMessageCrossesTwoBlindCouriersAndOnlyTheRecipientCanReadIt() {
        var now = t0
        val p = pairPhones()
        val a = RhCourier({ mapOf("d" to p.a.channel) }, { now })
        val b = RhCourier({ emptyMap() }, { now })
        val c = RhCourier({ emptyMap() }, { now })
        val d = RhCourier({ mapOf("a" to p.b.channel) }, { now })

        val envelope = p.a.ratchet.encrypt(Wire.encodeText("see you at 8")!!)
        val packet = RhSealer.seal(envelope, p.a.channel.outbound, now, rnd)
        assertTrue(a.inject(packet))

        now += 30_000
        assertEquals(1, meet(a, b).accepted)
        assertEquals(1, b.packets().single().hops)

        now += 30_000
        assertEquals(1, meet(b, c).accepted)
        assertEquals(2, c.packets().single().hops)

        // D is not met by A or B: only the second courier reaches it.
        now += 30_000
        val last = meet(c, d)
        assertEquals(1, last.accepted)
        assertEquals("see you at 8", text(last.toFirst.plus(last.toSecond).single { it is RhReceive.Delivered }, p.b))
        val delivered = (last.toSecond.single() as RhReceive.Delivered)
        assertEquals(3, delivered.hops)
        assertEquals("a", delivered.channelKey)

        // What the couriers hold is useless to them: no key opens it, and nothing in it names a person or a conversation.
        val stranger = pairPhones()
        for (courier in listOf(b, c)) {
            val held = courier.packets().single()
            assertNull(RhSealer.open(held, stranger.a.channel.inbound))
            assertNull(RhSealer.open(held, stranger.b.channel.inbound))
            assertFalse(held.toBytes().containsSlice(envelope.copyOfRange(1, 33))) // ratchet public key
            assertFalse(held.toBytes().containsSlice("see you".toByteArray()))
            assertEquals(packet.id, held.id) // yet it is recognisably the same packet, which is all dedup needs
        }
        // D has it and keeps nothing; asking again moves nothing, and no one resends it.
        assertEquals(0, d.size)
        assertEquals(0, meet(c, d).offered)
        assertEquals(0, meet(b, d).offered)
        assertEquals(0, meet(a, d).offered)
    }

    @Test
    fun aDifferentPersonOnTheRouteGetsNothingAndStaysAnOrdinaryCourier() {
        val now = t0
        val p = pairPhones()
        val other = pairPhones()
        val a = RhCourier({ mapOf("x" to p.a.channel) }, { now })
        val e = RhCourier({ mapOf("y" to other.b.channel) }, { now }) // has its own chat, not this one
        a.inject(RhSealer.seal(p.a.ratchet.encrypt(Wire.encodeText("private")!!), p.a.channel.outbound, now, rnd))
        val m = meet(a, e)
        assertTrue(m.toSecond.single() is RhReceive.Stored)
        assertEquals(1, e.size) // it will carry it onward, blind
    }

    @Test
    fun theSameMessageViaMeshAndViaRelayIsReadOnce() {
        // The mesh wraps the very envelope the relay would carry, so a phone that gets both just drops the second.
        val now = t0
        val p = pairPhones()
        val d = RhCourier({ mapOf("a" to p.b.channel) }, { now })
        val envelope = p.a.ratchet.encrypt(Wire.encodeText("once")!!)
        val viaMesh = d.receive(RhSealer.seal(envelope, p.a.channel.outbound, now, rnd).toBytes())
        assertEquals("once", text(viaMesh, p.b))
        assertNull(p.b.ratchet.decrypt(envelope)) // the relay copy arrives later: the ratchet has already used its key
        // And the other order: relay first, then mesh.
        val envelope2 = p.a.ratchet.encrypt(Wire.encodeText("twice")!!)
        assertEquals("twice", Wire.decodeText(p.b.ratchet.decrypt(envelope2)!!))
        val late = d.receive(RhSealer.seal(envelope2, p.a.channel.outbound, now, rnd).toBytes())
        assertTrue(late is RhReceive.Delivered)
        assertNull(p.b.ratchet.decrypt((late as RhReceive.Delivered).envelope))
    }

    @Test
    fun aFiveHundredCharacterMessageCrossesTheMeshInPiecesAndComesBackWhole() {
        var now = t0
        val p = pairPhones()
        val a = RhCourier({ emptyMap() }, { now })
        val b = RhCourier({ emptyMap() }, { now })
        val d = RhCourier({ mapOf("a" to p.b.channel) }, { now })
        val message = ("word " .repeat(100)).trim()
        val frames = MessageLimits.split(message).flatMapIndexed { n, piece -> Wire.encodeMessage(piece, 100 + n)!! }
        assertEquals(9, frames.size)
        val packets = frames.map { RhSealer.seal(p.a.ratchet.encrypt(it), p.a.channel.outbound, now, rnd) }
        packets.forEach { assertTrue(a.inject(it)) }
        assertEquals(setOf(192), packets.map { it.toBytes().size }.toSet())

        // A meets B briefly: only some of the pieces make it (a 3-second encounter). Then B meets D.
        now += 5_000
        val firstMeeting = a.plan(RhBloom.decode(b.bloom(1).encode()), 5)
        firstMeeting.forEach { b.receive(it.toBytes()) }
        assertEquals(5, b.size)
        now += 60_000
        val deliveries = ArrayList<ByteArray>()
        fun collect(m: Meeting) = (m.toFirst + m.toSecond).filterIsInstance<RhReceive.Delivered>().forEach { deliveries += it.envelope }
        collect(meet(b, d)) // D gets 5 of 9 and cannot read the whole message yet
        assertEquals(5, deliveries.size)
        // Later A meets D directly and the rest follow; the ones D already has are not sent again.
        now += 60_000
        val rest = meet(a, d)
        assertEquals(4, rest.offered)
        collect(rest)
        // All nine have reached D, in whatever order they came: reassemble them.
        val reassembler = Reassembler()
        val results = ArrayList<String>()
        assertEquals(9, deliveries.size)
        for (envelope in deliveries) {
            val frame = p.b.ratchet.decrypt(envelope)!!
            when (val decoded = Wire.decodeFrame(frame)) {
                is Wire.Decoded.Fragment -> reassembler.accept(decoded)?.let(results::add)
                is Wire.Decoded.Text -> results.add(decoded.text)
                else -> Unit
            }
        }
        assertEquals(MessageLimits.split(message), results)
    }

    // ---- clocks ---------------------------------------------------------------------------------------------------

    @Test
    fun aSlowCouriersClockIsForgivenByTheRecipientButOnlyBriefly() {
        var trueNow = t0
        val p = pairPhones()
        val sender = RhCourier({ emptyMap() }, { trueNow })
        val slowCourier = RhCourier({ emptyMap() }, { trueNow - 90_000 }) // 90 s behind
        val recipient = RhCourier({ mapOf("a" to p.b.channel) }, { trueNow })
        sender.inject(RhSealer.seal(p.a.ratchet.encrypt(Wire.encodeText("late")!!), p.a.channel.outbound, trueNow, rnd))
        trueNow += 10_000
        meet(sender, slowCourier)
        // 61 minutes later by the true clock: the courier's own clock says 59:30, so it still offers the packet, and the
        // recipient (age 61:00, inside the 2-minute grace) still opens it.
        trueNow = t0 + 61 * 60_000L
        val m = meet(slowCourier, recipient)
        assertEquals(1, m.offered)
        assertTrue(m.toSecond.single() is RhReceive.Delivered)
    }

    @Test
    fun theRecipientStopsAcceptingAtTheEndOfTheGraceWindow() {
        var now = t0
        val p = pairPhones()
        val recipient = RhCourier({ mapOf("a" to p.b.channel) }, { now })
        val raw = RhSealer.seal(fakeEnvelope(), p.a.channel.outbound, t0, rnd).toBytes()
        now = t0 + K.TTL_MS + K.ACCEPT_GRACE_MS - 1
        assertTrue(RhPacket.parse(raw) != null && RhAdmission.judge(RhPacket.parse(raw)!!, now) == RhVerdict.DELIVER_ONLY)
        now = t0 + K.TTL_MS + K.ACCEPT_GRACE_MS
        assertEquals(RhDropReason.EXPIRED, (recipient.receive(raw) as RhReceive.Dropped).reason)
    }

    @Test
    fun aPacketDatedInTheFutureIsRefusedOnceItIsBeyondTheSkew() {
        val now = t0
        val c = RhCourier({ emptyMap() }, { now })
        val ok = RhSealer.seal(fakeEnvelope(1), randomInbox(), now + K.FUTURE_SKEW_MS - 1_000, rnd)
        val bad = RhSealer.seal(fakeEnvelope(2), randomInbox(), now + K.FUTURE_SKEW_MS + 1_000, rnd)
        assertTrue(c.receive(ok.toBytes()) is RhReceive.Stored)
        assertEquals(RhDropReason.FROM_FUTURE, (c.receive(bad.toBytes()) as RhReceive.Dropped).reason)
    }

    // ---- a crowd -------------------------------------------------------------------------------------------------------

    private class Crowd(val p: PairedPhones, val nodes: List<RhCourier>, val clock: LongArray) {
        var transmitted = 0
        var accepted = 0
        var deliveredText: String? = null
    }

    /** 24 nodes. Node 0 sends to node 23; the other 22 are strangers. Every 10 s, [pairsPerStep] random pairs meet. */
    private fun crowd(startAfterMs: Long, steps: Int, pairsPerStep: Int, seed: Long): Crowd {
        val clock = longArrayOf(t0)
        val p = pairPhones()
        val nodes = List(24) { i ->
            when (i) {
                0 -> RhCourier({ mapOf("d" to p.a.channel) }, { clock[0] })
                23 -> RhCourier({ mapOf("a" to p.b.channel) }, { clock[0] })
                else -> RhCourier({ emptyMap() }, { clock[0] })
            }
        }
        val world = Crowd(p, nodes, clock)
        nodes[0].inject(RhSealer.seal(p.a.ratchet.encrypt(Wire.encodeText("anyone near the station?")!!), p.a.channel.outbound, t0, rnd))
        val random = Random(seed)
        for (step in 0 until steps) {
            clock[0] = t0 + startAfterMs + step * 10_000L
            repeat(pairsPerStep) {
                val i = random.nextInt(24)
                var j = random.nextInt(23)
                if (j >= i) j++
                val m = meet(nodes[i], nodes[j])
                world.transmitted += m.offered
                world.accepted += m.accepted
                for (r in m.toFirst + m.toSecond) {
                    if (r is RhReceive.Delivered) world.deliveredText = Wire.decodeText(p.b.ratchet.decrypt(r.envelope)!!)
                }
            }
        }
        return world
    }

    @Test
    fun withinTheHourACrowdDeliversItWithoutWastingATransmission() {
        val w = crowd(startAfterMs = 0, steps = 360, pairsPerStep = 6, seed = 42)
        assertEquals("anyone near the station?", w.deliveredText)
        // Every transmission was of a packet the peer lacked, so every one was accepted: nothing sent twice.
        assertEquals(w.transmitted, w.accepted)
        assertTrue("at most one acceptance per node: ${w.accepted}", w.accepted <= 23)
        println("crowd of 24: ${w.accepted} transfers delivered it (flooding blindly would have been ~${360 * 6 * 2})")
        // An hour on, the whole mesh has forgotten it.
        w.clock[0] = t0 + K.TTL_MS + K.ACCEPT_GRACE_MS
        w.nodes.forEach { it.purgeExpired() }
        assertTrue(w.nodes.all { it.size == 0 && it.seenCount == 0 })
    }

    @Test
    fun nothingIsDeliveredOnceTheHourHasPassedEvenInAThickCrowd() {
        val w = crowd(startAfterMs = K.TTL_MS + 1_000, steps = 360, pairsPerStep = 6, seed = 42)
        assertNull(w.deliveredText)
        assertEquals(0, w.transmitted)
    }

    @Test
    fun aSparseCrowdMayNotDeliverInTimeAndThatIsAHonestOutcome() {
        // One chance meeting every 10 s among 24 people for an hour is thin but usually enough; with a single meeting
        // per minute it is not. The protocol promises "within an hour or not at all", never "always".
        val w = crowd(startAfterMs = 0, steps = 60, pairsPerStep = 1, seed = 5)
        if (w.deliveredText == null) assertTrue(w.accepted < 23) else assertEquals("anyone near the station?", w.deliveredText)
        assertNotNull(w.nodes)
    }
}
