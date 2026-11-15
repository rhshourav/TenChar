package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Encryption of one attachment (a photo, a voice message or a short video) before it is uploaded to a relay.
 *
 * Every attachment gets its own random 32-byte key. The key travels to the other phone inside the ratchet-encrypted
 * descriptor frame ([Wire.encodeAttachment]); the relay never sees it. Because a key is used for exactly one
 * attachment, nonces can be counters and need no randomness.
 *
 * Format (the file on the relay, and the copy kept on this phone):
 *   plaintext, followed by zero bytes up to a multiple of BLOB_PAD_BYTES, is cut into chunks of BLOB_CHUNK_BYTES
 *   (the last one may be shorter). Chunk i is AES-256-GCM sealed and written as ciphertext || 16-byte tag.
 *     subkey = HKDF-SHA256(salt = empty, ikm = key, info = "tenchar-blob-key-v1", 32 bytes)
 *     nonce  = 7 zero bytes || last (1 byte, 1 for the final chunk) || i (4 bytes, big endian)
 *     aad    = "tenchar-blob-v1" || kind (1 byte) || plaintext length (4 bytes, big endian)
 *   The final flag means cutting chunks off the end is detected; the counter means reordering is; the aad ties the
 *   file to the descriptor that carries its key. The relay id is derived from the key, so it is not sent separately:
 *     id = hex(HKDF-SHA256(salt = empty, ikm = key, info = "tenchar-blob-id-v1", 16 bytes))
 *
 * What the relay can still see: that an attachment was sent, when, and its size rounded up to 16 KiB.
 */
object BlobCrypto {
    private const val TAG_BYTES = 16
    private val INFO_KEY = "tenchar-blob-key-v1".toByteArray(Charsets.UTF_8)
    private val INFO_ID = "tenchar-blob-id-v1".toByteArray(Charsets.UTF_8)
    private val AAD_LABEL = "tenchar-blob-v1".toByteArray(Charsets.UTF_8)

    fun newKey(random: RandomSource): ByteArray = ByteArray(32).also(random::nextBytes)

    /** 32 lowercase hex characters: what the relay calls this attachment. */
    fun blobId(key: ByteArray): String {
        require(key.size == 32) { "bad key size" }
        return Hex.encode(Kdf.hkdf(ByteArray(0), key, INFO_ID, 16))
    }

    /** [plainSize] rounded up to a multiple of BLOB_PAD_BYTES. */
    fun paddedSize(plainSize: Int): Int {
        require(plainSize in 1..Constants.MAX_MEDIA_BYTES) { "bad attachment size" }
        val pad = Constants.BLOB_PAD_BYTES
        return (plainSize + pad - 1) / pad * pad
    }

    /** The exact length of the encrypted file for [plainSize] bytes of content. */
    fun cipherSize(plainSize: Int): Int {
        val padded = paddedSize(plainSize)
        return padded + chunkCount(padded) * TAG_BYTES
    }

    private fun chunkCount(padded: Int): Int = (padded + Constants.BLOB_CHUNK_BYTES - 1) / Constants.BLOB_CHUNK_BYTES

    private fun nonce(index: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(12).put(ByteArray(7)).put(if (last) 1 else 0).putInt(index).array()

    private fun aad(kind: Int, plainSize: Int): ByteArray =
        ByteBuffer.allocate(AAD_LABEL.size + 5).put(AAD_LABEL).put(kind.toByte()).putInt(plainSize).array()

    /** Writes the encrypted file for [plain] to [out]. Never call this twice with the same [key]. */
    fun encrypt(key: ByteArray, kind: Int, plain: ByteArray, out: OutputStream) {
        require(key.size == 32) { "bad key size" }
        val padded = paddedSize(plain.size)
        val chunks = chunkCount(padded)
        val subKey = Kdf.hkdf(ByteArray(0), key, INFO_KEY, 32)
        val aad = aad(kind, plain.size)
        try {
            for (i in 0 until chunks) {
                val from = i * Constants.BLOB_CHUNK_BYTES
                val len = minOf(Constants.BLOB_CHUNK_BYTES, padded - from)
                val piece = ByteArray(len) // zero filled: whatever is not copied below is the padding
                val real = (plain.size - from).coerceIn(0, len)
                System.arraycopy(plain, from, piece, 0, real)
                out.write(Aead.seal(subKey, nonce(i, i == chunks - 1), aad, piece))
                piece.fill(0)
            }
        } finally {
            subKey.fill(0)
        }
    }

    /**
     * The content of an encrypted file, or null if it is not exactly what [encrypt] makes for this key, kind and
     * size: a wrong key, a changed or reordered or missing chunk, extra bytes, or non-zero padding.
     */
    fun decrypt(key: ByteArray, kind: Int, plainSize: Int, input: InputStream): ByteArray? {
        if (key.size != 32 || plainSize !in 1..Constants.MAX_MEDIA_BYTES) return null
        val padded = paddedSize(plainSize)
        val chunks = chunkCount(padded)
        val subKey = Kdf.hkdf(ByteArray(0), key, INFO_KEY, 32)
        val aad = aad(kind, plainSize)
        val out = ByteArray(padded)
        try {
            for (i in 0 until chunks) {
                val from = i * Constants.BLOB_CHUNK_BYTES
                val len = minOf(Constants.BLOB_CHUNK_BYTES, padded - from)
                val sealed = ByteArray(len + TAG_BYTES)
                if (!readFully(input, sealed)) return fail(out)
                val plain = Aead.open(subKey, nonce(i, i == chunks - 1), aad, sealed)
                if (plain == null || plain.size != len) return fail(out)
                System.arraycopy(plain, 0, out, from, len)
                plain.fill(0)
            }
            if (input.read() != -1) return fail(out) // bytes after the last chunk
            for (i in plainSize until padded) if (out[i].toInt() != 0) return fail(out)
            return out.copyOf(plainSize).also { out.fill(0) }
        } finally {
            subKey.fill(0)
        }
    }

    private fun fail(buffer: ByteArray): ByteArray? {
        buffer.fill(0)
        return null
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var at = 0
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) return false
            at += n
        }
        return true
    }
}
