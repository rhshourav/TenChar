package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private val acute = "́"

    private fun frameOf(type: Int, len: Int, payload: ByteArray): ByteArray =
        ByteArray(66).also { it[0] = type.toByte(); it[1] = len.toByte(); payload.copyInto(it, 2) }

    private fun asText(frame: ByteArray) = (Wire.decodeFrame(frame) as? Wire.Decoded.Text)?.text

    // ---- single text frames (unchanged from before) ---------------------------------------------------

    @Test
    fun textFrameRoundTrips() {
        for (text in listOf("hi", "on my way", "abcdefghij", "👍👍👍👍👍👍👍👍👍👍", "আমি আসছি", "a".repeat(64))) {
            val frame = Wire.encodeText(text)
            assertNotNull(text, frame)
            assertEquals(66, frame!!.size)
            assertEquals(text, Wire.decodeText(frame))
            assertEquals(text, asText(frame))
        }
    }

    @Test
    fun aSingleFrameRefusesWhatItCannotCarry() {
        assertNull(Wire.encodeText(""))
        assertNull(Wire.encodeText("   "))
        assertNull(Wire.encodeText("a".repeat(65))) // needs fragments: use encodeMessage
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
        assertNull(Wire.decodeText(good.copyOf().also { it[60] = 1 })) // non-zero padding
        assertNull(Wire.decodeText(good.copyOf().also { it[2] = 0xFF.toByte() })) // invalid UTF-8
    }

    @Test
    fun theSingleFrameDecoderHasNoLengthRule() {
        // 12 letters fit in the 64-byte frame, so a receiver must accept them: the ratchet has already
        // advanced, and refusing would lose a message for good.
        val text = "abcdefghijkl"
        assertEquals(text, Wire.decodeText(frameOf(1, text.length, text.toByteArray())))
        val full = "x".repeat(64)
        assertEquals(full, Wire.decodeText(frameOf(1, 64, full.toByteArray())))
    }

    @Test
    fun banglaClustersRoundTrip() {
        for (text in listOf("ক্ষ", "স্ত্রী", "ক্ষ্ম", "বাংলা", "আমি আসছি", "শ্রী")) {
            val frame = Wire.encodeText(text)
            assertNotNull(text, frame)
            assertEquals(text, Wire.decodeText(frame!!))
        }
    }

    @Test
    fun controlAndBidiCharactersAreRefusedOnReceiving() {
        for (bad in listOf("a\u0000b", "a\nb", "a\u007Fb", "a\u202Eb", "a\u2066b", "a\u200Fb", "\u0085")) {
            val bytes = bad.toByteArray()
            assertNull(bad, Wire.decodeText(frameOf(1, bytes.size, bytes)))
        }
    }

    // ---- messages that need several frames ------------------------------------------------------------

    @Test
    fun aShortMessageIsOneFrameIdenticalToBefore() {
        for (text in listOf("hi", "on my way", "a".repeat(64), "আমি আসছি")) {
            val frames = Wire.encodeMessage(text, group = 7)!!
            assertEquals(1, frames.size)
            assertTrue(frames[0].contentEquals(Wire.encodeText(text)!!))
            assertEquals(1, Wire.frameCount(text))
        }
    }

    @Test
    fun aLongerMessageIsSplitIntoFullFragments() {
        val text = "a".repeat(500)
        val frames = Wire.encodeMessage(text, group = 0x1234)!!
        assertEquals(9, frames.size) // 500 / 60, rounded up
        assertEquals(frames.size, Wire.frameCount(text))
        for ((i, f) in frames.withIndex()) {
            assertEquals(66, f.size)
            assertEquals(2, f[0].toInt())
            assertEquals(0x12, f[2].toInt() and 0xFF)
            assertEquals(0x34, f[3].toInt() and 0xFF)
            assertEquals(i, f[4].toInt())
            assertEquals(9, f[5].toInt())
            val data = (f[1].toInt() and 0xFF) - 4
            assertEquals(if (i < 8) 60 else 20, data)
        }
    }

    @Test
    fun theLargestMessageNeedsThirtyFiveFrames() {
        val heavy = ("e" + acute.repeat(4)).repeat(227) // 227 characters, 2,043 bytes
        assertEquals(35, Wire.encodeMessage(heavy, 1)!!.size)
        assertEquals(34, Wire.encodeMessage("👍".repeat(500), 1)!!.size) // 500 characters, 2,000 bytes
    }

    @Test
    fun encodeMessageEnforcesTheLimitsOnTheWayOut() {
        assertNull(Wire.encodeMessage("a".repeat(501), 1))
        assertNull(Wire.encodeMessage(("e" + acute.repeat(4)).repeat(228), 1)) // 2,052 bytes
        assertNull(Wire.encodeMessage("  ", 1))
        assertNull(Wire.encodeMessage("long ${"x".repeat(100)}\u202E", 1))
        assertNotNull(Wire.encodeMessage("a".repeat(500), 1))
    }

    @Test
    fun onlyTheLow16BitsOfTheGroupAreUsed() {
        val f = Wire.encodeMessage("a".repeat(100), group = 0x1ABCD)!!
        assertEquals(0xAB, f[0][2].toInt() and 0xFF)
        assertEquals(0xCD, f[0][3].toInt() and 0xFF)
    }

    @Test
    fun fragmentDecodingIsStrict() {
        val frames = Wire.encodeMessage("a".repeat(130), group = 5)!! // 3 fragments: 60, 60, 10
        val first = Wire.decodeFrame(frames[0]) as Wire.Decoded.Fragment
        assertEquals(5, first.group); assertEquals(0, first.index); assertEquals(3, first.count); assertEquals(60, first.data.size)
        val last = Wire.decodeFrame(frames[2]) as Wire.Decoded.Fragment
        assertEquals(10, last.data.size)

        fun mutate(i: Int, change: (ByteArray) -> Unit) = assertNull("fragment $i", Wire.decodeFrame(frames[i].copyOf().also(change)))
        mutate(0) { it[0] = 3 } // unknown type
        mutate(0) { it[1] = 4 } // header only, no data
        mutate(0) { it[1] = 65 } // longer than a frame can hold
        mutate(0) { it[5] = 1 } // a single "fragment" is not a fragment
        mutate(0) { it[5] = 36 } // more pieces than any message needs
        mutate(0) { it[4] = 3 } // index not below count
        mutate(0) { it[1] = 50 } // a middle piece that is not full
        mutate(2) { it[65] = 1 } // non-zero padding
        mutate(2) { it[1] = 4 } // empty last piece
        assertNull(Wire.decodeFrame(frames[0].copyOf(65)))
    }

    // ---- profile frames (names and avatars) ------------------------------------------------------------

    @Test
    fun aProfileRoundTrips() {
        for ((name, avatar, color) in listOf(
            Triple("Rafi", "🦊", 3),
            Triple("আমি রাফি", "", 0),
            Triple("", "😀", 7),
            Triple("a".repeat(32), "👨‍👩‍👧‍👦", 5), // the longest name and a 25-byte family emoji
            Triple("", "", 1),
        )) {
            val frame = Wire.encodeProfile(name, avatar, color)
            assertNotNull("$name $avatar", frame)
            assertEquals(66, frame!!.size)
            assertEquals(3, frame[0].toInt())
            val d = Wire.decodeFrame(frame) as Wire.Decoded.ProfileUpdate
            assertEquals(name, d.name); assertEquals(avatar, d.avatar); assertEquals(color, d.color)
        }
    }

    @Test
    fun aProfileThatCannotFitOrIsUnsafeIsNotEncoded() {
        assertNull(Wire.encodeProfile("a".repeat(33), "", 0))
        assertNull(Wire.encodeProfile("", "a".repeat(29), 0))
        assertNull(Wire.encodeProfile("x", "", 8))
        assertNull(Wire.encodeProfile("x", "", -1))
        assertNull(Wire.encodeProfile("bad\u202Ename", "", 0))
        assertNull(Wire.encodeProfile("nul\u0000", "", 0))
    }

    @Test
    fun profileDecodingIsStrict() {
        val good = Wire.encodeProfile("Rafi", "🦊", 3)!! // len = 2 + 4 + 4 = 10
        fun mutate(what: String, change: (ByteArray) -> Unit) =
            assertNull(what, Wire.decodeFrame(good.copyOf().also(change)))
        mutate("colour 8") { it[2] = 8 }
        mutate("avatar longer than the frame holds") { it[3] = 40 }
        mutate("avatar longer than allowed") { it[3] = 29; it[1] = 35 }
        mutate("name longer than allowed") { it[1] = (2 + 4 + 33).toByte() }
        mutate("shorter than the header") { it[1] = 1 }
        mutate("longer than a frame") { it[1] = 65 }
        mutate("non-zero padding") { it[40] = 1 }
        mutate("invalid UTF-8 in the name") { it[8] = 0xFF.toByte() }
        mutate("control character in the name") { it[8] = 0x07 }
        mutate("a bidi override in the name") {
            val bytes = "\u202E".toByteArray(); it[8] = bytes[0]; it[9] = bytes[1]; it[10] = bytes[2]
        }
    }

    @Test
    fun aProfileIsNotAMessage() {
        val d = Wire.decodeFrame(Wire.encodeProfile("Rafi", "🦊", 3)!!)
        assertTrue(d is Wire.Decoded.ProfileUpdate)
        assertNull(Wire.decodeText(Wire.encodeProfile("Rafi", "🦊", 3)!!)) // never read as chat text
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