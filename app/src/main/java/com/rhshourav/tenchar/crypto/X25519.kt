package com.rhshourav.tenchar.crypto

import java.security.SecureRandom

/**
 * X25519 (RFC 7748), a Kotlin port of the TweetNaCl `crypto_scalarmult` ladder.
 *
 * Why this exists: Android's platform provider only has X25519 from API 33, and
 * this project pins every dependency. The routine has no secret-dependent
 * branches or table lookups (conditional swaps are done with masks). The JVM
 * gives no hard constant-time guarantee, so treat timing resistance as best effort.
 *
 * Checked against the RFC 7748 test vectors in X25519Test.
 */
object X25519 {
    const val KEY_BYTES = 32

    private val BASE_POINT = ByteArray(32).also { it[0] = 9 }
    private val C121665 = LongArray(16).also { it[0] = 0xDB41; it[1] = 1 }

    fun generatePrivate(random: SecureRandom): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE_POINT)

    /**
     * The shared secret. Throws if the result is all zero, which happens only
     * for a low-order peer key (an attack, never an honest peer).
     */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val out = scalarMult(privateKey, peerPublic)
        var acc = 0
        for (b in out) acc = acc or b.toInt()
        if (acc == 0) throw IllegalArgumentException("low-order public key")
        return out
    }

    internal fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        require(n.size == KEY_BYTES && p.size == KEY_BYTES) { "keys are 32 bytes" }
        val z = n.copyOf()
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()

        val x = LongArray(16)
        unpack(x, p)
        val a = LongArray(16)
        val b = x.copyOf()
        val c = LongArray(16)
        val d = LongArray(16)
        val e = LongArray(16)
        val f = LongArray(16)
        a[0] = 1
        d[0] = 1

        for (i in 254 downTo 0) {
            val r = ((z[i ushr 3].toInt() and 0xFF) ushr (i and 7)) and 1
            sel(a, b, r)
            sel(c, d, r)
            add(e, a, c)
            sub(a, a, c)
            add(c, b, d)
            sub(b, b, d)
            mul(d, e, e)
            mul(f, a, a)
            mul(a, c, a)
            mul(c, b, e)
            add(e, a, c)
            sub(a, a, c)
            mul(b, a, a)
            sub(c, d, f)
            mul(a, c, C121665)
            add(a, a, d)
            mul(c, c, a)
            mul(a, d, f)
            mul(d, b, x)
            mul(b, e, e)
            sel(a, b, r)
            sel(c, d, r)
        }
        val cInv = LongArray(16)
        invert(cInv, c)
        mul(a, a, cInv)
        val out = ByteArray(32)
        pack(out, a)
        z.fill(0)
        return out
    }

    // Field arithmetic modulo 2^255 - 19 in 16 limbs of 16 bits (signed longs).

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            val next = if (i < 15) i + 1 else 0
            o[next] += c - 1 + (if (i == 15) 37 * (c - 1) else 0L)
            o[i] -= c shl 16
        }
    }

    /** Swaps p and q when b is 1, does nothing when b is 0, with no branch on b. */
    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val mask = (b - 1).inv().toLong()
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(o: ByteArray, n: LongArray) {
        val m = LongArray(16)
        val t = n.copyOf()
        carry(t); carry(t); carry(t)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun unpack(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) {
            o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        }
        o[15] = o[15] and 0x7fff
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o)
        carry(o)
    }

    private fun invert(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }
}
