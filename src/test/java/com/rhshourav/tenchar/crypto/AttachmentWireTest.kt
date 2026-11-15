package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-frame descriptor that carries an attachment's key through the ratchet (frame type 4). */
class AttachmentWireTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val wave = ByteArray(Constants.WAVE_BYTES) { (it * 7).toByte() }

    private fun roundTrip(kind: Int, size: Int, w: Int, h: Int, d: Int, wv: ByteArray) {
        val frame = Wire.encodeAttachment(kind, key, size, w, h, d, wv)
        assertNotNull(frame)
        assertEquals(Constants.FRAME_BYTES, frame!!.size)
        val back = Wire.decodeFrame(frame) as Wire.Decoded.AttachmentDescriptor
        assertEquals(kind, back.kind)
        assertArrayEquals(key, back.key)
        assertEquals(size, back.size)
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        assertEquals(d, back.durationDs)
        assertArrayEquals(wv, back.wave)
    }

    @Test
    fun everyKindRoundTrips() {
        roundTrip(Constants.KIND_IMAGE, 183_456, 1280, 960, 0, ByteArray(0))
        roundTrip(Constants.KIND_VOICE, 120_000, 0, 0, 1200, wave)
        roundTrip(Constants.KIND_VOICE, 5_000, 0, 0, 20, ByteArray(0))
        roundTrip(Constants.KIND_VIDEO, Constants.MAX_MEDIA_BYTES, 480, 854, 300, ByteArray(0))
    }

    @Test
    fun valuesThatCouldNeverBeRealAreRefused() {
        assertNull(Wire.encodeAttachment(Constants.KIND_IMAGE, key, 10, 10, 10, 5, ByteArray(0))) // a photo has no length
        assertNull(Wire.encodeAttachment(Constants.KIND_VOICE, key, 0, 0, 0, 5, wave)) // no content
        assertNull(Wire.encodeAttachment(Constants.KIND_IMAGE, key, Constants.MAX_MEDIA_BYTES + 1, 10, 10, 0, ByteArray(0)))
        assertNull(Wire.encodeAttachment(9, key, 10, 10, 10, 0, ByteArray(0)))
        assertNull(Wire.encodeAttachment(Constants.KIND_VOICE, key, 10, 0, 0, 5, ByteArray(7)))
        assertNull(Wire.encodeAttachment(Constants.KIND_IMAGE, ByteArray(31), 10, 10, 10, 0, ByteArray(0)))
    }

    @Test
    fun aDamagedFrameIsIgnoredNotHalfRead() {
        val good = Wire.encodeAttachment(Constants.KIND_IMAGE, key, 1000, 100, 100, 0, ByteArray(0))!!
        assertNull(Wire.decodeFrame(good.copyOf().also { it[60] = 1 })) // padding must be zero
        assertNull(Wire.decodeFrame(good.copyOf().also { it[1] = 40 })) // too short to hold a descriptor
        assertNull(Wire.decodeFrame(good.copyOf().also { it[0] = 9 })) // an unknown type is still ignored
        assertTrue(Wire.decodeFrame(Wire.encodeText("hello")!!) is Wire.Decoded.Text) // text frames are unchanged
    }
}
