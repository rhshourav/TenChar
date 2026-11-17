package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.Hex
import com.rhshourav.tenchar.crypto.Kdf
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.LABEL_INBOX
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.MEMBER_ID_BYTES
import com.rhshourav.tenchar.rhmesh.protocol.RhMeshConstants.MESH_SECRET_BYTES

/**
 * The two keys that make a packet addressable to one person and unreadable to everyone else:
 *  - [tagKey] lets the recipient recognise "this packet is for me" from the 8-byte tag, and nobody else.
 *  - [sealKey] is the outer AES-GCM key. It hides the ratchet envelope, including its header (the ratchet public key and
 *    counters), which would otherwise let any courier link every message of a conversation by that public key.
 *
 * Both come from HKDF over the conversation's 32-byte mesh secret and the recipient's member id, so each direction of a
 * conversation has its own pair. Anyone who does not hold the mesh secret (every courier) can derive neither.
 */
class RhInboxKeys private constructor(internal val tagKey: ByteArray, internal val sealKey: ByteArray) {
    fun wipe() {
        tagKey.fill(0)
        sealKey.fill(0)
    }

    override fun toString(): String = "RhInboxKeys(redacted)"

    companion object {
        /** The keys for packets addressed to [recipientMemberIdHex] (32 hex characters, the id the relay knows them by). */
        fun derive(meshSecret: ByteArray, recipientMemberIdHex: String): RhInboxKeys {
            require(meshSecret.size == MESH_SECRET_BYTES) { "the mesh secret is $MESH_SECRET_BYTES bytes" }
            val id = Hex.decode(recipientMemberIdHex)
            require(id.size == MEMBER_ID_BYTES) { "a member id is ${MEMBER_ID_BYTES * 2} hex characters" }
            val okm = Kdf.hkdf(ByteArray(0), meshSecret, LABEL_INBOX.toByteArray(Charsets.UTF_8) + id, 64)
            try {
                return RhInboxKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
            } finally {
                okm.fill(0)
            }
        }
    }
}

/** What this phone holds for one conversation: the keys for packets it receives and the keys for packets it sends. */
class MeshChannel(val inbound: RhInboxKeys, val outbound: RhInboxKeys) {
    fun wipe() {
        inbound.wipe()
        outbound.wipe()
    }

    companion object {
        /**
         * [meshSecret] is shared by the two phones of one conversation and by no one else (see docs/RHMESH.md for how it is
         * established through the existing ratchet). What I send is addressed to the peer's inbox and what I receive to mine,
         * so the peer derives the same two pairs with the arguments swapped.
         */
        fun derive(meshSecret: ByteArray, localMemberIdHex: String, peerMemberIdHex: String): MeshChannel {
            require(!Hex.decode(localMemberIdHex).contentEquals(Hex.decode(peerMemberIdHex))) { "member ids must differ" }
            return MeshChannel(
                inbound = RhInboxKeys.derive(meshSecret, localMemberIdHex),
                outbound = RhInboxKeys.derive(meshSecret, peerMemberIdHex),
            )
        }
    }
}
