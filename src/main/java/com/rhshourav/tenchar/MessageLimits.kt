package com.rhshourav.tenchar

import java.text.BreakIterator

/**
 * Message length rules. A message is at most MAX_CHARS user-perceived characters (grapheme
 * clusters) and at most MAX_MESSAGE_BYTES bytes. Counting code points instead would make Bangla
 * and emoji unusable, because one visible character can be several code points.
 *
 * Text longer than one message is split by [split] into several messages. The composer holds up to
 * MAX_DRAFT_CHARS characters, which is at most ten messages.
 */
object MessageLimits {
    fun graphemeCount(text: String): Int {
        if (text.isEmpty()) return 0
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        var count = 0
        while (iter.next() != BreakIterator.DONE) count++
        return count
    }

    fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    /** True if [text] is one message: at most MAX_CHARS characters and [maxBytes] bytes (a reply allows fewer). */
    fun fits(text: String, maxBytes: Int = Constants.MAX_MESSAGE_BYTES): Boolean =
        graphemeCount(text) <= Constants.MAX_CHARS && utf8Length(text) <= maxBytes

    /** True if [text] fits in the composer, which holds up to ten messages. */
    fun fitsDraft(text: String): Boolean =
        graphemeCount(text) <= Constants.MAX_DRAFT_CHARS && utf8Length(text) <= Constants.MAX_DRAFT_BYTES

    /**
     * How full the composer is, from 0 to 1, measured against one message: whichever of the character
     * and byte limits is closer. A letter with several combining marks is one character but many bytes,
     * so the byte limit can be nearer while the character counter still reads low. At 1 the text is
     * about to become more than one message.
     */
    fun fillFraction(text: String): Float {
        val byChars = graphemeCount(text) / Constants.MAX_CHARS.toFloat()
        val byBytes = utf8Length(text) / Constants.MAX_MESSAGE_BYTES.toFloat()
        return maxOf(byChars, byBytes).coerceIn(0f, 1f)
    }

    /** The longest prefix of [text] with at most [chars] characters and [bytes] bytes, cut at a grapheme boundary. */
    fun clampTo(text: String, chars: Int, bytes: Int): String = prefix(text, chars, bytes)

    /** The first user-perceived character of [text], or "" if it is empty. */
    fun firstGrapheme(text: String): String = prefix(text, 1, Int.MAX_VALUE)

    /** The longest prefix of [text] that fits in the composer, cut only at grapheme boundaries. */
    fun clamp(text: String): String = prefix(text, Constants.MAX_DRAFT_CHARS, Constants.MAX_DRAFT_BYTES)

    /**
     * Cuts [text] into messages that each [fit]. Cuts fall on grapheme boundaries, so a base letter is
     * never separated from its combining marks, and prefer a space near the end of a piece so words stay
     * whole. Spaces at a cut are dropped. Blank input gives no messages. A single character too large to
     * ever fit (thousands of combining marks) is dropped rather than looping.
     */
    fun split(text: String, maxBytes: Int = Constants.MAX_MESSAGE_BYTES): List<String> {
        val parts = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (fits(rest, maxBytes)) {
                parts += rest
                break
            }
            val head = prefix(rest, Constants.MAX_CHARS, maxBytes)
            if (head.isEmpty()) {
                rest = rest.substring(firstClusterEnd(rest)).trimStart()
                continue
            }
            // A space in the last fifth of the piece is a better place to cut than mid-word.
            val space = head.indexOfLast { it.isWhitespace() }
            val cut = if (space >= head.length * 4 / 5 && space > 0) space + 1 else head.length
            val piece = head.substring(0, cut).trimEnd()
            if (piece.isNotEmpty()) parts += piece
            rest = rest.substring(cut).trimStart()
        }
        return parts
    }

    /** The longest prefix of [text] with at most [maxChars] characters and [maxBytes] bytes, at a grapheme boundary. */
    private fun prefix(text: String, maxChars: Int, maxBytes: Int): String {
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        var start = iter.first()
        var end = 0
        var count = 0
        var bytes = 0
        var boundary = iter.next()
        while (boundary != BreakIterator.DONE) {
            val clusterBytes = utf8Length(text.substring(start, boundary))
            if (count + 1 > maxChars || bytes + clusterBytes > maxBytes) break
            count++
            bytes += clusterBytes
            end = boundary
            start = boundary
            boundary = iter.next()
        }
        return text.substring(0, end)
    }

    private fun firstClusterEnd(text: String): Int {
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        iter.first()
        return iter.next().let { if (it == BreakIterator.DONE) text.length else it }
    }
}