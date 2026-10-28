package com.rhshourav.tenchar

import com.rhshourav.tenchar.crypto.X25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class X25519Test {
    private fun hex(s: String) = Hex.decode(s)

    // RFC 7748, section 5.2.
    @Test
    fun rfc7748ScalarMultVectors() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            Hex.encode(
                X25519.scalarMult(
                    hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                    hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
                ),
            ),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            Hex.encode(
                X25519.scalarMult(
                    hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                    hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
                ),
            ),
        )
    }

    // RFC 7748, section 5.2: the iterated test, one and 1000 rounds.
    @Test
    fun rfc7748IteratedVectors() {
        var k = ByteArray(32).also { it[0] = 9 }
        var u = k.copyOf()
        for (i in 1..1000) {
            val next = X25519.scalarMult(k, u)
            u = k
            k = next
            if (i == 1) assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", Hex.encode(k))
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", Hex.encode(k))
    }

    // RFC 7748, section 6.1.
    @Test
    fun rfc7748DiffieHellman() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPriv = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePub = X25519.publicKey(alicePriv)
        val bobPub = X25519.publicKey(bobPriv)
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", Hex.encode(alicePub))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", Hex.encode(bobPub))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, Hex.encode(X25519.sharedSecret(alicePriv, bobPub)))
        assertEquals(shared, Hex.encode(X25519.sharedSecret(bobPriv, alicePub)))
    }

    @Test
    fun randomKeysAgree() {
        val rnd = java.security.SecureRandom()
        repeat(20) {
            val a = X25519.generatePrivate(rnd)
            val b = X25519.generatePrivate(rnd)
            assertTrue(
                X25519.sharedSecret(a, X25519.publicKey(b)).contentEquals(X25519.sharedSecret(b, X25519.publicKey(a))),
            )
        }
    }

    @Test
    fun lowOrderPointsAreRefused() {
        val priv = X25519.generatePrivate(java.security.SecureRandom())
        // The all-zero point and the point of order 1 both give an all-zero secret.
        assertThrows(IllegalArgumentException::class.java) { X25519.sharedSecret(priv, ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) {
            X25519.sharedSecret(priv, ByteArray(32).also { it[0] = 1 })
        }
    }
}
