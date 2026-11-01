package com.rhshourav.tenchar.crypto

import java.security.SecureRandom

/**
 * Where every random byte in the protocol comes from. Production uses [SystemRandom];
 * tests supply fixed bytes so a whole conversation can be compared byte for byte with
 * an independent implementation.
 *
 * Every draw asks for exactly the number of bytes it uses (32 for a private key, 12 for a
 * nonce, 16 for a member id), so a scripted source can check sizes as well as order.
 */
fun interface RandomSource {
    fun nextBytes(out: ByteArray)
}

object SystemRandom : RandomSource {
    private val random = SecureRandom()

    override fun nextBytes(out: ByteArray) = random.nextBytes(out)
}