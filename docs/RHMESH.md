# RHMesh (Rapid Hybrid Mesh), by rhshourav

Offline, store-and-forward messaging between phones that have already paired in TenChar. Phones hand each other
small encrypted packets as they pass; strangers carry them without being able to read them, link them, or know who
they are for. A packet disappears one hour after it was created.

Status: **Phase 1 (protocol and crypto core) is implemented and tested.** Phases 2 to 4 are specified below.

## 1. Scope and layering

RHMesh is a second transport for conversations that already exist. It adds no new key agreement and no new message
encryption: the 135-byte envelope produced by the existing Double Ratchet (X25519, HKDF, AES-256-GCM, forward secrecy,
replay rejection) is the payload. RHMesh wraps it for strangers.

| Layer | Package | Phase |
|---|---|---|
| Protocol: packet, addressing, TTL, Bloom, courier state machine | `rhmesh.protocol` | 1 (done) |
| Transport: BLE, Wi-Fi Aware, Wi-Fi Direct, foreground service | `rhmesh.transport` | 2 |
| Store: encrypted cache, journal, garbage collector | `rhmesh.store` | 3 |
| UI: radar, status pill, TTL ring, courier settings | `rhmesh.ui` | 4 |

Text only in version 1 (a long message is up to 35 packets, each independent). Photos, voice and video stay on the relay:
at 192 bytes a packet, megabytes would mean tens of thousands of packets in strangers' caches.

## 2. Packet (always 192 bytes)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | version | 1 |
| 1 | 1 | hops | transfers so far; **the only field a courier changes**; unauthenticated; not in the id; at most 16 |
| 2 | 4 | createdAt | seconds, big endian |
| 6 | 12 | nonce | random; AES-GCM nonce and tag input |
| 18 | 8 | tag | `HMAC-SHA256(tagKey, "rhmesh-tag-v1" \|\| nonce)[0..8]` |
| 26 | 151 | sealed | `AES-256-GCM(sealKey, nonce, aad, ratchetEnvelope[135])` |
| 177 | 15 | reserved | zero; refused otherwise (one byte string per packet, so one id per packet) |

`aad = version || createdAt || tag`. `id = SHA-256("rhmesh-id-v1" || version || bytes[2..177])[0..8]`.
192 bytes is one BLE GATT write at the usual MTU of 247.

## 3. Keys and addressing

Both phones of a conversation hold a 32-byte `meshSecret`. For each recipient (identified by the member id the relay already
knows) `HKDF-SHA256(meshSecret, info = "rhmesh-inbox-v1" || memberId, 64)` gives `tagKey` (first 32 bytes) and `sealKey`
(last 32). A phone's inbound keys are derived from its own member id, its outbound keys from the peer's.

Two deliberate differences from the brief's suggested `HMAC(recipientIdentityKey, timeEpoch)`:

- **Per-packet tags, not per-epoch tags.** A tag that is fixed for an hour lets any courier link every packet for one person in
  that hour. Here the tag depends on a fresh random nonce, so no two packets share anything a courier can see (the same idea
  as BLE resolvable private addresses). The recipient checks one HMAC per conversation (at most 20) per packet.
- **A secret key, not the identity public key.** Identity public keys pass through the relay at pairing, so a relay operator
  could compute tags from them. `meshSecret` is known only to the two phones.

The outer AES-GCM layer also hides the ratchet header (ratchet public key, counters). On the relay path that header is visible,
which is acceptable for one relay; on the mesh every courier would see it and could follow a conversation by that public key.

## 4. Lifetime

- A courier keeps a packet while `age < 3,600,000 ms` exactly. No grace.
- A packet dated more than 2 minutes ahead of the receiver's clock is refused. Otherwise a sender could date it next year and
  make it immortal. No packet lives longer than 1 hour + 2 minutes on an honest clock.
- Only the recipient forgives 2 more minutes (a courier whose clock runs slow). It never passes the packet on.
- Hop limit 16. A node receiving at the limit may deliver to itself but not carry on.
- Expiry deletes from the cache and from the seen set (after the recipient grace). Phase 3 adds overwrite-on-delete on disk.

Phones whose clock is wrong by more than about 2 minutes will drop mesh packets. Phase 4 shows a "check date and time" notice.

## 5. Meeting a peer

1. **Beacon (BLE advertisement):** `RhSetFingerprint`, 6 bytes: packet count (u16) and 32 bits of an order-independent XOR hash
   of the cached ids. Equal on both sides: nothing to swap, no second radio.
