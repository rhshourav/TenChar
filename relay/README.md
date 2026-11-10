# TenChar relay

A small mailbox for end-to-end encrypted envelopes. The API is in
[`../docs/relay-api.md`](../docs/relay-api.md). The same logic
(`src/core.js`) runs in two places:

| Where | Entry | State |
|---|---|---|
| Cloudflare Workers | `src/worker.js` | one Durable Object per room (SQLite-backed) |
| Node 22+ (Render, anywhere) | `src/node.js` | memory, optionally mirrored to files in `DATA_DIR` |

There are no npm dependencies. Run the tests with `npm test` (Node 22 or newer).

## What was tested, and what was not

Tested here (`npm test`): the protocol logic, including values checked against
independent Python computations; the Node server over real HTTP, including
restart persistence of messages **and waiting attachments**; and the Worker plus
Durable Object code against a **fake** Durable Object store that enforces the
128-key delete limit and the 128 KiB value limit (a 9 MiB attachment is 144
stored pieces, so deleting one needs more than one call).

**Not tested: the real Cloudflare runtime and the real Render deployment.**
There is no `workerd` or Cloudflare account here. Expect to find at least one
small deployment problem. Run the checks under "Smoke test" after deploying.

## Deploy to Cloudflare

```
cd relay
npm install --save-dev wrangler      # then pin the exact version it installs
npx wrangler login
npx wrangler deploy
```

The Worker URL (for example `https://tenchar-relay.<you>.workers.dev`) is the
relay URL the app will use. `wrangler.toml` declares the Durable Object and its
SQLite migration; there is nothing else to configure. (`PUSH_HOSTS` is no longer
used; remove it if you set it.)

Costs and limits depend on your Cloudflare plan; check the current Workers and
Durable Objects pricing and free-tier limits before relying on it.

**Add rate limiting.** The relay has none. Without it anyone can create rooms
until your quota runs out. Use Cloudflare's rate-limiting rules on
`/v1/rooms/*/hello` at minimum. Attachments make this matter more: each
upload is up to 9 MiB written to Durable Object storage (and 64 writes of
64 KiB pieces). Check the current Durable Objects storage and write limits for
your plan.

## Deploy to Render

`render.yaml` at the repository root is a Blueprint: in the Render dashboard
choose New, then Blueprint, and select this repository. It builds `relay/` and
runs `node src/node.js`, health-checked at `/v1/health`.

**The default is the free plan with no disk, and that loses data.** A free
instance sleeps when idle and restarts often. Every restart drops all rooms and
all queued messages: a pairing in progress fails, and unread messages vanish. It
is fine for trying the app, not for using it. For real use, switch to a paid
plan with a disk (see the commented lines in `render.yaml`) and set
`DATA_DIR` to the disk's mount path. Check Render's current plan names, prices
and limits first; they change.

Environment variables:

| Name | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | Render sets this itself |
| `DATA_DIR` | unset | if set, rooms are saved there as `<room>.json` (mode 0600) and reloaded at start |
| `MAX_ROOMS` | `10000` | new rooms are refused (`503`) beyond this |

Persistence rewrites a room's small JSON file after each change. Attachment
pieces are written once, as separate files in `DATA_DIR/<room>.blobs/`, and
removed when the other phone deletes them, so a message never rewrites a photo.
That is fine for a two-person messenger and would not scale to many rooms.

**Memory.** Waiting attachments are also held in memory (up to 32 MiB per room,
9 MiB each, 64 per room). A relay for a few people is fine on a small instance;
a public one should lower `MAX_ROOMS`. Uploads may take up to two minutes
(`server.requestTimeout`), because a phone on a slow link needs time to send
9 MiB.

## Smoke test after deploying

Replace `$RELAY`:

```
curl -i $RELAY/v1/health                                   # 200 {"ok":true}
curl -i $RELAY/v1/rooms/00000000000000000000000000000000/state   # 404 not_found
head -c 600 /dev/zero | tr '\0' x | curl -i -X POST --data-binary @- \
  $RELAY/v1/rooms/00000000000000000000000000000000/hello         # 413 too_large
```

Then pair two test members using the sequence in `test/node.test.js`
("handshake and a message over real HTTP") as a script.

## Message alerts (optional)

A member can register the SHA-256 hash of a random **watch token**
(`POST /v1/rooms/<room>/watch` with `{"tokenHash": "<64 hex>"}`; an empty
`tokenHash` removes it). With the token itself, and nothing else, a phone can ask
`GET /v1/rooms/<room>/peek` and get `{"waiting": n, "latest": n}`: how many
messages wait for that member, and the newest sequence number ever queued. That
is all it can do. It cannot read, acknowledge, delete or send, it does not keep
the room alive, and the token is never stored (only its hash, which no call
returns). The Android app uses this from a small foreground service to show a
fixed "New message" alert. No third-party push service is involved, and **the
relay never makes an outgoing request on anyone's behalf**: there is no
`PUSH_HOSTS`, no allow-list and nothing to configure. A relay deployed before
this change answers `404` to `/watch`, and the app says so.

## Attachments (photos, voice messages, short video)

The relay stores opaque ciphertext that one member uploads for the other. It
never sees a key (the key travels inside the ratchet-encrypted message), a file
name or a type.

| Call | Who | Result |
|---|---|---|
| `PUT /v1/rooms/<room>/blobs/<id>` (raw bytes) | a member | `201`; `200` if the same upload is repeated; `409 conflict` for a different upload under that id |
| `GET /v1/rooms/<room>/blobs/<id>` | the **recipient** only | the bytes (`404` for anyone else, including the uploader) |
| `DELETE /v1/rooms/<room>/blobs/<id>` | either member | `204` |

`<id>` is 32 lowercase hex characters, derived from the key by the sender. Limits:
9 MiB per attachment (`413 too_large`), 64 waiting and 32 MiB in total per room
(`429 blob_quota`); an attachment not picked up within 7 days is deleted. The
exact format of the encrypted file is documented in `BlobCrypto.kt` and
cross-checked against an independent Python implementation.

## Privacy

The relay stores token hashes, public keys, commits, ciphertext envelopes,
ciphertext attachments (with their size and arrival time) and, if a member turned
alerts on, the hash of a watch token. It never logs requests, addresses, room ids
or tokens. The only log lines are the listening port at startup and a warning
when `DATA_DIR` is unset. The host still sees IP
addresses and timing, and Cloudflare or Render keep their own logs that this
code cannot control.