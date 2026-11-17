package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.ACCEPT_GRACE_MS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.DEFAULT_CACHE_PACKETS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.SEEN_PER_CACHE_SLOT
import java.util.TreeSet

enum class RhDropReason {
    MALFORMED,
    EXPIRED,
    FROM_FUTURE,
    HOP_LIMIT,
    FORGED,
    STALE,

    /** Not mine, and it may not be passed on (last hop, or past its hour on a courier's clock). */
    NOT_FORWARDABLE,

    /** Not mine, and Courier Mode is off. */
    COURIER_OFF,

    /** Not mine, and every slot holds something that lives longer or is my own. */
    CACHE_FULL,
}

sealed interface RhReceive {
    /** A stranger's packet, now carried by this node. */
    class Stored(val id: PacketId) : RhReceive

    /** Mine. [envelope] goes to the conversation's ratchet; wipe it afterwards. [hops] is how many transfers it took. */
    class Delivered(val id: PacketId, val channelKey: String, val envelope: ByteArray, val hops: Int) : RhReceive

    data object Duplicate : RhReceive

    class Dropped(val reason: RhDropReason) : RhReceive
}

/**
 * Everything one node decides about packets, in memory and with no I/O: what to accept, keep, offer, evict and forget.
 * Phase 3 persists and encrypts its contents; Phase 2 feeds it from the radios. Because it takes its clock and its keys as
 * parameters, a whole mesh of them can be run in a unit test.
 *
 * What it holds:
 *  - the cache: packets this node will offer to others (its own outgoing ones, and strangers' if [carryForOthers]);
 *  - the seen set: ids of everything it has handled, held or not (delivered to me, forged, evicted, refused). That set is
 *    what the Bloom filter describes, so a peer never re-sends something this node has already dealt with.
 *
 * Not thread-safe: serialise every call through one Mutex or actor.
 */
