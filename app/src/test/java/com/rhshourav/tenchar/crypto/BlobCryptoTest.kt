package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The attachment format (see [BlobCrypto]). The "independent implementation" check is relay/../tools/blob_ref.py, which
 * decrypts a file made here with a separate Python implementation and reproduces it byte for byte.
 */
class BlobCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private val chunk = Constants.BLOB_CHUNK_BYTES + 16

    private fun pattern(n: Int, seed: Int): ByteArray {
        var x = seed
        return ByteArray(n) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            (x ushr 16).toByte()
        }
    }

    private fun encrypt(kind: Int, plain: ByteArray, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { BlobCrypto.encrypt(k, kind, plain, it) }.toByteArray()

    private fun decrypt(kind: Int, size: Int, data: ByteArray, k: ByteArray = key): ByteArray? =
        BlobCrypto.decrypt(k, kind, size, ByteArrayInputStream(data))

    @Test
    fun roundTripsAroundEveryBoundary() {
        for (size in listOf(1, 2, 16383, 16384, 16385, 65535, 65536, 65537, 81920, 200_000, 1_048_577, Constants.MAX_MEDIA_BYTES)) {
            val plain = pattern(size, size)
            val sealed = encrypt(Constants.KIND_IMAGE, plain)
            assertEquals("length of $size", BlobCrypto.cipherSize(size), sealed.size)
            assertTrue("fits the relay cap at $size", sealed.size <= Constants.MAX_BLOB_BYTES)
            assertArrayEquals("round trip of $size", plain, decrypt(Constants.KIND_IMAGE, size, sealed))
        }
    }

    @Test
    fun sizesAreRoundedUpSoTheRelayDoesNotSeeExactLengths() {
        assertEquals(16384, BlobCrypto.paddedSize(1))
        assertEquals(16384, BlobCrypto.paddedSize(16384))
        assertEquals(32768, BlobCrypto.paddedSize(16385))
        assertEquals(BlobCrypto.cipherSize(1000), BlobCrypto.cipherSize(16000))
    }

    @Test
    fun anyChangedByteIsRejected() {
        val plain = pattern(200_000, 9)
        val sealed = encrypt(Constants.KIND_VIDEO, plain)
        for (pos in listOf(0, 100, chunk - 1, chunk, 2 * chunk + 5, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            assertNull("flip at $pos", decrypt(Constants.KIND_VIDEO, plain.size, bad))
        }
    }

    @Test
    fun truncationExtensionAndReorderingAreRejected() {
        val plain = pattern(200_000, 9)
        val sealed = encrypt(Constants.KIND_VIDEO, plain)
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size, sealed.copyOf(3 * chunk)))
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size, sealed.copyOf(sealed.size - 1)))
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size, sealed + byteArrayOf(0)))
        val swapped = sealed.copyOf()
        System.arraycopy(sealed, chunk, swapped, 0, chunk)
        System.arraycopy(sealed, 0, swapped, chunk, chunk)
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size, swapped))
        // The beginning of a longer file is not a valid shorter file: the final-chunk flag is part of every nonce.
        val longer = encrypt(Constants.KIND_VIDEO, pattern(300_000, 2))
        assertNull(decrypt(Constants.KIND_VIDEO, 200_000, longer.copyOf(sealed.size)))
    }

    @Test
    fun theFileIsBoundToItsKeyKindAndSize() {
        val plain = pattern(200_000, 9)
        val sealed = encrypt(Constants.KIND_VIDEO, plain)
        assertNotNull(decrypt(Constants.KIND_VIDEO, plain.size, sealed))
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size, sealed, ByteArray(32) { 1 }))
        assertNull(decrypt(Constants.KIND_VOICE, plain.size, sealed))
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size + 1, sealed))
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size + 20_000, sealed))
        assertNull(decrypt(Constants.KIND_VIDEO, plain.size, sealed, ByteArray(31)))
    }

    @Test
    fun theRelayNameIsDerivedFromTheKeyAndRevealsNothingAboutIt() {
        val id = BlobCrypto.blobId(key)
        assertTrue(id.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(id, BlobCrypto.blobId(ByteArray(32) { 5 }))
        assertEquals(id, BlobCrypto.blobId(key.copyOf()))
    }
}
