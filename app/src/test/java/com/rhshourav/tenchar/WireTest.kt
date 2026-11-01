package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test
    fun textFrameRoundTrips() {
        for (text in listOf("hi", "on my way", "abcdefghij", "👍👍👍👍👍👍👍👍👍👍", "আমি আসছি")) {
            val frame = Wire.encodeText(text)
            assertNotNull(text, frame)
            assertEquals(66, frame!!.size)
            assertEquals(text, Wire.decodeText(frame))
        }
    }

    @Test
    fun refusesWhatDoesNotFit() {
        assertNull(Wire.encodeText(""))
        assertNull(Wire.encodeText("   "))
        assertNull(Wire.encodeText("abcdefghijk"))
        assertNull(Wire.encodeText("a\u0000b"))
        assertNull(Wire.encodeText("abc\u202Edef"))
    }

    @Test
    fun decodeIsStrict() {
        val good = Wire.encodeText("hello")!!
        assertNull(Wire.decodeText(good.copyOf(65)))
        assertNull(Wire.decodeText(good.copyOf().also { it[0] = 9 }))
        assertNull(Wire.decodeText(good.copyOf().also { it[1] = 0 }))
        assertNull(Wire.decodeText(good.copyOf().also { it[1] = 65.toByte() }))
        // Non-zero padding is refused.
        assertNull(Wire.decodeText(good.copyOf().also { it[60] = 1 }))
        // Invalid UTF-8.
        assertNull(Wire.decodeText(good.copyOf().also { it[2] = 0xFF.toByte() }))
    }

    @Test
    fun theTenCharacterLimitIsEnforcedOnSendingOnly() {
        // 12 letters fit in the 64-byte frame, so a receiver must accept them: the ratchet has
        // already advanced, and refusing would lose a message for good.
        val text = "abcdefghijkl"
        assertNull(Wire.encodeText(text))
        val frame = ByteArray(66).also {
            it[0] = 1
            it[1] = text.length.toByte()
            text.toByteArray().copyInto(it, 2)
        }
        assertEquals(text, Wire.decodeText(frame))

        // The full 64 bytes of text, with no spaces, is also accepted.
        val full = "x".repeat(64)
        val fullFrame = ByteArray(66).also { it[0] = 1; it[1] = 64; full.toByteArray().copyInto(it, 2) }
        assertEquals(full, Wire.decodeText(fullFrame))
    }

    @Test
    fun banglaClustersRoundTrip() {
        // Consonant-virama-consonant clusters, with and without a vowel sign. These stay under 10 characters
        // whichever Unicode version counts them, so encodeText accepts them everywhere.
        for (text in listOf("ক্ষ", "স্ত্রী", "ক্ষ্ম", "বাংলা", "আমি আসছি", "শ্রী")) {
            val frame = Wire.encodeText(text)
            assertNotNull(text, frame)
            assertEquals(text, Wire.decodeText(frame!!))
        }
        // Whatever another platform counted, a well-formed frame of Bangla text is delivered.
        val long = "ক্ষ".repeat(7) // seven conjuncts are 63 bytes: it fits the frame whatever a platform counts them as
        val bytes = long.toByteArray()
        val frame = ByteArray(66).also { it[0] = 1; it[1] = bytes.size.toByte(); bytes.copyInto(it, 2) }
        assertEquals(long, Wire.decodeText(frame))
    }

    @Test
    fun controlAndBidiCharactersAreStillRefusedOnReceiving() {
        for (bad in listOf("a\u0000b", "a\nb", "a\u007Fb", "a\u202Eb", "a\u2066b", "a\u200Fb", "\u0085")) {
            val bytes = bad.toByteArray()
            val frame = ByteArray(66).also { it[0] = 1; it[1] = bytes.size.toByte(); bytes.copyInto(it, 2) }
            assertNull(bad, Wire.decodeText(frame))
        }
    }

    @Test
    fun envelopeBase64HasTheLengthTheRelayExpects() {
        val env = ByteArray(135) { it.toByte() }
        val text = Wire.toBase64(env)
        assertEquals(180, text.length)
        assertTrue(Regex("^[A-Za-z0-9+/]{180}$").matches(text))
        assertTrue(Wire.fromBase64(text)!!.contentEquals(env))
        assertNull(Wire.fromBase64(text.dropLast(1)))
        assertNull(Wire.fromBase64(text + "A"))
        assertNull(Wire.fromBase64("!".repeat(180)))
    }
}