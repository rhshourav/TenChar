package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.ACCEPT_GRACE_MS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.FUTURE_SKEW_MS
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.TTL_MS

/**
 * The one-hour life of a packet, as pure functions of the creation time and "now" (both on the caller's own clock).
 *
 * Three rules, and the reasons for them:
 *  - A courier keeps a packet only while age < exactly [TTL_MS]. No grace: the promise is "gone after an hour".
 *  - A packet that claims to come from more than [FUTURE_SKEW_MS] in the future is refused. Without this a sender could
 *    stamp a date next year and make a packet immortal. With it, no packet lives longer than TTL + skew on any honest clock.
 *  - Only the recipient forgives a further [ACCEPT_GRACE_MS], because a courier whose clock runs a little behind may hand
 *    over a packet that is, by the recipient's clock, a moment past its hour. The recipient never passes it on.
 */
object RhTtl {
    fun createdAtMs(createdAtSec: Long): Long = createdAtSec * 1000L

    fun expiresAtMs(createdAtSec: Long): Long = createdAtMs(createdAtSec) + TTL_MS

    fun ageMs(createdAtSec: Long, nowMs: Long): Long = nowMs - createdAtMs(createdAtSec)

    fun isExpired(createdAtSec: Long, nowMs: Long): Boolean = ageMs(createdAtSec, nowMs) >= TTL_MS

    fun isFromFuture(createdAtSec: Long, nowMs: Long): Boolean = ageMs(createdAtSec, nowMs) < -FUTURE_SKEW_MS

    /** May this node keep the packet and offer it to others? */
    fun shouldCarry(createdAtSec: Long, nowMs: Long): Boolean = ageMs(createdAtSec, nowMs) in -FUTURE_SKEW_MS until TTL_MS

    /** May this node, as the recipient, still open the packet? */
    fun shouldAccept(createdAtSec: Long, nowMs: Long): Boolean =
        ageMs(createdAtSec, nowMs) in -FUTURE_SKEW_MS until TTL_MS + ACCEPT_GRACE_MS

    /** Time left, 0 to [TTL_MS], for the countdown ring. */
    fun remainingMs(createdAtSec: Long, nowMs: Long): Long = (expiresAtMs(createdAtSec) - nowMs).coerceIn(0L, TTL_MS)

    /** 1.0 when new, 0.0 when gone. */
    fun remainingFraction(createdAtSec: Long, nowMs: Long): Float = remainingMs(createdAtSec, nowMs).toFloat() / TTL_MS
}
