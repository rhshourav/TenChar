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
}