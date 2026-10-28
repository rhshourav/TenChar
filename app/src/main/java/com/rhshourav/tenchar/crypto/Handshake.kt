package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import java.security.SecureRandom

class KeyPair(val priv: ByteArray, val pub: ByteArray) {
    fun wipe() = priv.fill(0)

    companion object {
        fun generate(random: SecureRandom): KeyPair {
            val priv = X25519.generatePrivate(random)
            return KeyPair(priv, X25519.publicKey(priv))
        }
    }
}

/** The keys one phone makes for one pairing: a long-term-for-this-chat identity key and an ephemeral key. */
class LocalKeys(val ik: KeyPair, val ek: KeyPair) {
    fun wipe() {
        ik.wipe()
        ek.wipe()
    }
}

/** Everything one phone presents to the relay while pairing. */
class LocalMember(
    val memberId: String,
    val memberToken: String,
    val tokenHash: String,
    val keys: LocalKeys,
    val commit: String,
) {
    companion object {
        fun create(random: SecureRandom): LocalMember {
            val id = ByteArray(16).also(random::nextBytes)
            val token = ByteArray(32).also(random::nextBytes)
            val keys = LocalKeys(KeyPair.generate(random), KeyPair.generate(random))
            val commit = Handshake.commit(keys.ik.pub, keys.ek.pub, id)
            return LocalMember(
                memberId = Hex.encode(id),
                memberToken = Hex.encode(token),
                tokenHash = Hex.encode(Kdf.sha256(token)),
                keys = keys,
                commit = Hex.encode(commit),
            )
        }
    }
}

/**
 * The key agreement between two phones that both know each other's identity
 * and ephemeral public keys (the relay carried them, commit-then-reveal).
 *
 * It is the X3DH shape with four Diffie-Hellman values and no signed prekey,
 * because both ephemeral keys are fresh and both sides are online while
 * pairing. The pairing code, which the relay never sees, is mixed in as the
 * HKDF salt, so a relay that swaps keys also cannot compute the result.
 * The 8-digit verification number is derived from the result, so it still
 * catches a swap if the code was ever leaked.
 */
object Handshake {
    private const val INFO_SK = "tenchar-x3dh-v1"
    private const val INFO_VERIFY = "tenchar-verify-v1"

    class Agreement(
        /** 32-byte root secret for the ratchet. */
        val sk: ByteArray,
        /** Identity keys, initiator first. Bound into every message as associated data. */
        val ad: ByteArray,
        val initiator: Boolean,
        /** "dddd dddd" */
        val verification: String,
    ) {
        fun wipe() = sk.fill(0)
    }

    /** SHA-256 over "tenchar-commit-v1" || ik || ek || memberId. The relay checks the same value. */
    fun commit(ik: ByteArray, ek: ByteArray, memberId: ByteArray): ByteArray =
        Kdf.sha256("tenchar-commit-v1".toByteArray(Charsets.UTF_8), ik, ek, memberId)

    /** The phone with the smaller member id is the initiator. Both phones agree because ids are distinct. */
    fun isInitiator(localMemberId: String, peerMemberId: String): Boolean {
        require(localMemberId != peerMemberId) { "member ids must differ" }
        return localMemberId < peerMemberId
    }

    fun agree(
        local: LocalKeys,
        localMemberId: String,
        peerIk: ByteArray,
        peerEk: ByteArray,
        peerMemberId: String,
        codeKey: ByteArray,
    ): Agreement {
        require(peerIk.size == 32 && peerEk.size == 32 && codeKey.size == Constants.PBKDF2_DK_BYTES)
        val initiator = isInitiator(localMemberId, peerMemberId)

        val dh1: ByteArray
        val dh2: ByteArray
        val dh3: ByteArray
        val dh4: ByteArray
        if (initiator) {
            dh1 = X25519.sharedSecret(local.ik.priv, peerEk)
            dh2 = X25519.sharedSecret(local.ek.priv, peerIk)
            dh3 = X25519.sharedSecret(local.ek.priv, peerEk)
            dh4 = X25519.sharedSecret(local.ik.priv, peerIk)
        } else {
            dh1 = X25519.sharedSecret(local.ek.priv, peerIk)
            dh2 = X25519.sharedSecret(local.ik.priv, peerEk)
            dh3 = X25519.sharedSecret(local.ek.priv, peerEk)
            dh4 = X25519.sharedSecret(local.ik.priv, peerIk)
        }

        val ikI = if (initiator) local.ik.pub else peerIk
        val ekI = if (initiator) local.ek.pub else peerEk
        val ikR = if (initiator) peerIk else local.ik.pub
        val ekR = if (initiator) peerEk else local.ek.pub
        val idI = Hex.decode(if (initiator) localMemberId else peerMemberId)
        val idR = Hex.decode(if (initiator) peerMemberId else localMemberId)

        val ikm = ByteArray(32) { 0xFF.toByte() } + dh1 + dh2 + dh3 + dh4
        val info = INFO_SK.toByteArray(Charsets.UTF_8) + ikI + ekI + ikR + ekR + idI + idR
        val sk = Kdf.hkdf(codeKey, ikm, info, 32)

        val digits = Kdf.hkdf(ByteArray(0), sk, INFO_VERIFY.toByteArray(Charsets.UTF_8) + info, 7)
        var v = 0L
        for (b in digits) v = (v shl 8) or (b.toLong() and 0xFF)
        val text = (v % 100_000_000L).toString().padStart(8, '0')

        ikm.fill(0); dh1.fill(0); dh2.fill(0); dh3.fill(0); dh4.fill(0)
        return Agreement(sk, ikI + ikR, initiator, text.substring(0, 4) + " " + text.substring(4))
    }
}
