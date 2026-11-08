package com.rhshourav.tenchar.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256, SHA-256 and HKDF-SHA256 (RFC 5869) on the platform provider. */
object Kdf {
    private const val HMAC = "HmacSHA256"
    private const val HASH_LEN = 32

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "empty HMAC key" }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** An empty salt means HashLen zero bytes, as the RFC says. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "bad HKDF length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var done = 0
        var counter = 1
        while (done < length) {
            block = hmac(prk, block, info, byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - done)
            block.copyInto(out, done, 0, n)
            done += n
            counter++
        }
        prk.fill(0)
        return out
    }
}
