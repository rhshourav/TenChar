package com.rhshourav.tenchar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantsTest {
    @Test
    fun specValues() {
        assertEquals(10, Constants.MAX_CHARS)
        assertEquals(64, Constants.MAX_UTF8_BYTES)
        assertEquals(66, Constants.FRAME_BYTES)
        assertEquals(16, Constants.CODE_LEN)
        assertEquals(24, Constants.PAIRING_WINDOW_HOURS)
        assertEquals(7, Constants.RETENTION_DAYS)
        assertEquals(200, Constants.MAX_SKIP)
        assertEquals(512, Constants.MAX_BODY_BYTES)
        assertEquals(600_000, Constants.PBKDF2_ITERATIONS)
    }

    @Test
    fun codeAlphabetGivesEightyBitsWithoutModuloBias() {
        assertEquals(32, Constants.CODE_ALPHABET.length)
        assertEquals(32, Constants.CODE_ALPHABET.toSet().size)
        assertEquals(80, Constants.CODE_LEN * 5)
        // 256 is a multiple of 32, so one random byte maps to a symbol evenly.
        assertEquals(0, 256 % Constants.CODE_ALPHABET.length)
        assertTrue("IO01".none { it in Constants.CODE_ALPHABET })
    }

    @Test
    fun userAgentUsesVersionName() {
        assertEquals("TenChar-android/0.1.0", Constants.USER_AGENT)
    }
}
