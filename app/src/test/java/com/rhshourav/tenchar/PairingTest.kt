package com.rhshourav.tenchar

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val code = "ABCDEFGHJKLMNPQR"

    // Expected values below were computed with Python's hashlib.pbkdf2_hmac,
    // independently of this code.
    private val expectedRoom = "f1b914add3fb600eb619488f2d3e622c"
    private val expectedToken = "5d3b0d40eb1b4274dde289ad6d7f6f84f6c4c64d23f2ddcf"
    private val expectedKey = "e35b8e3b2f8d6601f12b26e95cd2eed063ed1be586555ffe1dcf739f124425ac"

    @Test
    fun pbkdf2MatchesPublishedVectors() {
        val pw = "password".toCharArray()
        val salt = "salt".toByteArray()
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            Hex.encode(Pairing.pbkdf2Sha256(pw, salt, 1, 32)),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            Hex.encode(Pairing.pbkdf2Sha256(pw, salt, 2, 32)),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            Hex.encode(Pairing.pbkdf2Sha256(pw, salt, 4096, 32)),
        )
    }

    @Test
    fun deriveMatchesIndependentlyComputedValues() = runBlocking {
        val d = Pairing.derive("ABCD-EFGH-JKLM-NPQR")
        assertEquals(expectedRoom, d.room)
        assertEquals(expectedToken, d.codeToken)
        assertEquals(expectedKey, Hex.encode(d.codeKey))
    }

    @Test
    fun deriveIgnoresCaseAndSeparators() = runBlocking {
        val d = Pairing.derive(" abcd-efgh jklm-npqr ")
        assertEquals(expectedRoom, d.room)
        assertEquals(expectedToken, d.codeToken)
        assertEquals(expectedKey, Hex.encode(d.codeKey))
    }

    @Test
    fun derivedValuesHaveSpecLengthsAndAreUnrelated() = runBlocking {
        val d = Pairing.derive(code)
        assertEquals(32, d.room.length)
        assertEquals(48, d.codeToken.length)
        assertTrue(d.room.all { it in "0123456789abcdef" })
        assertTrue(d.codeToken.all { it in "0123456789abcdef" })
        assertFalse(d.codeToken.startsWith(d.room))
        // The key that never leaves the phone is independent of what the relay sees.
        assertEquals(32, d.codeKey.size)
        assertFalse(Hex.encode(d.codeKey).contains(d.codeToken))
        assertFalse(Hex.encode(d.codeKey).startsWith(d.room))
        d.wipe()
        assertTrue(d.codeKey.all { it.toInt() == 0 })
    }

    @Test
    fun derivedToStringDoesNotPrintSecrets() = runBlocking {
        val d = Pairing.derive(code)
        assertFalse(d.toString().contains(d.room))
        assertFalse(d.toString().contains(d.codeToken))
        assertFalse(d.toString().contains(Hex.encode(d.codeKey)))
    }

    @Test
    fun deriveRejectsInvalidCode() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { Pairing.derive("short") }
        }
    }

    @Test
    fun fromBytesMapsEachByteToOneSymbol() {
        assertEquals(code, Pairing.fromBytes(ByteArray(16) { it.toByte() }))
        assertEquals("9999999999999999", Pairing.fromBytes(ByteArray(16) { 0xFF.toByte() }))
        // Only the low 5 bits matter, so 0x20 + i maps like i.
        assertEquals(code, Pairing.fromBytes(ByteArray(16) { (0x20 + it).toByte() }))
    }

    @Test
    fun fromBytesRejectsWrongLength() {
        assertThrows(IllegalArgumentException::class.java) { Pairing.fromBytes(ByteArray(15)) }
    }

    @Test
    fun generateProducesValidDistinctCodes() {
        val codes = List(200) { Pairing.generate() }
        assertTrue(codes.all { it.length == Constants.CODE_LEN && Pairing.isValid(it) })
        assertEquals(200, codes.toSet().size)
    }

    @Test
    fun normaliseStripsSeparatorsAndUppercases() {
        assertEquals(code, Pairing.normalise(" abcd-efgh jklm\tnpqr "))
    }

    @Test
    fun formatGroupsByFourAndIsIdempotent() {
        assertEquals("ABCD-EFGH-JKLM-NPQR", Pairing.format("abcdefghjklmnpqr"))
        assertEquals("ABCD-EFGH-JKLM-NPQR", Pairing.format("ABCD-EFGH-JKLM-NPQR"))
    }

    @Test
    fun formatPartialGroupsFiltersAndTruncates() {
        assertEquals("", Pairing.formatPartial(""))
        assertEquals("AB", Pairing.formatPartial("ab"))
        assertEquals("ABCD-E", Pairing.formatPartial("abcd e"))
        assertEquals("ABCD-EFGH", Pairing.formatPartial("ABCD-EFGH"))
        // I, O, 0, 1 can never be valid, so they are dropped as typed.
        assertEquals("ABCD", Pairing.formatPartial("AI0B1OCD"))
        assertEquals("ABCD-EFGH-JKLM-NPQR", Pairing.formatPartial("abcdefghjklmnpqrstuv"))
    }

    @Test
    fun formatRejectsInvalidCode() {
        assertThrows(IllegalArgumentException::class.java) { Pairing.format("ABCD") }
    }

    @Test
    fun isValidAcceptsFormattedAndRejectsBadInput() {
        assertTrue(Pairing.isValid("ABCD-EFGH-JKLM-NPQR"))
        assertTrue(Pairing.isValid(code.lowercase()))
        assertFalse(Pairing.isValid(""))
        assertFalse(Pairing.isValid("ABCD-EFGH-JKLM-NPQ"))
        assertFalse(Pairing.isValid("ABCD-EFGH-JKLM-NPQRS"))
        // Characters left out of the alphabet: I, O, 0, 1.
        assertFalse(Pairing.isValid("IBCDEFGHJKLMNPQR"))
        assertFalse(Pairing.isValid("OBCDEFGHJKLMNPQR"))
        assertFalse(Pairing.isValid("0BCDEFGHJKLMNPQR"))
        assertFalse(Pairing.isValid("1BCDEFGHJKLMNPQR"))
        // Upper-casing must not change length or sneak in valid letters.
        assertFalse(Pairing.isValid("ßBCDEFGHJKLMNPQR"))
    }

    @Test
    fun hexLengthsFitInPbkdf2Output() {
        assertTrue(Constants.ROOM_HEX_CHARS <= Constants.PBKDF2_DK_BYTES * 2)
        assertTrue(Constants.CODE_TOKEN_HEX_CHARS <= Constants.PBKDF2_DK_BYTES * 2)
        assertTrue(Constants.SALT_ROOM != Constants.SALT_AUTH)
    }
}
