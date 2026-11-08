package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants

/**
 * Puts the pieces of a long message back together. Fragments arrive in any order (the ratchet allows
 * out-of-order delivery) and are kept until the last one lands, then the whole message is returned once.
 *
 * Every fragment has already been acknowledged to the relay and its ratchet key is gone, so the pieces
 * received so far are saved with the conversation: locking the app between two fragments must not lose the message.
 * A message that never completes (the relay dropped a piece) is eventually pushed out by newer ones.
 */
class Reassembler {
    /** The pieces of one message. [parts] has [count] slots, empty until that piece arrives. */
    class Group(val id: Int, val count: Int, val parts: Array<ByteArray?>)

    private val groups = LinkedHashMap<Int, Group>()

    val pending: List<Group> get() = groups.values.toList()

    /** The finished message once [fragment] completes it, otherwise null. Bad or repeated pieces are ignored. */
    fun accept(fragment: Wire.Decoded.Fragment): String? {
        var group = groups[fragment.group]
        if (group == null) {
            while (groups.size >= Constants.MAX_PENDING_MESSAGES) {
                val oldest = groups.keys.first()
                groups.remove(oldest)
            }
            group = Group(fragment.group, fragment.count, arrayOfNulls(fragment.count))
            groups[fragment.group] = group
        }
        if (group.count != fragment.count) return null // disagrees with what this group already is
        if (group.parts[fragment.index] != null) return null // a piece already received
        group.parts[fragment.index] = fragment.data.copyOf()
        if (group.parts.any { it == null }) return null

        groups.remove(group.id)
        val total = group.parts.sumOf { it!!.size }
        if (total > Constants.MAX_MESSAGE_BYTES) return null
        val joined = ByteArray(total)
        var at = 0
        for (part in group.parts) {
            part!!.copyInto(joined, at)
            at += part.size
        }
        return Wire.validText(joined, 0, joined.size)
    }

    /** Restores pieces saved earlier. Groups that do not make sense are skipped. */
    fun restore(saved: List<Group>) {
        groups.clear()
        for (g in saved.takeLast(Constants.MAX_PENDING_MESSAGES)) {
            if (g.count in 2..Constants.MAX_FRAGMENTS && g.parts.size == g.count) groups[g.id] = g
        }
    }

    fun wipe() {
        for (g in groups.values) for (p in g.parts) p?.fill(0)
        groups.clear()
    }
}