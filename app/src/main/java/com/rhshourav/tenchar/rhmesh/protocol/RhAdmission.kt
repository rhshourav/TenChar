package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.MAX_HOPS

/** What a node decides about a packet it has just been handed. */
enum class RhVerdict {
    /** Keep it, check it against my own conversations, and offer it onward. */
    CARRY,

    /** Check it against my own conversations, but do not keep it: it is in the recipient-only grace window, or at its last hop. */
    DELIVER_ONLY,

    EXPIRED,
    FROM_FUTURE,

    /** A compliant node never sends a packet that has already used its last hop. */
    HOP_LIMIT,
}

object RhAdmission {
    /**
     * [packet] is as received (its [RhPacket.hops] is what the sender had). On [RhVerdict.CARRY] store
     * `packet.withHopsIncremented()`.
     */
    fun judge(packet: RhPacket, nowMs: Long): RhVerdict {
        val created = packet.createdAtSec
        if (RhTtl.isFromFuture(created, nowMs)) return RhVerdict.FROM_FUTURE
        if (!RhTtl.shouldAccept(created, nowMs)) return RhVerdict.EXPIRED
        if (packet.hops >= MAX_HOPS) return RhVerdict.HOP_LIMIT
        // After this node stores it, the copy has hops + 1 and may only be passed on if that is still below the limit.
        val forwardable = packet.hops + 1 < MAX_HOPS
        return if (forwardable && RhTtl.shouldCarry(created, nowMs)) RhVerdict.CARRY else RhVerdict.DELIVER_ONLY
    }
}

object RhSyncPlanner {
    /**
     * The packets to offer a peer whose filter is [peerFilter]: those I may still pass on, within their hour, that the
     * filter says the peer lacks. Fewest hops first (the least spread), then newest (the most life left), so a short
     * meeting spends its seconds where they help most. At most [maxPackets]. A null filter means "send what I can".
     *
     * [ownOnly] is for a node with Courier Mode off: it offers only the ids in [own] and carries nothing for strangers.
     */
    fun plan(
        mine: Collection<RhPacket>,
        peerFilter: RhBloom?,
        nowMs: Long,
        maxPackets: Int,
        ownOnly: Boolean = false,
        own: Set<PacketId> = emptySet(),
    ): List<RhPacket> {
        require(maxPackets >= 0) { "maxPackets must not be negative" }
        return mine.asSequence()
            .filter { it.canForward && RhTtl.shouldCarry(it.createdAtSec, nowMs) }
            .filter { !ownOnly || it.id in own }
            .filter { peerFilter == null || !peerFilter.mightContain(it.id) }
            .sortedWith(compareBy<RhPacket> { it.hops }.thenByDescending { it.createdAtSec }.thenBy { it.id.raw })
            .take(maxPackets)
            .toList()
    }
}
