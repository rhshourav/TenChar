package com.rhshourav.tenchar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLimitsTest {
    private val acute = "́"
    private val thumbsUp = "👍"

    @Test
    fun countsGraphemesNotCodePoints() {
        assertEquals(0, MessageLimits.graphemeCount(""))
        assertEquals(5, MessageLimits.graphemeCount("hello"))
        assertEquals(1, MessageLimits.graphemeCount("e$acute"))
        assertEquals(1, MessageLimits.graphemeCount(thumbsUp))
    }

    @Test
    fun tenCharactersFitAndElevenDoNot() {
        assertTrue(MessageLimits.fits("abcdefghij"))
        assertFalse(MessageLimits.fits("abcdefghijk"))
        assertTrue(MessageLimits.fits(""))
    }

    @Test
    fun tenEmojiFitWithinByteLimit() {
        val text = thumbsUp.repeat(10)
        assertEquals(40, MessageLimits.utf8Length(text))
        assertTrue(MessageLimits.fits(text))
    }

    @Test
    fun byteLimitAppliesEvenForOneGrapheme() {
        val text = "e" + acute.repeat(40) // one grapheme, 81 bytes
        assertEquals(1, MessageLimits.graphemeCount(text))
        assertFalse(MessageLimits.fits(text))
        assertEquals("", MessageLimits.clamp(text))
    }

    @Test
    fun clampCutsAtTenCharacters() {
        assertEquals("abcdefghij", MessageLimits.clamp("abcdefghijklmno"))
        assertEquals("short", MessageLimits.clamp("short"))
        assertEquals("", MessageLimits.clamp(""))
    }

    @Test
    fun clampKeepsCombiningMarksWithTheirLetter() {
        val nine = "abcdefghi"
        // Ten characters, the tenth being e with an accent, then an eleventh.
        assertEquals("${nine}e$acute", MessageLimits.clamp("${nine}e${acute}x"))
        // A second accent on the same letter is still one character, so it stays.
        assertEquals("${nine}e$acute$acute", MessageLimits.clamp("${nine}e$acute$acute"))
        // The eleventh character is "b" plus an accent. It is cut as a whole,
        // never leaving a lone accent behind.
        assertEquals("${nine}a", MessageLimits.clamp("${nine}ab$acute"))
    }

    @Test
    fun clampResultAlwaysFits() {
        val samples = listOf(
            "a".repeat(100),
            thumbsUp.repeat(30),
            ("e" + acute).repeat(30),
            "x" + acute.repeat(100),
        )
        for (s in samples) assertTrue(MessageLimits.fits(MessageLimits.clamp(s)))
    }

    // A letter with four combining marks: one character in every Unicode version, 9 bytes. It stands in
    // for a Bangla consonant-virama-consonant cluster, whose character count differs between Unicode 15.0
    // and 15.1 (and so between Android versions), while its size in bytes does not.
    private val heavy = "e" + acute.repeat(4)

    @Test
    fun fillFractionFollowsTheNearerLimit() {
        assertEquals(0f, MessageLimits.fillFraction(""), 0.0001f)
        assertEquals(0.5f, MessageLimits.fillFraction("hello"), 0.0001f)
        assertEquals(1f, MessageLimits.fillFraction("abcdefghij"), 0.0001f)
        // Ten emoji: ten characters, only 40 bytes. The character limit is nearer.
        assertEquals(1f, MessageLimits.fillFraction(thumbsUp.repeat(10)), 0.0001f)
        // Seven heavy clusters: 7 characters (0.7) but 63 bytes (about 0.98). The byte limit is nearer.
        val seven = heavy.repeat(7)
        assertEquals(7, MessageLimits.graphemeCount(seven))
        assertEquals(63, MessageLimits.utf8Length(seven))
        assertEquals(63f / 64f, MessageLimits.fillFraction(seven), 0.0001f)
        assertTrue(MessageLimits.fits(seven))
        // An eighth no longer fits, although the counter would read only 8 of 10.
        assertFalse(MessageLimits.fits(seven + heavy))
        assertEquals(1f, MessageLimits.fillFraction(seven + heavy), 0.0001f)
    }

    @Test
    fun theByteLimitCanBindBelowTenCharacters() {
        val raw = heavy.repeat(8)
        // The composer's rule: refused while still at most 10 characters means the bytes did it.
        assertTrue(MessageLimits.graphemeCount(raw) <= Constants.MAX_CHARS)
        assertFalse(MessageLimits.fits(raw))
        assertEquals(heavy.repeat(7), MessageLimits.clamp(raw))
        // And when the characters bind, the rule does not call it a byte limit.
        val eleven = "abcdefghijk"
        assertFalse(MessageLimits.fits(eleven))
        assertTrue(MessageLimits.graphemeCount(eleven) > Constants.MAX_CHARS)
    }
}