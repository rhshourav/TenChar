package com.rhshourav.tenchar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFormatTest {
    private val letters = "ABCDEFGHJKLMNPQR"

    @Test
    fun hyphensAreOnlyForDisplay() {
        assertEquals("", CodeFormat.display(""))
        assertEquals("ABCD", CodeFormat.display("ABCD"))
        assertEquals("ABCD-E", CodeFormat.display("ABCDE"))
        assertEquals("ABCD-EFGH-J", CodeFormat.display("ABCDEFGHJ"))
        assertEquals("ABCD-EFGH-JKLM-NPQR", CodeFormat.display(letters))
    }

    @Test
    fun theCursorStaysAfterTheLetterJustTyped() {
        // The bug: after the 5th, 9th and 13th letter the cursor landed in front of the new letter.
        // Typing at the end puts the cursor at the end of the held text; it must be at the end of the shown text too.
        for (n in 0..16) {
            val shown = CodeFormat.display(letters.take(n))
            assertEquals("after $n letters", shown.length, CodeFormat.originalToTransformed(n))
        }
    }

    @Test
    fun everyCursorPositionMapsThereAndBack() {
        for (n in 0..16) {
            val shown = CodeFormat.display(letters.take(n))
            for (o in 0..n) {
                val t = CodeFormat.originalToTransformed(o)
                assertTrue("shown position $t is outside \"$shown\"", t in 0..shown.length)
                assertEquals("held $o of $n", o, CodeFormat.transformedToOriginal(t, n))
            }
        }
    }

    @Test
    fun aTapNextToAHyphenLandsBesideALetter() {
        // "ABCD-EFGH": a tap before or after the hyphen means "between D and E".
        assertEquals(4, CodeFormat.transformedToOriginal(4, 8))
        assertEquals(4, CodeFormat.transformedToOriginal(5, 8))
        assertEquals(5, CodeFormat.transformedToOriginal(6, 8))
        assertEquals(8, CodeFormat.transformedToOriginal(99, 8)) // past the end is the end
        assertEquals(0, CodeFormat.transformedToOriginal(-3, 8))
    }

    @Test
    fun theFieldCanHoldWhatAPasteBrings() {
        // The held text is exactly the valid letters, so a paste with hyphens and spaces gives the same code.
        val pasted = " abcd-efgh jklm-npqr "
        val held = Pairing.normalise(pasted).filter { it in Constants.CODE_ALPHABET }.take(Constants.CODE_LEN)
        assertEquals(letters, held)
        assertEquals("ABCD-EFGH-JKLM-NPQR", CodeFormat.display(held))
    }
}