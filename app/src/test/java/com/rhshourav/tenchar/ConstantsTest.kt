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
    fun envelopeSizesMatchTheRelay() {
        // relay/src/constants.js: 41 + 12 + 66 + 16 = 135 bytes, 180 base64 characters.
        assertEquals(135, Constants.ENVELOPE_BYTES)
        assertEquals(180, Constants.ENVELOPE_B64_CHARS)
        assertEquals(0, Constants.ENVELOPE_BYTES % 3)
    }

    @Test
    fun relaysAreDistinctHttpsOrigins() {
        assertTrue(Constants.DEFAULT_RELAY_URL != Constants.FALLBACK_RELAY_URL)
        assertEquals("https://tenchar-relay.tenchar.workers.dev", Constants.FALLBACK_RELAY_URL)
        assertTrue(Constants.SALT_KEY != Constants.SALT_ROOM && Constants.SALT_KEY != Constants.SALT_AUTH)
    }

    @Test
    fun userAgentUsesVersionName() {
        assertEquals("TenChar-android/0.1.0", Constants.USER_AGENT)
    }
}