class RhCourier(
    private val channels: () -> Map<String, MeshChannel>,
    private val clock: () -> Long,
    maxPackets: Int = DEFAULT_CACHE_PACKETS,
    var carryForOthers: Boolean = true,
) {
    var maxPackets: Int = maxPackets
        set(value) {
            require(value >= 0) { "maxPackets must not be negative" }
            field = value
            while (cache.size > value) evictOne(preferNonOwn = true)
        }

    private val cache = HashMap<PacketId, RhPacket>()
    private val byExpiry = TreeSet<RhPacket>(compareBy<RhPacket> { it.expiresAtMs }.thenBy { it.id.raw })
    private val own = HashSet<PacketId>()
    private val seen = LinkedHashMap<PacketId, Long>()
    private val fingerprint = RhSetFingerprint()

    val size: Int get() = cache.size
    val ownCount: Int get() = own.size
    val seenCount: Int get() = seen.size

    fun holds(id: PacketId): Boolean = cache.containsKey(id)

    fun isOwn(id: PacketId): Boolean = id in own

    /** A snapshot of what this node would offer. */
    fun packets(): List<RhPacket> = cache.values.toList()

    // ---- sending ----------------------------------------------------------------------------------------------------

    /** Puts a packet this phone just sealed into the cache. False if it was already known. Own packets are evicted last. */
    fun inject(packet: RhPacket): Boolean {
        require(packet.hops == 0) { "a new packet starts at zero hops" }
        if (seen.containsKey(packet.id)) return false
        markSeen(packet)
        own += packet.id
        if (!store(packet, isOwn = true)) {
            own -= packet.id
            return false
        }
        return true
    }

    // ---- receiving --------------------------------------------------------------------------------------------------

    fun receive(raw: ByteArray): RhReceive {
        val now = clock()
        val packet = RhPacket.parse(raw) ?: return RhReceive.Dropped(RhDropReason.MALFORMED)
        val id = packet.id
        if (seen.containsKey(id)) return RhReceive.Duplicate

        val verdict = RhAdmission.judge(packet, now)
        when (verdict) {
            RhVerdict.EXPIRED -> return RhReceive.Dropped(RhDropReason.EXPIRED)
            RhVerdict.FROM_FUTURE -> return RhReceive.Dropped(RhDropReason.FROM_FUTURE)
            RhVerdict.HOP_LIMIT -> return RhReceive.Dropped(RhDropReason.HOP_LIMIT)
            RhVerdict.CARRY, RhVerdict.DELIVER_ONLY -> Unit
        }

        when (val open = RhInbox.open(packet, now, channels())) {
            is RhOpen.Delivered -> {
                markSeen(packet)
                return RhReceive.Delivered(id, open.channelKey, open.envelope, packet.hops + 1)
            }
            RhOpen.Forged -> {
                markSeen(packet)
                return RhReceive.Dropped(RhDropReason.FORGED)
            }
            RhOpen.Stale -> return RhReceive.Dropped(RhDropReason.STALE)
            RhOpen.NotForMe -> Unit
        }

        markSeen(packet)
        if (verdict != RhVerdict.CARRY) return RhReceive.Dropped(RhDropReason.NOT_FORWARDABLE)
        if (!carryForOthers) return RhReceive.Dropped(RhDropReason.COURIER_OFF)
        return if (store(packet.withHopsIncremented(), isOwn = false)) {
            RhReceive.Stored(id)
        } else {
            RhReceive.Dropped(RhDropReason.CACHE_FULL)
        }
    }

    // ---- meeting a peer ---------------------------------------------------------------------------------------------

    /** Six bytes for a BLE beacon: how many packets are cached and a hash of which. Equal on both sides means nothing to swap. */
    fun beacon(): ByteArray = fingerprint.beaconBytes()

    /** "Here is everything I have already handled", with a fresh [salt]. Send it to the peer you are about to sync with. */
    fun bloom(salt: Int): RhBloom {
        val now = clock()
        val live = ArrayList<PacketId>(seen.size)
        for ((id, until) in seen) if (until > now) live += id
        return RhBloom.of(live, salt)
    }

    /**
     * What to offer a peer that sent [peerFilter]: at most [maxPackets], those it lacks, fewest hops first. A filter fuller
     * than an honest node would build is not believed and gets nothing this time.
     */
    fun plan(peerFilter: RhBloom?, maxPackets: Int): List<RhPacket> {
        if (peerFilter != null && !peerFilter.isPlausible()) return emptyList()
        return RhSyncPlanner.plan(cache.values, peerFilter, clock(), maxPackets, ownOnly = !carryForOthers, own = own)
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------------

    /** Forgets every packet whose hour is over, and every seen id past its grace. Returns how many packets were dropped. */
    fun purgeExpired(): Int {
        val now = clock()
        var dropped = 0
        while (true) {
            val first = byExpiry.firstOrNull() ?: break
            if (first.expiresAtMs > now) break
            remove(first)
            dropped++
        }
        val it = seen.entries.iterator()
        while (it.hasNext()) if (it.next().value <= now) it.remove()
        return dropped
    }

    fun wipe() {
        cache.clear()
        byExpiry.clear()
        own.clear()
        seen.clear()
        fingerprint.clear()
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    private fun markSeen(packet: RhPacket) {
        seen[packet.id] = packet.expiresAtMs + ACCEPT_GRACE_MS
        val limit = maxOf(1, maxPackets) * SEEN_PER_CACHE_SLOT
        while (seen.size > limit) {
            val eldest = seen.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    /** Makes room if needed and keeps [copy]. False if it was not worth evicting for. */
    private fun store(copy: RhPacket, isOwn: Boolean): Boolean {
        if (maxPackets <= 0) return false
        if (cache.size >= maxPackets) {
            val victim = byExpiry.firstOrNull { it.id !in own }
            when {
                // Make room for a stranger's packet only by dropping one that dies sooner.
                !isOwn && victim != null && victim.expiresAtMs <= copy.expiresAtMs -> remove(victim)
                !isOwn -> return false
                // My own outgoing packet always gets in: drop a stranger's first, then my oldest.
                victim != null -> remove(victim)
                else -> remove(byExpiry.first())
            }
        }
        cache[copy.id] = copy
        byExpiry += copy
        fingerprint.add(copy.id)
        return true
    }

    private fun evictOne(preferNonOwn: Boolean) {
        val victim = (if (preferNonOwn) byExpiry.firstOrNull { it.id !in own } else null) ?: byExpiry.firstOrNull() ?: return
        remove(victim)
    }

    private fun remove(packet: RhPacket) {
        if (cache.remove(packet.id) != null) {
            byExpiry.remove(packet)
            own.remove(packet.id)
            fingerprint.remove(packet.id)
        }
    }
}
