package com.rhshourav.tenchar.net

import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.crypto.Handshake
import com.rhshourav.tenchar.crypto.LocalMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class PeerKeys(val memberId: String, val ik: String, val ek: String)

enum class FailReason { Timeout, Unreachable, Tampered, Full }

sealed interface PairOutcome {
    /** [endpoints] are the relays that carry the conversation: every relay this phone joined. */
    class Success(val peer: PeerKeys, val endpoints: List<String>) : PairOutcome

    class Failure(val reason: FailReason) : PairOutcome
}

/**
 * The relay-side of pairing, run on every configured relay at once.
 *
 *  1. hello: join the room with a commitment to this phone's public keys.
 *  2. wait until the other phone's commitment is there too.
 *  3. reveal this phone's keys. The relay refuses to accept a reveal before
 *     both commitments exist, and refuses one that does not match.
 *  4. wait for the other phone's keys and check them against its commitment.
 *
 * A relay is not trusted. If two relays both complete, they must agree on the
 * other phone's keys, which means one dishonest relay cannot swap them unnoticed.
 */
class PairingFlow(
    private val endpoints: List<String>,
    private val room: String,
    private val codeToken: String,
    private val me: LocalMember,
    private val clientFor: (String) -> RelayClient,
    private val timeoutMs: Long,
) {
    private val joined = ConcurrentHashMap.newKeySet<String>()

    private sealed interface EndpointResult {
        class Sealed(val peer: PeerKeys) : EndpointResult
        class Failed(val reason: FailReason) : EndpointResult
    }

    suspend fun run(): PairOutcome {
        // A blocked HTTP call cannot be interrupted, so the per-relay jobs live in their own
        // scope and are not waited for: a slow relay must never hold up the result.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            return runAll(scope)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun runAll(scope: CoroutineScope): PairOutcome {
        val deadline = now() + timeoutMs
        val results = Channel<Pair<String, EndpointResult>>(endpoints.size)
        endpoints.forEach { ep -> scope.launch { results.send(ep to runEndpoint(ep, deadline)) } }

        val done = ArrayList<Pair<String, EndpointResult>>()
        var graceEnds: Long? = null
        while (done.size < endpoints.size) {
            val item = if (graceEnds == null) {
                results.receive()
            } else {
                withTimeoutOrNull((graceEnds - now()).coerceAtLeast(0)) { results.receive() } ?: break
            }
            done += item
            if (item.second is EndpointResult.Sealed && graceEnds == null) graceEnds = now() + GRACE_MS
        }

        val sealed = done.mapNotNull { (ep, r) -> (r as? EndpointResult.Sealed)?.let { ep to it.peer } }
        if (sealed.isEmpty()) {
            val reasons = done.mapNotNull { (it.second as? EndpointResult.Failed)?.reason }
            val reason = listOf(FailReason.Tampered, FailReason.Full, FailReason.Timeout, FailReason.Unreachable)
                .firstOrNull { it in reasons } ?: FailReason.Timeout
            return PairOutcome.Failure(reason)
        }
        val first = sealed.first().second
        val agree = sealed.all {
            it.second.memberId == first.memberId && it.second.ik == first.ik && it.second.ek == first.ek
        }
        if (!agree) return PairOutcome.Failure(FailReason.Tampered)

        // Keep every relay we joined, sealed or not: the other phone may have finished there a moment
        // later, and a message it posts there must not be missed. An unsealed room just answers
        // "not sealed" and expires on its own.
        val carrying = endpoints.filter { it in joined || sealed.any { s -> s.first == it } }
        return PairOutcome.Success(first, carrying)
    }

    private suspend fun runEndpoint(endpoint: String, deadline: Long): EndpointResult {
        val client = clientFor(endpoint)

        // 1. hello, tolerating a cold-starting relay for a while.
        val helloDeadline = minOf(deadline, now() + HELLO_BUDGET_MS)
        while (true) {
            when (val r = client.hello(room, codeToken, me.memberId, me.tokenHash, me.commit)) {
                is Reply.Ok -> break
                is Reply.Rejected -> return EndpointResult.Failed(
                    if (r.status == 409 || r.status == 410) FailReason.Full else FailReason.Unreachable,
                )
                Reply.Unreachable -> {
                    if (now() >= helloDeadline) return EndpointResult.Failed(FailReason.Unreachable)
                    delay(RETRY_MS)
                }
            }
        }
        joined.add(endpoint)

        // 2-4. wait for the other phone, reveal, wait for its reveal.
        var didReveal = false
        while (now() < deadline) {
            when (val s = client.state(room, me.memberToken)) {
                is Reply.Ok -> {
                    val peer = s.value.members.firstOrNull { it.id != me.memberId }
                    if (peer != null && s.value.members.size == 2) {
                        if (!didReveal) {
                            val r = client.reveal(room, me.memberToken, me.memberId, Hex.encode(me.keys.ik.pub), Hex.encode(me.keys.ek.pub))
                            if (r is Reply.Ok) {
                                didReveal = true
                            } else if (r is Reply.Rejected && r.status == 400) {
                                return EndpointResult.Failed(FailReason.Tampered)
                            }
                        }
                        val ik = peer.ik
                        val ek = peer.ek
                        if (didReveal && ik != null && ek != null) {
                            val expected = Hex.encode(Handshake.commit(Hex.decode(ik), Hex.decode(ek), Hex.decode(peer.id)))
                            if (expected != peer.commit) return EndpointResult.Failed(FailReason.Tampered)
                            return EndpointResult.Sealed(PeerKeys(peer.id, ik, ek))
                        }
                    }
                }
                is Reply.Rejected -> if (s.status == 404 || s.status == 401) return EndpointResult.Failed(FailReason.Unreachable)
                Reply.Unreachable -> Unit
            }
            delay(POLL_MS)
        }
        return EndpointResult.Failed(FailReason.Timeout)
    }

    /** Deletes the rooms this phone joined. Used when pairing is abandoned. Best effort. */
    suspend fun cleanup() {
        withContext(NonCancellable) {
            for (ep in joined.toList()) {
                try {
                    clientFor(ep).delete(room, me.memberToken)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best effort: the relay deletes unused rooms after 24 hours anyway.
                }
            }
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val POLL_MS = 2_000L
        const val RETRY_MS = 3_000L
        const val HELLO_BUDGET_MS = 60_000L
        const val GRACE_MS = 15_000L
    }
}
