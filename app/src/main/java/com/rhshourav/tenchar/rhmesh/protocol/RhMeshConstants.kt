package com.rhshourav.tenchar.rhmesh.protocol

import com.rhshourav.tenchar.Constants

/**
 * The only place RHMesh protocol numbers are defined. Everything else reads these.
 *
 * A mesh packet is always exactly [PACKET_BYTES] bytes, whatever it carries:
 *
 *   offset  size  field
 *        0     1  version         = 1
 *        1     1  hops            transfers so far; the ONLY field a courier may change (not authenticated, not in the id)
 *        2     4  createdAt       seconds since 1970, big endian
 *        6    12  nonce           random; it is the AES-GCM nonce and the input of the routing tag
 *       18     8  tag             HMAC-SHA256(recipient tagKey, "rhmesh-tag-v1" || nonce)[0..8]
 *       26   151  sealed          AES-256-GCM(recipient sealKey, nonce, aad, ratchet envelope 135) = 135 + 16
 *      177    15  reserved        zero in version 1; refused if not
 *
 *   aad = version || createdAt || tag       (so a courier cannot move the timestamp or re-label the packet)
 *   id  = SHA-256("rhmesh-id-v1" || version || bytes[2..177])[0..8]      (content addressed; excludes hops)
 *
 * 192 bytes fits a single BLE GATT write at the usual negotiated MTU of 247 (244 bytes of payload).
 */
object RhMeshConstants {
    const val VERSION = 1

    // ---- time ------------------------------------------------------------------------------------------------------

    /** A packet lives exactly one hour from the moment its sender created it. */
    const val TTL_MS = 3_600_000L

    /** A packet claiming to be created further ahead than this on my clock is refused (otherwise it could never expire). */
    const val FUTURE_SKEW_MS = 120_000L

    /** Only the recipient gets this extra time, to forgive a courier's clock that runs behind. Couriers expire at exactly [TTL_MS]. */
    const val ACCEPT_GRACE_MS = 120_000L

    // ---- packet layout ---------------------------------------------------------------------------------------------

    const val PACKET_BYTES = 192
    const val OFF_VERSION = 0
    const val OFF_HOPS = 1
    const val OFF_CREATED = 2
    const val CREATED_BYTES = 4
    const val OFF_NONCE = 6
    const val NONCE_BYTES = 12
    const val OFF_TAG = OFF_NONCE + NONCE_BYTES
    const val TAG_BYTES = 8
    const val OFF_SEALED = OFF_TAG + TAG_BYTES

    /** One existing ratchet envelope (135 bytes) plus the 16-byte AES-GCM tag of the outer layer. */
    const val SEALED_BYTES = Constants.ENVELOPE_BYTES + Constants.TAG_BYTES
    const val OFF_RESERVED = OFF_SEALED + SEALED_BYTES
    const val RESERVED_BYTES = PACKET_BYTES - OFF_RESERVED

    /** The most transfers a packet may make. A node that receives it at the limit may deliver it to itself but not pass it on. */
    const val MAX_HOPS = 16

    // ---- keys ------------------------------------------------------------------------------------------------------

    const val MESH_SECRET_BYTES = 32
    const val MEMBER_ID_BYTES = 16

    const val LABEL_INBOX = "rhmesh-inbox-v1"
    const val LABEL_TAG = "rhmesh-tag-v1"
    const val LABEL_ID = "rhmesh-id-v1"

    // ---- Bloom filter ----------------------------------------------------------------------------------------------

    const val BLOOM_BITS_PER_ENTRY = 10
    const val BLOOM_HASHES = 7
    const val BLOOM_MIN_WORDS = 4
    /** 65,536 words = 4 Mi bits = 512 KiB: room for about 400,000 packets, far beyond any cache this app will hold. */
    const val BLOOM_MAX_WORDS = 65_536
    const val BLOOM_HEADER_BYTES = 12
    const val BLOOM_FORMAT = 1

    /** A filter fuller than this is not one an honest node built at [BLOOM_BITS_PER_ENTRY] bits per entry (that gives about 50 %). */
    const val BLOOM_MAX_PLAUSIBLE_FILL = 0.80

    // ---- courier policy defaults -----------------------------------------------------------------------------------

    /** 50 MB of packets at 192 bytes each. */
    const val DEFAULT_CACHE_PACKETS = 50 * 1024 * 1024 / PACKET_BYTES
    const val SEEN_PER_CACHE_SLOT = 4
}
