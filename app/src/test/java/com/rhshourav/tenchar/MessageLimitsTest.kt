package com.rhshourav.tenchar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageLimitsTest {
    private val acute = "́"
    private val thumbsUp = "👍"

    // A letter with four combining marks: one character in every Unicode version, 9 bytes. It stands in
    // for a Bangla consonant-virama-consonant cluster, whose character count differs between Unicode 15.0
    // and 15.1 (and so between Android versions), while its size in bytes does not.
    private val heavy = "e" + acute.repeat(4)

    @Test
    fun countsGraphemesNotCodePoints() {
        assertEquals(0, MessageLimits.graphemeCount(""))
        assertEquals(5, MessageLimits.graphemeCount("hello"))
        assertEquals(1, MessageLimits.graphemeCount("e$acute"))
        assertEquals(1, MessageLimits.graphemeCount(thumbsUp))
    }

    @Test
    fun aMessageIsAtMostFiveHundredCharacters() {
        assertTrue(MessageLimits.fits(""))
        assertTrue(MessageLimits.fits("a".repeat(500)))
        assertFalse(MessageLimits.fits("a".repeat(501)))
    }

    @Test
    fun fiveHundredEmojiFitWithinTheByteLimit() {
        val text = thumbsUp.repeat(500)
        assertEquals(2000, MessageLimits.utf8Length(text))
        assertTrue(MessageLimits.fits(text))
    }

    @Test
    fun theByteLimitAppliesEvenBelowTheCharacterLimit() {
        // 228 heavy clusters are 228 characters but 2,052 bytes.
        val text = heavy.repeat(228)
        assertEquals(228, MessageLimits.graphemeCount(text))
        assertFalse(MessageLimits.fits(text))
        assertTrue(MessageLimits.fits(heavy.repeat(227)))
    }

    @Test
    fun theComposerHoldsTenMessages() {
        assertTrue(MessageLimits.fitsDraft("a".repeat(5000)))
        assertFalse(MessageLimits.fitsDraft("a".repeat(5001)))
        assertEquals("a".repeat(5000), MessageLimits.clamp("a".repeat(6000)))
        assertEquals("short", MessageLimits.clamp("short"))
        assertEquals("", MessageLimits.clamp(""))
    }

    @Test
    fun clampKeepsCombiningMarksWithTheirLetter() {
        val text = "a".repeat(4999) + "e$acute" + "x"
        // 4999 + the accented e is exactly 5000 characters; the x is cut, the accent stays.
        assertEquals("a".repeat(4999) + "e$acute", MessageLimits.clamp(text))
        // A second accent on the same letter is still one character, so it stays.
        assertEquals("a".repeat(4999) + "e$acute$acute", MessageLimits.clamp("a".repeat(4999) + "e$acute$acute"))
        // The next character is "b" plus an accent. It is cut as a whole, never leaving a lone accent behind.
        assertEquals("a".repeat(5000), MessageLimits.clamp("a".repeat(5000) + "b$acute"))
    }

    @Test
    fun clampResultAlwaysFitsTheComposer() {
        val samples = listOf("a".repeat(9000), thumbsUp.repeat(6000), heavy.repeat(3000), "x" + acute.repeat(30000))
        for (s in samples) assertTrue(MessageLimits.fitsDraft(MessageLimits.clamp(s)))
    }

    @Test
    fun fillFractionFollowsTheNearerLimit() {
        assertEquals(0f, MessageLimits.fillFraction(""), 0.0001f)
        assertEquals(0.5f, MessageLimits.fillFraction("a".repeat(250)), 0.0001f)
        assertEquals(1f, MessageLimits.fillFraction("a".repeat(500)), 0.0001f)
        assertEquals(1f, MessageLimits.fillFraction("a".repeat(900)), 0.0001f)
        // 100 heavy clusters: 100 characters (0.2) but 900 bytes (about 0.44). The byte limit is nearer.
        assertEquals(900f / 2048f, MessageLimits.fillFraction(heavy.repeat(100)), 0.0001f)
        // 500 emoji: 500 characters (1.0) but only 2000 bytes. The character limit is nearer.
        assertEquals(1f, MessageLimits.fillFraction(thumbsUp.repeat(500)), 0.0001f)
    }

    // ---- splitting ------------------------------------------------------------------------------------

    @Test
    fun textThatFitsIsOneMessage() {
        assertEquals(listOf("hello there"), MessageLimits.split("hello there"))
        assertEquals(listOf("a".repeat(500)), MessageLimits.split("a".repeat(500)))
        assertEquals(listOf("hi"), MessageLimits.split("  hi  "))
    }

    @Test
    fun blankTextIsNoMessages() {
        assertEquals(emptyList<String>(), MessageLimits.split(""))
        assertEquals(emptyList<String>(), MessageLimits.split("    "))
    }

    @Test
    fun fiveHundredAndOneCharactersBecomeTwoMessages() {
        val parts = MessageLimits.split("a".repeat(501))
        assertEquals(listOf("a".repeat(500), "a"), parts)
    }

    @Test
    fun everyPieceFitsAndNothingIsLost() {
        val samples = listOf(
            "a".repeat(5000),
            thumbsUp.repeat(1234),
            heavy.repeat(700),
            ("word " + "b".repeat(30) + " ").repeat(120),
            "ক্ষ স্ত্রী বাংলা ".repeat(150),
        )
        for (text in samples) {
            val parts = MessageLimits.split(text)
            assertTrue(parts.size > 1)
            for (p in parts) {
                assertTrue("piece of ${MessageLimits.graphemeCount(p)} chars, ${MessageLimits.utf8Length(p)} bytes", MessageLimits.fits(p))
                assertTrue(p.isNotBlank() && p == p.trim())
            }
            // Joining the pieces gives the text back, apart from the spaces at the cuts.
            assertEquals(text.filter { !it.isWhitespace() }, parts.joinToString("").filter { !it.isWhitespace() })
        }
    }

    @Test
    fun aCutPrefersASpaceNearTheEnd() {
        // 450 letters, a space, then 100 more: the cut falls at the space, so no word is broken.
        val text = "a".repeat(450) + " " + "b".repeat(100)
        assertEquals(listOf("a".repeat(450), "b".repeat(100)), MessageLimits.split(text))
        // A space too early in the piece is not worth a short message: the cut falls at 500.
        val early = "a".repeat(10) + " " + "b".repeat(600)
        assertEquals(listOf("a".repeat(10) + " " + "b".repeat(489), "b".repeat(111)), MessageLimits.split(early))
    }

    @Test
    fun aCutNeverSeparatesALetterFromItsMarks() {
        val parts = MessageLimits.split("a".repeat(499) + "e$acute" + "z".repeat(10))
        for (p in parts) assertFalse("a piece starts with a lone accent", p.startsWith(acute))
        assertEquals("a".repeat(499) + "e$acute", parts.first())
    }

    @Test
    fun aCharacterTooBigToEverFitIsDroppedWithoutLooping() {
        val monster = "e" + acute.repeat(1500) // one character, about 3,000 bytes
        assertEquals(1, MessageLimits.graphemeCount(monster))
        assertFalse(MessageLimits.fits(monster))
        assertEquals(listOf("hello"), MessageLimits.split("hello $monster"))
        assertEquals(emptyList<String>(), MessageLimits.split(monster))
    }
}