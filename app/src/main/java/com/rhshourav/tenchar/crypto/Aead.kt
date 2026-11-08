package com.rhshourav.tenchar.crypto

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a 96-bit nonce and a 128-bit tag. Every key used with this
 * in the messaging path is a one-time message key, so a random nonce is safe.
 */
object Aead {
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Returns ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12) { "bad key or nonce size" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Returns the plaintext, or null if the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        require(key.size == 32 && nonce.size == 12) { "bad key or nonce size" }
        if (sealed.size < TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
