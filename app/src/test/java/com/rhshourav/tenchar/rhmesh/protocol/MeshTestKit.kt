package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import com.rhshourav.tenchar.crypto.RandomSource
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.SystemRandom

/** One phone of a paired conversation: its ratchet (the existing Double Ratchet) and its mesh keys. */
internal class Phone(val memberId: String, val ratchet: Ratchet, val channel: MeshChannel)

internal class PairedPhones(val a: Phone, val b: Phone)

/** Two phones paired exactly as the app pairs them, plus a mesh secret they both hold. */
internal fun pairPhones(rnd: RandomSource = SystemRandom): PairedPhones {
    val ma = LocalMember.create(rnd)
    val mb = LocalMember.create(rnd)
    val codeKey = ByteArray(32).also(rnd::nextBytes)
    val ga = Handshake.agree(ma.keys, ma.memberId, mb.keys.ik.pub, mb.keys.ek.pub, mb.memberId, codeKey)
    val gb = Handshake.agree(mb.keys, mb.memberId, ma.keys.ik.pub, ma.keys.ek.pub, ma.memberId, codeKey)
    fun build(me: LocalMember, peer: LocalMember, g: Handshake.Agreement) =
        if (g.initiator) Ratchet.forInitiator(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
        else Ratchet.forResponder(g.sk, g.ad, me.keys.ek, peer.keys.ek.pub, rnd)
    val secret = ByteArray(32).also(rnd::nextBytes)
    return PairedPhones(
        Phone(ma.memberId, build(ma, mb, ga), MeshChannel.derive(secret, ma.memberId, mb.memberId)),
        Phone(mb.memberId, build(mb, ma, gb), MeshChannel.derive(secret, mb.memberId, ma.memberId)),
    )
}

internal fun randomInbox(rnd: RandomSource = SystemRandom): RhInboxKeys =
    RhInboxKeys.derive(ByteArray(32).also(rnd::nextBytes), ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) })

internal fun fakeEnvelope(fill: Int = 7): ByteArray = ByteArray(135) { (it + fill).toByte() }

/** What one side got from a meeting. */
internal class Meeting(val toFirst: List<RhReceive>, val toSecond: List<RhReceive>, val offeredToFirst: Int, val offeredToSecond: Int) {
    val offered: Int get() = offeredToFirst + offeredToSecond
    val accepted: Int get() = (toFirst + toSecond).count { it is RhReceive.Stored || it is RhReceive.Delivered }
}

private var saltCounter = 1

/**
 * What the radios will do in Phase 2, with the wire left out: each side sends a fresh-salted Bloom filter, each plans
 * what the other lacks, then both deliver. Both plans are made before either delivers, as they would be over a link.
 */
internal fun meet(x: RhCourier, y: RhCourier, budget: Int = 10_000): Meeting {
    val fx = RhBloom.decode(x.bloom(saltCounter++).encode())!!
    val fy = RhBloom.decode(y.bloom(saltCounter++).encode())!!
    val toY = x.plan(fy, budget)
    val toX = y.plan(fx, budget)
    val gotY = toY.map { y.receive(it.toBytes()) }
    val gotX = toX.map { x.receive(it.toBytes()) }
    return Meeting(gotX, gotY, toX.size, toY.size)
}

internal fun ByteArray.containsSlice(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return true
    }
    return false
}
