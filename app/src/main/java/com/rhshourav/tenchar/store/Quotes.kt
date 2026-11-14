package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.crypto.Wire

/**
 * What a reply points at, as this phone sees it: whether the answered message was [quotedMine] (written by this phone's
 * owner) and a short [hash] of it (see [Wire.quoteHash]). The text of the answered message is never stored here or sent:
 * it is looked up in this chat when the reply is shown.
 */
class ReplyRef(val quotedMine: Boolean, val hash: ByteArray)

object Quotes {
    /** The part of a message that its hash is made from: its text, or for an attachment "att:" and its id. */
    fun canonical(m: StoredMessage): String = m.attachment?.let { "att:" + it.id } ?: m.text

    /** The reference a reply to [target] carries. */
    fun refTo(target: StoredMessage): ReplyRef = ReplyRef(target.mine, Wire.quoteHash(canonical(target)))

    /** The wire form of [ref] for a reply this phone sends: who = 0 if the answered message is the sender's own. */
    fun toWire(ref: ReplyRef): Wire.Quote = Wire.Quote(if (ref.quotedMine) 0 else 1, ref.hash)

    /** The stored form of a reference received from the other phone: who = 1 means the answered message is the receiver's, i.e. mine. */
    fun fromWire(quote: Wire.Quote): ReplyRef = ReplyRef(quote.who == 1, quote.hash)

    /**
     * The message that [messages] [index] answers, or null if it is no longer there (older than the chat keeps, or not a
     * message this phone has). Earlier messages are looked at first, newest first, because that is where an answered
     * message normally is. Only messages by the right person are considered.
     */
    fun resolve(messages: List<StoredMessage>, index: Int, ref: ReplyRef): StoredMessage? {
        fun matches(m: StoredMessage) = m.mine == ref.quotedMine && Wire.quoteHash(canonical(m)).contentEquals(ref.hash)
        for (i in index - 1 downTo 0) if (matches(messages[i])) return messages[i]
        for (i in index + 1 until messages.size) if (matches(messages[i])) return messages[i]
        return null
    }
}
