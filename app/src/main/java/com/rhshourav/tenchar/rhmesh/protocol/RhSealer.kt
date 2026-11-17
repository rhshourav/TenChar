package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.Constants
import com.rhshourav.tenchar.crypto.Aead
import com.rhshourav.tenchar.crypto.Kdf
import com.rhshourav.tenchar.crypto.RandomSource
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.LABEL_TAG
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.NONCE_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.SEALED_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.TAG_BYTES
import java.security.MessageDigest

/**
 * Turns a ratchet envelope into a mesh packet and back.
 *
 * Layering. The envelope is exactly what the Double Ratchet already produces for the relay (135 bytes: forward secrecy,
 * post-compromise recovery, replay rejection). This outer layer adds only what strangers carrying the packet need:
 *  - an unlinkable address: a fresh random nonce and a tag the recipient alone can recognise, so two packets for the same
 *    person look unrelated;
 *  - header hiding: the ratchet public key and counters inside the envelope are encrypted, so they cannot be used to
 *    follow a conversation from courier to courier;
 *  - a creation time that cannot be edited (it is authenticated), which is what the one-hour life is counted from.
 *
 * It invents no new key agreement and no new message encryption.
 */
object RhSealer {
    private val TAG_LABEL = LABEL_TAG.toByteArray(Charsets.UTF_8)

    /** The 8-byte tag of the packet with this [nonce] for this recipient. */
    fun tagFor(inbox: RhInboxKeys, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES) { "a nonce is $NONCE_BYTES bytes" }
        return Kdf.hmac(inbox.tagKey, TAG_LABEL, nonce).copyOf(TAG_BYTES)
    }

    /**
     * Wraps one ratchet [envelope] for the owner of [to]. Call it after the ratchet state that produced the envelope has
     * been saved (the same rule as posting to a relay). Draws exactly [NONCE_BYTES] bytes from [random].
     */
    fun seal(envelope: ByteArray, to: RhInboxKeys, nowMs: Long, random: RandomSource): RhPacket {
        require(envelope.size == Constants.ENVELOPE_BYTES) { "an envelope is ${Constants.ENVELOPE_BYTES} bytes" }
        // Check nowMs itself: integer division truncates toward zero, so -1 ms would otherwise quietly become second 0.
        require(nowMs >= 0L) { "the clock is before 1970" }
        val createdAtSec = nowMs / 1000L
        require(createdAtSec <= 0xFFFF_FFFFL) { "the clock is past the year 2106, which the 32-bit time field cannot store" }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val tag = tagFor(to, nonce)
        val sealed = Aead.seal(to.sealKey, nonce, RhPacket.aad(createdAtSec, tag), envelope)
        check(sealed.size == SEALED_BYTES) { "unexpected sealed size" }
        return RhPacket.assemble(createdAtSec, nonce, tag, sealed)
    }

    /** Is this packet addressed to the owner of [inbox]? A cheap check (one HMAC) that does not touch the ciphertext. */
    fun isAddressedTo(packet: RhPacket, inbox: RhInboxKeys): Boolean =
        MessageDigest.isEqual(tagFor(inbox, packet.nonce), packet.tag)

    /**
     * The ratchet envelope inside [packet], or null if the tag is not [inbox]'s or the packet was altered (nonce, time,
     * tag or ciphertext). The caller wipes the result when done.
     */
    fun open(packet: RhPacket, inbox: RhInboxKeys): ByteArray? {
        if (!isAddressedTo(packet, inbox)) return null
        val envelope = Aead.open(inbox.sealKey, packet.nonce, RhPacket.aad(packet.createdAtSec, packet.tag), packet.sealed)
            ?: return null
        if (envelope.size != Constants.ENVELOPE_BYTES) {
            envelope.fill(0)
            return null
        }
        return envelope
    }
}
