package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptedRandomTest {
    @Test
    fun handsOutChunksInOrder() {
        val r = ScriptedRandom(listOf(ByteArray(32) { 1 }, ByteArray(12) { 2 }))
        assertTrue(X25519.generatePrivate(r).all { it.toInt() == 1 })
        assertEquals(12, ByteArray(12).also(r::nextBytes).size)
        r.assertDrained()
    }

    @Test
    fun aWrongSizeIsRefused() {
        val r = ScriptedRandom(listOf(ByteArray(16)))
        assertThrows(AssertionError::class.java) { r.nextBytes(ByteArray(32)) }
    }

    @Test
    fun drawingTooMuchIsRefused() {
        val r = ScriptedRandom(emptyList())
        assertThrows(AssertionError::class.java) { r.nextBytes(ByteArray(1)) }
    }

    @Test
    fun unusedChunksAreReported() {
        val r = ScriptedRandom(listOf(ByteArray(32)))
        assertThrows(AssertionError::class.java) { r.assertDrained() }
    }
}