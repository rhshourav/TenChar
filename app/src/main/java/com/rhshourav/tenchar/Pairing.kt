package com.rhshourav.tenchar

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The pairing code is a one-time rendezvous secret. It is not an encryption
 * key: it only lets two phones find the same relay room and authenticate to it
 * until both have joined.
 */
object Pairing {
    /** Each symbol uses 5 bits, which is why the alphabet must have 32 symbols. */
    private const val SYMBOL_MASK = 0x1F

    /**
     * Values derived from a code. [toString] never prints them.
     * [room] and [codeToken] go to the relay. [codeKey] never leaves the phone:
     * it is mixed into the handshake, so the relay cannot take part in it.
     */
    class Derived(val room: String, val codeToken: String, val codeKey: ByteArray) {
        fun wipe() = codeKey.fill(0)

        override fun toString(): String = "Derived(redacted)"
    }

    /** A new random code, 16 symbols with no separators. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(Constants.CODE_LEN)
        try {
            random.nextBytes(bytes)
            return fromBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Maps one random byte to one symbol using its low 5 bits. 256 is a
     * multiple of 32, so every symbol is equally likely and there is no modulo
     * bias.
     */
    internal fun fromBytes(bytes: ByteArray): String {
        val alphabet = Constants.CODE_ALPHABET
        require(alphabet.length == SYMBOL_MASK + 1) { "alphabet must have 32 symbols" }
        require(bytes.size == Constants.CODE_LEN) { "wrong number of bytes" }
        val out = CharArray(Constants.CODE_LEN)
        for (i in bytes.indices) {
            out[i] = alphabet[bytes[i].toInt() and SYMBOL_MASK]
        }
        return out.concatToString()
    }

    /** Upper-cases and drops hyphens and whitespace. Does not check validity. */
    fun normalise(input: String): String = buildString(input.length) {
        for (c in input) {
            if (c == '-' || c.isWhitespace()) continue
            // Per-character upper-casing keeps the length (no "ß" -> "SS").
            append(c.uppercaseChar())
        }
    }

    fun isValid(input: String): Boolean {
        val code = normalise(input)
        return code.length == Constants.CODE_LEN && code.all { it in Constants.CODE_ALPHABET }
    }

    /**
     * Formats what has been typed so far as XXXX-XXXX-..., dropping anything
     * that can never be part of a code and anything beyond CODE_LEN symbols.
     */
    fun formatPartial(input: String): String =
        normalise(input)
            .filter { it in Constants.CODE_ALPHABET }
            .take(Constants.CODE_LEN)
            .chunked(Constants.CODE_GROUP_LEN)
            .joinToString("-")

    /** XXXX-XXXX-XXXX-XXXX. Throws IllegalArgumentException for an invalid code. */
    fun format(input: String): String {
        require(isValid(input)) { "invalid pairing code" }
        return normalise(input).chunked(Constants.CODE_GROUP_LEN).joinToString("-")
    }

    /**
     * PBKDF2 is deliberately slow, so it runs on a background dispatcher.
     *   room      = hex(PBKDF2(code, SALT_ROOM))[0 until ROOM_HEX_CHARS]
     *   codeToken = hex(PBKDF2(code, SALT_AUTH))[0 until CODE_TOKEN_HEX_CHARS]
     *   codeKey   = PBKDF2(code, SALT_KEY), all PBKDF2_DK_BYTES bytes
     * The three run in parallel.
     */
    suspend fun derive(input: String): Derived {
        require(isValid(input)) { "invalid pairing code" }
        return withContext(Dispatchers.Default) {
            val password = normalise(input).toCharArray()
            try {
                coroutineScope {
                    val room = async { hexPrefix(password, Constants.SALT_ROOM, Constants.ROOM_HEX_CHARS) }
                    val token = async { hexPrefix(password, Constants.SALT_AUTH, Constants.CODE_TOKEN_HEX_CHARS) }
                    val key = async {
                        pbkdf2Sha256(
                            password,
                            Constants.SALT_KEY.toByteArray(Charsets.UTF_8),
                            Constants.PBKDF2_ITERATIONS,
                            Constants.PBKDF2_DK_BYTES,
                        )
                    }
                    Derived(room.await(), token.await(), key.await())
                }
            } finally {
                password.fill('\u0000')
            }
        }
    }

    internal fun hexPrefix(
        password: CharArray,
        salt: String,
        hexChars: Int,
        iterations: Int = Constants.PBKDF2_ITERATIONS,
    ): String {
        require(hexChars in 1..(Constants.PBKDF2_DK_BYTES * 2)) { "bad prefix length" }
        val key = pbkdf2Sha256(password, salt.toByteArray(Charsets.UTF_8), iterations, Constants.PBKDF2_DK_BYTES)
        try {
            return Hex.encode(key).substring(0, hexChars)
        } finally {
            key.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA256 through the platform provider (JCA). */
    internal fun pbkdf2Sha256(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyBytes: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyBytes * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
