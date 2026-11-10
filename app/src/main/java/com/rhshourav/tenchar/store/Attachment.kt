package com.rhshourav.tenchar.store

import com.rhshourav.tenchar.crypto.BlobCrypto

/**
 * A photo, voice message or short video that belongs to a message. The content is not here: it is an encrypted file in
 * [MediaStore], and [key] opens it. The key is saved with the chat, inside the encrypted state, so it is exactly as
 * protected as the messages themselves.
 *
 * [state] means: for something this phone sends, PENDING is "not yet uploaded"; for something it receives, PENDING is
 * "not yet downloaded". READY is done either way. FAILED is shown with a tap to retry.
 */
class Attachment(
    val kind: Int,
    val key: ByteArray,
    /** Length of the content in bytes (not of the padded, encrypted file). */
    val size: Int,
    val width: Int,
    val height: Int,
    /** Voice and video length in tenths of a second. */
    val durationDs: Int,
    /** Voice only: [com.rhshourav.tenchar.Constants.WAVE_BARS] bars of 4 bits, two to a byte. Empty otherwise. */
    val wave: ByteArray,
    var state: Int,
) {
    /** The name of the encrypted file, here and on the relay. Derived from the key, so it is never stored. */
    val id: String by lazy { BlobCrypto.blobId(key) }

    fun wipe() = key.fill(0)

    companion object {
        const val PENDING = 0
        const val READY = 1
        const val FAILED = 2
    }
}
