# TenChar relay API, version 1

Status: **draft for review.** The original spec was cut off before the relay
section, so this API is derived from spec section 3 (pairing and handshake) and
section 4 (message protocol). Nothing in the Android app calls it yet. Review
it before the client is written; changing it later costs less than changing the
client.

The relay is a dumb mailbox. It never sees plaintext, never learns the pairing
code, and verifies only what it can verify without secrets.

## Conventions

- Base path `/v1`. All bodies are JSON, UTF-8, at most **512 bytes**
  (`MAX_BODY_BYTES`). Larger requests get `413`.
- `room` is 32 lowercase hex characters (`hex(PBKDF2(code, "tenchar-room-v1"))[0..32]`).
- `memberId` is 32 hex (16 random bytes). Keys, hashes and commits are lowercase
  hex of raw bytes: public keys 64 hex, SHA-256 values 64 hex.
- Authentication is `Authorization: Bearer <token>`, lowercase hex:
  - 48 hex characters: the **codeToken** (24 bytes). Valid only until the room is
    sealed.
  - 64 hex characters: a **memberToken** (32 random bytes, chosen by the phone).
- Errors are `{"error": "<code>"}`. Every response has `Cache-Control: no-store`.
- `204` responses have no body.

## What the relay stores per room

| Item | Why |
|---|---|
| `SHA-256(codeToken)` | to admit the two phones during pairing, without keeping the token |
| per member: `memberId`, `SHA-256(memberToken)`, `commit`, and after reveal `ik`, `ek` | pairing, and so each side can read the peer's public keys |
| `sealed` flag, `createdAt`, `lastActive` | expiry |
| per recipient: queue of 135-byte envelopes with a sequence number and timestamp | delivery |

Identity and ephemeral keys are public keys. The relay holds no private key and
no token in the clear.

## Pairing

```
A: POST /hello   (codeToken)   -> room created, A is member 1
B: POST /hello   (codeToken)   -> B is member 2
both: GET /state (codeToken)   until both commits are present
both: POST /reveal             -> after the second reveal the room is sealed
both: GET /state (memberToken) until both reveals are present
```

### `POST /v1/rooms/{room}/hello`

Auth: codeToken. Body:

```json
{"memberId": "<32 hex>", "memberTokenHash": "<64 hex>", "commit": "<64 hex>"}
```

`memberTokenHash = SHA-256(memberToken)`,
`commit = SHA-256("tenchar-commit-v1" || IKpub || EKpub || memberId)` with raw
bytes (ASCII string, 32, 32, 16 bytes).

The first valid call creates the room. `200 {"members": 1|2}`.
Repeating the identical call is a no-op (`200`). Errors: `400 bad_request`,
`401 unauthorized` (wrong codeToken), `409 full` (two other members already
joined), `409 conflict` (same `memberId` or token hash with different data),
`410 gone` (room sealed).

### `GET /v1/rooms/{room}/state`

Auth: codeToken before sealing, memberToken at any time.

```json
{"sealed": false, "expiresAt": 1790000000000,
 "members": [{"memberId": "...", "commit": "...", "ik": null, "ek": null}]}
```

`expiresAt` (ms since epoch, pairing deadline) is `null` once sealed. `ik` and
`ek` are `null` until revealed.

### `POST /v1/rooms/{room}/reveal`

Auth: codeToken before sealing, or the member's own memberToken. Body:

```json
{"memberId": "<32 hex>", "ik": "<64 hex>", "ek": "<64 hex>"}
```

Allowed only when both members have posted commits (`409 not_ready`). The relay
checks that the keys hash to the stored commit (`400 commit_mismatch`), which
stops junk and nothing more: **clients must verify the peer's commit
themselves**, because a malicious relay can skip this check. When the second
reveal arrives the room is sealed and the codeToken stops working (`410`).
Response `200 {"sealed": true|false}`. Repeating the same call is a no-op.

## Messaging (sealed rooms only; memberToken)

An envelope is 135 bytes (41-byte header + 12-byte nonce + 66-byte frame +
16-byte tag), standard Base64 without line breaks: exactly 180 characters.

### `POST /v1/rooms/{room}/messages`

Body `{"env": "<180 base64 chars>"}`. Queues it for the other member.
`201 {"ok": true}`. Errors: `400 bad_request` (wrong length or alphabet),
`409 not_sealed`, `429 queue_full` (1000 waiting messages).

### `GET /v1/rooms/{room}/messages?after=<n>`

Returns up to 100 messages addressed to the caller with id greater than `n`
(default 0), oldest first:

```json
{"messages": [{"id": 7, "env": "<base64>"}], "more": false}
```

Reading never deletes. Messages are never returned to their sender.

### `POST /v1/rooms/{room}/ack`

Body `{"upTo": <n>}`. Deletes the caller's queued messages with id <= n.
`200 {"removed": <count>}`. **Send this only after the messages have been
decrypted (or stored) and the ratchet state persisted**, as spec section 4
requires. There is no delivery receipt to the sender: the relay learns that the
recipient fetched, as it learns everything about timing, and tells nobody.

## Ending a room

### `DELETE /v1/rooms/{room}`

Auth: codeToken before sealing, memberToken after. Deletes everything.
`204`. Used for "Doesn't match" and "Forget conversation". The other phone sees
`404 not_found` on its next request.

## Other

`GET /v1/health` returns `200 {"ok": true}` with no auth.

Unknown paths return `404 not_found`; wrong methods `405 method_not_allowed`.
A room that does not exist or has expired returns `404 not_found` on every
route except `hello`.

## Lifetimes

| Item | Lifetime |
|---|---|
| Room, before sealing | 24 hours from creation (`PAIRING_WINDOW_HOURS`), then deleted |
| Room, after sealing | deleted after 7 days (`RETENTION_DAYS`) with no authenticated request |
| Queued message | 7 days, then deleted, or earlier on `ack` |

The 7-day idle expiry of a sealed room is a choice made here, not in the spec.
The app's background sync counts as activity, so an installed app keeps its
room alive. A room that expires makes both phones see `404`; the app should say
the conversation ended and offer to pair again.

## Not provided

- **Rate limiting.** Add Cloudflare rate-limiting rules, or a reverse proxy in
  front of the Node server. Without it, anyone can create rooms until storage
  or quota runs out.
- **Delivery guarantees.** If the Render deployment runs without a disk, a
  restart loses all rooms and messages. See `relay/README.md`.
- **Hiding metadata.** The relay and its host see IP addresses, request timing,
  room ids, message counts and sizes (constant), and when each member's
  public keys appeared.
- **Reuse protection after expiry.** Once a room is deleted, the same code can
  create a new room. The app must not re-use a code after the pairing window.
