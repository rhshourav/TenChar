package com.rhshourav.tenchar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantsTest {
    @Test
    fun specValues() {
        assertEquals(500, Constants.MAX_CHARS)
        assertEquals(2048, Constants.MAX_MESSAGE_BYTES)
        assertEquals(5000, Constants.MAX_DRAFT_CHARS)
        // The relay's contract test reads this one, and one frame is one fixed-size envelope.
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
    fun longMessageLimitsAreConsistent() {
        // 500 four-byte characters must fit the byte limit, or a full message of emoji could not be sent.
        assertTrue(Constants.MAX_CHARS * 4 <= Constants.MAX_MESSAGE_BYTES)
        // The fragments of the largest message fit the count a fragment can carry (one byte).
        assertEquals(35, Constants.MAX_FRAGMENTS)
        assertTrue(Constants.MAX_FRAGMENTS * Constants.MAX_FRAGMENT_DATA >= Constants.MAX_MESSAGE_BYTES)
        assertTrue(Constants.MAX_FRAGMENTS <= 255)
        // The composer holds ten full messages at most.
        assertEquals(10, Constants.MAX_DRAFT_CHARS / Constants.MAX_CHARS)
        // A fragment frame is the same size as a text frame: one envelope each.
        assertEquals(Constants.FRAME_BYTES, 2 + Constants.FRAGMENT_HEADER_BYTES + Constants.MAX_FRAGMENT_DATA)
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