2. **Bloom exchange:** each side sends a filter of everything it has *handled* (held, delivered to itself, forged, evicted),
   10 bits per packet, 7 hashes, **fresh random salt every meeting**. Bit positions depend on the salt, so a false positive in one
   meeting is independent of the next and cannot be precomputed. No false negatives: nothing the peer holds is ever sent.
3. **Plan:** offer what the peer lacks, fewest hops first, then newest, up to the meeting's budget.
4. A filter that is more than 80 % full is not believed (an honest 10-bits-per-entry filter is about 50 %); the peer gets nothing.
   The decoder allocates at most 512 KiB for a filter a peer sends.

## 6. Courier policy

- Cache bounded (default 50 MB = 273,066 packets). When full, a stranger's packet replaces the stranger's packet that dies
  soonest, only if the newcomer lives longer. Your own outgoing packets are evicted last.
- Courier Mode off: carries nothing for strangers; still sends your own and receives yours.
- A courier cannot tell a damaged packet from a good one (it holds no key). The recipient does, and drops it. Spam is bounded by
  the cache limit and eviction order, not by recognition.

## 7. Phase 3 integration (designed, not yet built)

- **Establishing `meshSecret`.** `agreement.sk` is wiped after pairing, so existing chats cannot derive it. The phone with the
  smaller member id sends a new ratchet frame (type 7, 32 random bytes) once the chat is verified; both store it. If both send
  at once, the smaller member id's wins. This covers chats paired before the mesh existed, and needs state version 7.
  Adding a `Wire.Decoded` subtype also makes the `when` in `LongMessageTest` non-exhaustive: add a branch there.
- **Send:** encrypt through the ratchet, save state, then `RhSealer.seal` the same envelope the outbox holds and `inject` it.
  The relay and the mesh can carry the same envelope; the second arrival is rejected by the ratchet like any duplicate.
- **Receive:** `RhReceive.Delivered.envelope` goes to the existing `receive` path (`Wire.toBase64` makes it the string it takes).
- **Delivery receipt ("Delivered" badge):** a ratchet frame (type 8) carrying the 8-byte packet id, sent back as a mesh packet.
  Both sides compute the same id because it is content addressed.
- **Rotating `meshSecret`** through the ratchet every few weeks limits what a leaked secret exposes (header metadata only;
  message content stays protected by the ratchet).

## 8. Threat model (Phase 1)

A courier learns: that a 192-byte packet exists, when it was created (to the second), how many hops it has made, and the ids of
packets it handles. It cannot learn: sender, recipient, conversation, length, content, or whether two packets belong together.
A courier **can**: drop packets, delay them, inject junk, alter `hops`, or rewrite `createdAt` (the recipient then rejects it).
A radio observer who sees one physical phone repeatedly can still correlate the phone, not the people. Hop count and creation
time are metadata a courier sees; the hop count tells it roughly how far the packet has come.

Not independently reviewed. The ratchet it wraps carries the same warning as the rest of TenChar.

## 9. Phase 2 to 4 plan, and the risks that shape it

- **Make BLE GATT the guaranteed data path and Wi-Fi Aware the accelerator.** Packets are 192 bytes, so even a 5-second BLE
  encounter can move a hundred of them. Wi-Fi is then an optimisation, not a requirement.
- **Wi-Fi Direct is the weakest part of the brief.** From what I know, Android's P2P connection normally puts an invitation or
  consent dialog in front of the other user, which contradicts "zero setup". It will be opt-in and tested on real devices, not
  assumed. Wi-Fi Aware needs NAN-capable hardware (not every phone) and a foreground context.
- **Background discovery:** `PendingIntent` scan wakeups with a service-UUID filter; scan-result delivery while the screen is off
  is throttled by the OS and varies by vendor. Expect seconds-to-minutes discovery latency in the background.
- **Permissions:** `minSdk` is 30, so Android 11 needs the legacy Bluetooth and location permissions (with `maxSdkVersion`),
  Android 12+ the `BLUETOOTH_*` set, Android 13+ `NEARBY_WIFI_DEVICES`; the service type is `connectedDevice`.
- **Density decides everything.** A crowd of 24 delivers within the hour in simulation; a lone pair of phones in a village will not.

## 10. Tests

61 JUnit tests in `app/src/test/java/.../rhmesh/protocol/`. `RhVectorTest` compares the packet, id, Bloom filter and beacon byte for
byte against `tools/gen_mesh_vectors.py`, an independent Python implementation (OpenSSL via `cryptography`) written from the
layout above. Routing tests use the app's real Double Ratchet through A -> B -> C -> D with B and C holding no keys.
