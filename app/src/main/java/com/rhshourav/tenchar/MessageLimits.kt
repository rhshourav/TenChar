package com.rhshourav.tenchar

import java.text.BreakIterator

/**
 * Message length rules. A message is at most MAX_CHARS user-perceived
 * characters (grapheme clusters) and at most MAX_UTF8_BYTES bytes. Counting
 * code points instead would make Bangla and emoji unusable, because one
 * visible character can be several code points.
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

    /**
     * How full the message is, from 0 to 1: whichever of the two limits is closer. A Bangla
     * consonant-virama-consonant cluster is one character but 9 bytes, so the byte limit can be
     * reached while the character counter still reads under 10. The ring and the hint use this
     * so the person can see which limit is about to bind.
     */
    fun fillFraction(text: String): Float {
        val byChars = graphemeCount(text) / Constants.MAX_CHARS.toFloat()
        val byBytes = utf8Length(text) / Constants.MAX_UTF8_BYTES.toFloat()
        return maxOf(byChars, byBytes).coerceIn(0f, 1f)
    }

    fun fits(text: String): Boolean =
        graphemeCount(text) <= Constants.MAX_CHARS && utf8Length(text) <= Constants.MAX_UTF8_BYTES

    /**
     * The longest prefix of [text] that fits, cut only at grapheme boundaries,
     * so a base letter is never separated from its combining marks.
     */
    fun clamp(text: String): String {
        val iter = BreakIterator.getCharacterInstance()
        iter.setText(text)
        var start = iter.first()
        var end = 0
        var count = 0
        var bytes = 0
        var boundary = iter.next()
        while (boundary != BreakIterator.DONE) {
            val clusterBytes = utf8Length(text.substring(start, boundary))
            if (count + 1 > Constants.MAX_CHARS || bytes + clusterBytes > Constants.MAX_UTF8_BYTES) break
            count++
            bytes += clusterBytes
            end = boundary
            start = boundary
            boundary = iter.next()
        }
        return text.substring(0, end)
    }
}