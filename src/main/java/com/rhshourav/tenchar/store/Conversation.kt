package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.crypto.RandomSource
import com.rhshourav.tenchar.crypto.Ratchet
import com.rhshourav.tenchar.crypto.Reassembler

/**
 * One message. [text] is empty when there is an [attachment]. [at] is when this phone sent or received it (milliseconds
 * since 1970), kept only here and never sent; 0 for messages saved by an older version.
 */
class StoredMessage(
    val id: Long,
    val text: String,
    val mine: Boolean,
    var delivery: Int,
    val at: Long = 0L,
    val attachment: Attachment? = null,
    /** Set if this message answers an earlier one. */
    val reply: ReplyRef? = null,
)

/** An envelope that is already encrypted (the ratchet has moved on) but not yet accepted by a relay. */
class OutboxItem(val msgId: Long, val envelope: ByteArray)

// A message longer than one frame is several outbox items with the same msgId; it counts as sent when the last one is.

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
    /** Pieces of long messages that have arrived but are not yet complete. Saved with the conversation. */
    val reassembler: Reassembler = Reassembler(),
    /** Names this chat inside the app. Never sent anywhere. */
    val localId: String = newLocalId(com.rhshourav.tenchar.crypto.SystemRandom),
    /** How you appear to the other person. Sent to them through the encrypted channel. */
    var me: Profile = Profile.EMPTY,
    /** How the other person says they appear. Arrives through the encrypted channel. */
    var themShared: Profile? = null,
    /** How you chose to see them on this phone. Overrides [themShared] and is never sent. */
    var themLocal: Profile? = null,
    /**
     * The secret that lets the alert service ask the relays how many messages wait for this chat (it opens nothing else),
     * as 64 hex characters, or null when alerts are off. The relays only ever see its hash.
     */
    var watchToken: String? = null,
    /** Messages that arrived while this chat was not open. */
    var unread: Int = 0,
) {
    /** What to show for the other person: your own choice, else what they shared, else nothing. */
    val them: Profile get() = themLocal ?: themShared ?: Profile.EMPTY

    /** Overwrites key material. Strings (the member token) cannot be overwritten on the JVM. */
    fun wipe() {
        ratchet.wipe()
        reassembler.wipe()
        for (item in outbox) item.envelope.fill(0)
        for (m in messages) m.attachment?.wipe()
    }

    companion object {
        fun newLocalId(random: RandomSource): String = Hex.encode(ByteArray(8).also(random::nextBytes))

        /** Only for reading state saved by version 4, which stored a push address here. */
        const val MAX_PUSH_URL_BYTES = 128
        const val DELIVERY_NONE = 0
        const val DELIVERY_QUEUED = 1
        const val DELIVERY_SENT = 2

        /** Older messages are dropped from the phone beyond this. */
        const val MAX_MESSAGES = 1000
        const val MAX_ENDPOINTS = 4
    }
}