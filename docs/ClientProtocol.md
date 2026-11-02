# TenChar client protocol

This describes what the Android app does, so it can be reviewed. The relay API is in
`relay-api.md`; the relay logic is `relay/src/core.js`.

## Roles of each secret

| Secret | Where it comes from | Who sees it |
|---|---|---|
| Pairing code (80 bits) | random, shown on one phone, typed on the other | the two people only |
| `room`, `codeToken` | PBKDF2-SHA256(code, salt, 600 000) | relay (it must) |
| `codeKey` | PBKDF2-SHA256(code, `tenchar-key-v1`, 600 000) | **never leaves the phone** |
| `memberToken` | 32 random bytes | relay stores only its SHA-256 |
| Identity key `ik`, ephemeral key `ek` | X25519, new for every pairing | public halves go through the relay |
| Ratchet state, message history | derived / typed | encrypted at rest, see Storage |

## Pairing (per relay, on every configured relay in parallel)

1. `hello`: join the room with `commit = SHA-256("tenchar-commit-v1" || ik || ek || memberId)`.
2. Wait until the room has two members. Only then `reveal` `ik` and `ek`. The relay refuses a
   reveal before two commitments exist and refuses one that does not match its commitment.
3. Wait for the other side's reveal. Check it against that side's commitment locally.
4. If more than one relay completed, both must report identical keys for the other phone,
   otherwise pairing is cancelled ("relays disagree").

The phone with the smaller `memberId` is the initiator.

## Key agreement

Four Diffie-Hellman values (identity x ephemeral, both directions, plus ek x ek and ik x ik),
combined with HKDF-SHA256. The salt is `codeKey`; the info string is the full transcript
(both identity keys, both ephemeral keys, both member ids). So a relay that swaps keys cannot
compute the result, and neither can anyone who did not hear the code.

The 8-digit verification number is HKDF(result, "tenchar-verify-v1" || transcript), 7 bytes mod 10^8.
Because keys are committed before they are revealed, an attacker cannot grind keys to make
two numbers collide: the chance per attempt is 1 in 10^8.

## Double Ratchet

X25519 DH ratchet, HMAC-SHA256 chain keys (0x01 message key, 0x02 next chain key),
HKDF-SHA256 root key, AES-256-GCM messages. Message key = HKDF(mk, "tenchar-message-v1").
Associated data = header (41 bytes) || initiator ik || responder ik.

Both phones know the other's ephemeral key from pairing, so either side may send first:
the initiator's first ratchet key is its pairing `ek`; the responder derives the matching
receiving chain at once and then makes its first sending chain with a fresh key.

Rules enforced: version byte 1 only; at most `MAX_SKIP` (200) skipped keys per gap and in total;
a message key is used once; decrypt works on a copy and commits only on success, so forged or
damaged envelopes change nothing; a replay is rejected because its key is gone.

## Wire format

Frame (encrypted, 66 bytes): `[type 1][len 1][payload 64, zero padded]`.

- Type 1, text: the payload is up to 64 bytes of UTF-8, a whole message.
- Type 2, fragment: the payload is `[group 2, big endian][index 1][count 1][up to 60 bytes of UTF-8]`.
  The pieces of one longer message share a group; all but the last carry exactly 60 bytes; `count` is 2 to 35.
  The receiver joins them in index order, then checks the whole as strict UTF-8.

Envelope (135 bytes, 180 base64 chars): `[ver 1][ratchet pub 32][prev chain len 4][n 4][nonce 12][ciphertext 66][tag 16]`.
`prev chain len` and `n` are big-endian. Every envelope is the same size, and a frame is always one envelope,
so the relay is unchanged. The relay sees the ratchet public key, counters and timing, and now also how many
envelopes a message used (see Known limits).

## Message length

A message is at most 500 user-perceived characters and 2,048 bytes (500 four-byte emoji is 2,000). Text up to
64 bytes is one type-1 frame, byte for byte what it was before long messages existed. Longer text is fragmented:
34 frames for 500 emoji, 35 at most. The composer holds up to 5,000 characters (ten messages); longer-than-one-message
text is cut into messages on grapheme boundaries, at a space near the end of a piece where there is one.

The limit is the sender's rule. A receiver checks only that a frame is well formed and the reassembled text is at most
2,048 bytes, valid UTF-8, not blank, with no control or bidirectional-override characters, never a character count:
platforms with different Unicode versions count some Bangla clusters differently, and a message the ratchet has already
consumed must not be dropped over that.

Pieces received so far are saved with the conversation (state version 3), up to 16 unfinished messages; the oldest is
dropped beyond that. A message with a missing piece never appears.

## Delivery

Send: encrypt, **save state**, then POST to the first relay that accepts it (failover to the next).
Receive: poll every relay the conversation was paired on. Handle messages, **save state**, then ACK.
Duplicates (the same envelope arriving from two relays, or a retry) are rejected by the ratchet and dropped.
There is no push: polling runs only while the app is open.

## Storage

```
Android Keystore AES-256-GCM key   (StrongBox if present; every use needs the system unlock prompt;
                                    destroyed by Android when the secure lock screen is removed)
   wraps  data key (32 random bytes, tc_dek.bin)
   encrypts tc_state.bin (AES-256-GCM, fresh nonce per write, atomic replace)
```

The data key is in memory only while unlocked. Locking overwrites it, the ratchet state and
the outbox. The JVM cannot overwrite `String`s (the member token) or guarantee that the garbage
collector left no copy, so memory wiping is best effort.

## Known limits

- Not independently audited. X25519 is a port of TweetNaCl's ladder, checked against RFC 7748
  vectors, not a reviewed library.
- The relay sees IP addresses, timing, counters and message count, and the length of a message to within about 60 bytes
  (the number of envelopes it needed). Short messages, one envelope each, still look alike.
- The ratchet keeps at most 200 skipped message keys. Frames normally arrive in order; one that arrives more than 200
  places early is refused and its message is lost. A full 5,000-character Bangla draft is about 230 frames.
- One conversation, two people, no push, no history sync, no key backup.