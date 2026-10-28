package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.crypto.Ratchet

class StoredMessage(val id: Long, val text: String, val mine: Boolean, var delivery: Int)

/** An envelope that is already encrypted (the ratchet has moved on) but not yet accepted by a relay. */
class OutboxItem(val msgId: Long, val envelope: ByteArray)

/**
 * Everything the app remembers about the one conversation it can hold.
 * It exists only in memory while unlocked and on disk only inside [Vault].
 */
class Conversation(
    /** 32 hex characters, derived from the pairing code. */
    val room: String,
    val memberId: String,
    /** 64 hex characters. The relay only ever sees its hash until the first request. */
    val memberToken: String,
    /** Relays that carry this conversation, in order of preference. */
    val endpoints: List<String>,
    val ratchet: Ratchet,
    /** "dddd dddd" */
    val verification: String,
    var verified: Boolean,
    /** Highest relay message id already handled, per relay. */
    val lastIds: MutableMap<String, Long> = LinkedHashMap(),
    val outbox: MutableList<OutboxItem> = ArrayList(),
    val messages: MutableList<StoredMessage> = ArrayList(),
    var nextMsgId: Long = 1L,
) {
    /** Overwrites key material. Strings (the member token) cannot be overwritten on the JVM. */
    fun wipe() {
        ratchet.wipe()
        for (item in outbox) item.envelope.fill(0)
    }

    companion object {
        const val DELIVERY_NONE = 0
        const val DELIVERY_QUEUED = 1
        const val DELIVERY_SENT = 2

        /** Older messages are dropped from the phone beyond this. */
        const val MAX_MESSAGES = 1000
        const val MAX_ENDPOINTS = 4
    }
}
