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

Tested here (`npm test`, 50 tests): the protocol logic, including values checked
against independent Python computations; the Node server over real HTTP,
including restart persistence; and the Worker plus Durable Object code against a
**fake** Durable Object store that enforces the 128-key delete limit.

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
SQLite migration; there is nothing else to configure.

Costs and limits depend on your Cloudflare plan; check the current Workers and
Durable Objects pricing and free-tier limits before relying on it.

**Add rate limiting.** The relay has none. Without it anyone can create rooms
until your quota runs out. Use Cloudflare's rate-limiting rules on
`/v1/rooms/*/hello` at minimum.

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
| `PUSH_HOSTS` | `ntfy.sh` | hosts that notifications may be sent to; `none` turns push off |

Persistence rewrites a room's whole file after each change. That is fine for a
two-person messenger and would not scale to many rooms.

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

## Push notifications (optional)

A member can register an address that gets a short wake-up when a message is queued for them
(`POST /v1/rooms/<room>/push` with `{"url": "https://ntfy.sh/<topic>"}`; an empty `url` removes it). The relay sends
that address the fixed text "New message", never the sender, the room or the content, at most once per 10 seconds.

Because this makes the relay send requests to an address a client chose, it is locked down:

* only hosts on the allow-list: the `PUSH_HOSTS` environment variable (comma separated; default `ntfy.sh`;
  `none` turns push off, and a relay with push off answers `501 push_unavailable`);
* https only, one path segment of 16 to 64 letters, digits, `_` or `-` (a long random topic), and no credentials,
  port, query or fragment; no redirects are followed; the request gives up after 5 seconds;
* the registered address is never returned by any call, including `state`.

On Cloudflare set it with `wrangler secret put PUSH_HOSTS` or a `[vars]` entry in `wrangler.toml`; on Render add the
variable in the dashboard. A relay deployed before this change answers `404` to `/push`, and the app says so.
Redeploy both relays to enable notifications.

## Privacy

The relay stores token hashes, public keys, commits, ciphertext envelopes,
timestamps and, if a member turned notifications on, their push address (see the API document). It never logs requests, addresses, room ids
or tokens. The only log lines are the listening port at startup and a warning
when `DATA_DIR` is unset. The host still sees IP
addresses and timing, and Cloudflare or Render keep their own logs that this
code cannot control.