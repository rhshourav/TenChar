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
        // More than ten characters inside a frame that is otherwise well formed.
        val long = ByteArray(66).also { it[0] = 1; it[1] = 11; for (i in 0 until 11) it[2 + i] = 'a'.code.toByte() }
        assertNull(Wire.decodeText(long))
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
