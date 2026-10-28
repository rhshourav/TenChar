package com.rhshourav.tenchar.crypto

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * The Double Ratchet (Signal specification, revision 1) with X25519, HKDF-SHA256,
 * HMAC-SHA256 chain keys and AES-256-GCM.
 *
 * Start-up differs from the textbook in one way that matters. Both phones know
 * the other's ephemeral key from pairing, so **either side can send first**:
 *  - the initiator uses its pairing ephemeral key as its first ratchet key and
 *    the responder's pairing ephemeral key as the remote key;
 *  - the responder derives the initiator's first receiving chain right away and
 *    then makes its own first sending chain with a fresh ratchet key.
 * That is exactly the state each side would be in after receiving the
 * other's first message, so the normal ratchet rules apply from the start.
 *
 * Guarantees: confidentiality and integrity per message, forward secrecy
 * (message keys are deleted after use), post-compromise recovery after the next
 * round trip, replay rejection (a message key is used at most once), and
 * transactional decrypt (a forged or damaged envelope changes no state).
 */
class Ratchet private constructor(
    private var rk: ByteArray,
    private var dhsPriv: ByteArray,
    private var dhsPub: ByteArray,
    private var dhr: ByteArray?,
    private var cks: ByteArray?,
    private var ckr: ByteArray?,
    private var ns: Int,
    private var nr: Int,
    private var pn: Int,
    private val skipped: LinkedHashMap<SkipKey, ByteArray>,
    private val ad: ByteArray,
    private val random: SecureRandom,
) {
    private data class SkipKey(val dh: String, val n: Int)

    /** Encrypts one frame. Advances the sending chain. Persist the state before sending the result. */
    fun encrypt(frame: ByteArray): ByteArray {
        require(frame.size == Constants.FRAME_BYTES) { "bad frame size" }
        val ck = cks ?: throw IllegalStateException("no sending chain")
        check(ns < Int.MAX_VALUE) { "chain exhausted" }
        val (nextCk, mk) = kdfCk(ck)
        val header = header(dhsPub, pn, ns)
        ck.fill(0)
        cks = nextCk
        ns += 1

        val nonce = ByteArray(Constants.NONCE_BYTES).also(random::nextBytes)
        val key = messageKey(mk)
        val sealed = Aead.seal(key, nonce, header + ad, frame)
        key.fill(0)
        mk.fill(0)
        return header + nonce + sealed
    }

    /**
     * The frame inside [envelope], or null if it is not a valid next, late or
     * out-of-order message of this conversation (forged, damaged, replayed, or
     * too far ahead). A null result leaves the state exactly as it was.
     */
    fun decrypt(envelope: ByteArray): ByteArray? {
        if (envelope.size != Constants.ENVELOPE_BYTES || envelope[0] != Wire.VERSION) return null
        val work = copy()
        val frame = try {
            work.decryptInner(envelope)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IllegalStateException) {
            null
        }
        if (frame == null) {
            work.wipe()
            return null
        }
        adopt(work)
        return frame
    }

    private fun decryptInner(envelope: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(envelope)
        val dh = envelope.copyOfRange(1, 33)
        buf.position(33)
        val pnHeader = buf.int
        val n = buf.int
        if (pnHeader < 0 || n < 0) return null
        val nonce = envelope.copyOfRange(Constants.HEADER_BYTES, Constants.HEADER_BYTES + Constants.NONCE_BYTES)
        val sealed = envelope.copyOfRange(Constants.HEADER_BYTES + Constants.NONCE_BYTES, envelope.size)
        val aad = envelope.copyOfRange(0, Constants.HEADER_BYTES) + ad

        val skipKey = SkipKey(Hex.encode(dh), n)
        val stored = skipped[skipKey]
        if (stored != null) {
            val key = messageKey(stored)
            val plain = Aead.open(key, nonce, aad, sealed)
            key.fill(0)
            if (plain == null) return null
            skipped.remove(skipKey)
            stored.fill(0)
            return plain
        }

        val current = dhr
        if (current == null || !dh.contentEquals(current)) {
            skipUntil(pnHeader)
            dhRatchet(dh)
        }
        if (n < nr) return null // an old message whose key is already used: a replay
        skipUntil(n)
        val ck = ckr ?: return null
        val (nextCk, mk) = kdfCk(ck)
        val key = messageKey(mk)
        val plain = Aead.open(key, nonce, aad, sealed)
        key.fill(0)
        mk.fill(0)
        if (plain == null) return null
        ck.fill(0)
        ckr = nextCk
        nr += 1
        return plain
    }

    /** Stores the keys of messages that were skipped in the current receiving chain. */
    private fun skipUntil(until: Int) {
        val start = ckr ?: return
        val chain = dhr ?: return
        if (until - nr > Constants.MAX_SKIP) throw IllegalStateException("too many skipped messages")
        var ck = start
        val label = Hex.encode(chain)
        while (nr < until) {
            val (next, mk) = kdfCk(ck)
            skipped[SkipKey(label, nr)] = mk
            ck.fill(0)
            ck = next
            nr += 1
        }
        ckr = ck
        while (skipped.size > Constants.MAX_SKIP) {
            val eldest = skipped.entries.iterator().next()
            eldest.value.fill(0)
            skipped.remove(eldest.key)
        }
    }

    private fun dhRatchet(peerPublic: ByteArray) {
        pn = ns
        ns = 0
        nr = 0
        dhr = peerPublic.copyOf()
        val (rk1, ckr1) = kdfRk(rk, X25519.sharedSecret(dhsPriv, peerPublic))
        dhsPriv.fill(0)
        val newPriv = X25519.generatePrivate(random)
        val newPub = X25519.publicKey(newPriv)
        val (rk2, cks2) = kdfRk(rk1, X25519.sharedSecret(newPriv, peerPublic))
        rk.fill(0); rk1.fill(0)
        rk = rk2
        ckr?.fill(0); cks?.fill(0)
        ckr = ckr1
        cks = cks2
        dhsPriv = newPriv
        dhsPub = newPub
    }

    // ---- state handling ------------------------------------------------------------------------

    private fun copy(): Ratchet = Ratchet(
        rk.copyOf(), dhsPriv.copyOf(), dhsPub.copyOf(), dhr?.copyOf(), cks?.copyOf(), ckr?.copyOf(),
        ns, nr, pn,
        LinkedHashMap<SkipKey, ByteArray>().also { m -> for ((k, v) in skipped) m[k] = v.copyOf() },
        ad.copyOf(), random,
    )

    /** Takes over the state of [work], which is a decrypted-successfully copy of this ratchet. */
    private fun adopt(work: Ratchet) {
        val oldSecrets = listOfNotNull(rk, dhsPriv, cks, ckr) + skipped.values
        rk = work.rk; dhsPriv = work.dhsPriv; dhsPub = work.dhsPub
        dhr = work.dhr; cks = work.cks; ckr = work.ckr
        ns = work.ns; nr = work.nr; pn = work.pn
        skipped.clear()
        skipped.putAll(work.skipped)
        // Shared arrays between old and new are not wiped; only arrays that were replaced.
        for (old in oldSecrets) if (!isHeldByThis(old)) old.fill(0)
    }

    private fun isHeldByThis(a: ByteArray): Boolean =
        a === rk || a === dhsPriv || a === cks || a === ckr || skipped.values.any { it === a }

    /** Overwrites every secret held. The object is unusable afterwards. */
    fun wipe() {
        rk.fill(0); dhsPriv.fill(0); cks?.fill(0); ckr?.fill(0)
        for (v in skipped.values) v.fill(0)
        skipped.clear()
    }

    fun serialize(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeByte(STATE_VERSION)
        out.write(rk)
        out.write(dhsPriv)
        out.write(dhsPub)
        writeOptional(out, dhr)
        writeOptional(out, cks)
        writeOptional(out, ckr)
        out.writeInt(ns)
        out.writeInt(nr)
        out.writeInt(pn)
        out.writeInt(skipped.size)
        for ((k, v) in skipped) {
            out.write(Hex.decode(k.dh))
            out.writeInt(k.n)
            out.write(v)
        }
        out.writeInt(ad.size)
        out.write(ad)
        out.flush()
        return bytes.toByteArray()
    }

    companion object {
        private const val STATE_VERSION = 1
        private val INFO_RK = "tenchar-ratchet-v1".toByteArray(Charsets.UTF_8)
        private val INFO_MSG = "tenchar-message-v1".toByteArray(Charsets.UTF_8)

        /**
         * The phone that is the initiator in [Handshake]. [ek] is its pairing ephemeral key
         * and [peerEk] the responder's.
         */
        fun forInitiator(sk: ByteArray, ad: ByteArray, ek: KeyPair, peerEk: ByteArray, random: SecureRandom): Ratchet {
            val (rk, cks) = kdfRk(sk, X25519.sharedSecret(ek.priv, peerEk))
            return Ratchet(
                rk, ek.priv.copyOf(), ek.pub.copyOf(), peerEk.copyOf(), cks, null, 0, 0, 0,
                LinkedHashMap(), ad.copyOf(), random,
            )
        }

        /** The responder. [peerEk] is the initiator's pairing ephemeral key. */
        fun forResponder(sk: ByteArray, ad: ByteArray, ek: KeyPair, peerEk: ByteArray, random: SecureRandom): Ratchet {
            val (rk1, ckr) = kdfRk(sk, X25519.sharedSecret(ek.priv, peerEk))
            val priv = X25519.generatePrivate(random)
            val pub = X25519.publicKey(priv)
            val (rk2, cks) = kdfRk(rk1, X25519.sharedSecret(priv, peerEk))
            rk1.fill(0)
            return Ratchet(
                rk2, priv, pub, peerEk.copyOf(), cks, ckr, 0, 0, 0,
                LinkedHashMap(), ad.copyOf(), random,
            )
        }

        fun deserialize(bytes: ByteArray, random: SecureRandom): Ratchet {
            try {
                val input = DataInputStream(ByteArrayInputStream(bytes))
                if (input.readUnsignedByte() != STATE_VERSION) throw IOException("unknown ratchet state version")
                val rk = ByteArray(32).also(input::readFully)
                val priv = ByteArray(32).also(input::readFully)
                val pub = ByteArray(32).also(input::readFully)
                val dhr = readOptional(input)
                val cks = readOptional(input)
                val ckr = readOptional(input)
                val ns = input.readInt()
                val nr = input.readInt()
                val pn = input.readInt()
                val count = input.readInt()
                if (ns < 0 || nr < 0 || pn < 0 || count < 0 || count > Constants.MAX_SKIP) throw IOException("bad ratchet state")
                val skipped = LinkedHashMap<SkipKey, ByteArray>()
                repeat(count) {
                    val dh = ByteArray(32).also(input::readFully)
                    val n = input.readInt()
                    val mk = ByteArray(32).also(input::readFully)
                    skipped[SkipKey(Hex.encode(dh), n)] = mk
                }
                val adLen = input.readInt()
                if (adLen != 64) throw IOException("bad associated data")
                val ad = ByteArray(adLen).also(input::readFully)
                if (input.available() != 0) throw IOException("trailing bytes")
                return Ratchet(rk, priv, pub, dhr, cks, ckr, ns, nr, pn, skipped, ad, random)
            } catch (e: java.io.EOFException) {
                throw IOException("truncated ratchet state", e)
            }
        }

        private fun writeOptional(out: DataOutputStream, value: ByteArray?) {
            out.writeBoolean(value != null)
            if (value != null) out.write(value)
        }

        private fun readOptional(input: DataInputStream): ByteArray? =
            if (input.readBoolean()) ByteArray(32).also(input::readFully) else null

        private fun header(pub: ByteArray, pn: Int, n: Int): ByteArray =
            ByteBuffer.allocate(Constants.HEADER_BYTES).put(Wire.VERSION).put(pub).putInt(pn).putInt(n).array()

        /** (new root key, new chain key) */
        private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
            val out = Kdf.hkdf(rk, dhOut, INFO_RK, 64)
            dhOut.fill(0)
            return out.copyOfRange(0, 32) to out.copyOfRange(32, 64).also { out.fill(0) }
        }

        /** (next chain key, message key) */
        private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> =
            Kdf.hmac(ck, byteArrayOf(0x02)) to Kdf.hmac(ck, byteArrayOf(0x01))

        private fun messageKey(mk: ByteArray): ByteArray = Kdf.hkdf(ByteArray(0), mk, INFO_MSG, 32)
    }
}
