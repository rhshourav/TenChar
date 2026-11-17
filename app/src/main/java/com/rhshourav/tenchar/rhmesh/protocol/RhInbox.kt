package com.rhshourav.tenchar.rhmesh.protocol

/** The result of checking one packet against this phone's own conversations. */
sealed interface RhOpen {
    /** Addressed to none of my conversations. Only a courier cares about it. */
    data object NotForMe : RhOpen

    /** Addressed to me but too old (or from the future) to open. */
    data object Stale : RhOpen

    /** The tag says it is mine but it does not authenticate: forged or altered. Never pass it on. */
    data object Forged : RhOpen

    /** Mine. [envelope] is the 135-byte ratchet envelope for [channelKey]'s conversation; the caller wipes it after use. */
    class Delivered(val channelKey: String, val envelope: ByteArray) : RhOpen
}

object RhInbox {
    /** [channels] maps the app's conversation id to its mesh keys. */
    fun open(packet: RhPacket, nowMs: Long, channels: Map<String, MeshChannel>): RhOpen {
        var addressedTo: String? = null
        for ((key, channel) in channels) {
            if (RhSealer.isAddressedTo(packet, channel.inbound)) {
                addressedTo = key
                break
            }
        }
        val key = addressedTo ?: return RhOpen.NotForMe
        if (!RhTtl.shouldAccept(packet.createdAtSec, nowMs)) return RhOpen.Stale
        val envelope = RhSealer.open(packet, channels.getValue(key).inbound) ?: return RhOpen.Forged
        return RhOpen.Delivered(key, envelope)
    }
}
