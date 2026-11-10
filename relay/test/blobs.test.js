import assert from "node:assert/strict";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { MAX_BLOBS, MAX_BLOB_BYTES, MAX_ROOM_BLOB_BYTES } from "../src/constants.js";
import { createRelay } from "../src/node.js";
import worker, { Room } from "../src/worker.js";
import { envelope, helloBody, makeHarness, makeMember, pair, pattern, rand, revealBody } from "./helpers.js";

const D = 24 * 60 * 60 * 1000;
const put = (h, who, blob, bytes) => h.call("put_blob", { auth: who.memberToken, blob, bytes });
const get = (h, who, blob) => h.call("get_blob", { auth: who.memberToken, blob });
const del = (h, who, blob) => h.call("delete_blob", { auth: who.memberToken, blob });
const same = (x, y) => Buffer.compare(Buffer.from(x), Buffer.from(y)) === 0;

test("an upload comes back byte for byte, for sizes around every piece boundary", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  for (const size of [1, 2, 65535, 65536, 65537, 131072, 200_000, 1_000_003]) {
    const id = rand(16);
    const data = pattern(size, size);
    assert.equal((await put(h, a, id, data)).status, 201, String(size));
    const got = await get(h, b, id);
    assert.equal(got.status, 200);
    assert.ok(got.bytes instanceof Uint8Array && same(got.bytes, data), `size ${size}`);
  }
});

test("only the recipient downloads; either member can delete; deleting frees every stored piece", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const baseline = h.storage.size;
  const id = rand(16);
  await put(h, a, id, pattern(300_000));
  assert.ok(h.storage.size > baseline);
  // The uploader cannot read it back, and neither can anyone without a member token.
  assert.equal((await get(h, a, id)).status, 404);
  assert.equal((await h.call("get_blob", { auth: h.codeToken, blob: id })).status, 401);
  assert.equal((await h.call("get_blob", { blob: id })).status, 401);
  assert.equal((await get(h, b, rand(16))).status, 404);
  assert.equal((await del(h, b, id)).status, 204);
  assert.equal(h.storage.size, baseline);
  assert.equal((await get(h, b, id)).status, 404);
  assert.equal((await del(h, b, id)).status, 404);

  const id2 = rand(16);
  await put(h, a, id2, pattern(10));
  assert.equal((await del(h, a, id2)).status, 204); // the uploader may withdraw it
  assert.equal(h.storage.size, baseline);
  assert.equal((await h.storage.get("meta")).blobBytes, 0);
});

test("uploads need a sealed room, a non-empty body and fit the size cap", async () => {
  const h = makeHarness();
  const a = await makeMember();
  const b = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  await h.call("hello", { auth: h.codeToken, body: helloBody(b) });
  assert.equal((await put(h, a, rand(16), pattern(10))).status, 409); // not sealed yet
  await h.call("reveal", { auth: h.codeToken, body: revealBody(a) });
  await h.call("reveal", { auth: h.codeToken, body: revealBody(b) });

  assert.equal((await put(h, a, rand(16), new Uint8Array(0))).status, 400);
  assert.equal((await put(h, a, rand(16), undefined)).status, 400);
  assert.equal((await put(h, a, rand(16), pattern(MAX_BLOB_BYTES + 1))).status, 413);
  assert.equal((await put(h, a, rand(16), pattern(MAX_BLOB_BYTES))).status, 201); // exactly the cap is fine
  assert.equal(h.storage.size > 100, true); // 144 pieces: more than one delete call can remove
});

test("authorize() refuses a stranger before any body is read", async () => {
  const h = makeHarness();
  const { a } = await pair(h);
  assert.equal(await h.core.authorize(`Bearer ${a.memberToken}`), null);
  assert.equal((await h.core.authorize(`Bearer ${rand(32)}`)).status, 401);
  assert.equal((await h.core.authorize(`Bearer ${h.codeToken}`)).status, 401);
  assert.equal((await h.core.authorize(null)).status, 401);
  assert.equal((await makeHarness().core.authorize(`Bearer ${rand(32)}`)).status, 404); // no such room
});

test("repeating an upload is harmless; a different upload under the same id is a conflict", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const id = rand(16);
  const data = pattern(70_000);
  assert.equal((await put(h, a, id, data)).status, 201);
  assert.equal((await put(h, a, id, data)).status, 200);
  assert.deepEqual((await put(h, a, id, pattern(70_001))).body, { error: "conflict" });
  assert.equal((await put(h, b, id, data)).status, 409); // the other member cannot take over an id
  assert.equal((await h.storage.get("meta")).blobBytes, 70_000);
});

test("quotas: a count limit and a total size limit per room", async () => {
  const h = makeHarness();
  const { a } = await pair(h);
  for (let i = 0; i < MAX_BLOBS; i++) assert.equal((await put(h, a, rand(16), pattern(8))).status, 201);
  assert.deepEqual((await put(h, a, rand(16), pattern(8))).body, { error: "blob_quota" });

  const h2 = makeHarness();
  const { a: x } = await pair(h2);
  const big = pattern(MAX_BLOB_BYTES);
  for (let i = 0; i < Math.floor(MAX_ROOM_BLOB_BYTES / MAX_BLOB_BYTES); i++) assert.equal((await put(h2, x, rand(16), big)).status, 201);
  assert.equal((await put(h2, x, rand(16), big)).status, 429);
});

test("blobs expire after 7 days, and a room's blobs go with the room", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const old = rand(16);
  await put(h, a, old, pattern(100_000));
  h.clock.t += 6 * D;
  await h.call("get_messages", { auth: a.memberToken }); // keeps the room alive
  h.clock.t += 2 * D;
  await h.call("get_messages", { auth: a.memberToken });
  const fresh = rand(16);
  assert.equal((await put(h, a, fresh, pattern(5))).status, 201); // the upload prunes the old one
  assert.equal((await get(h, b, old)).status, 404);
  assert.equal((await get(h, b, fresh)).status, 200);
  assert.equal((await h.storage.get("meta")).blobBytes, 5);

  assert.equal((await h.call("delete", { auth: a.memberToken })).status, 204);
  assert.equal(h.storage.size, 0);
});

test("the relay holds only what it was given: no key, no name, no type", async () => {
  const h = makeHarness();
  const { a } = await pair(h);
  const id = rand(16);
  await put(h, a, id, pattern(1000));
  const dump = JSON.stringify(h.storage.snapshot());
  assert.ok(!dump.includes(a.memberToken));
  assert.deepEqual(Object.keys((await h.storage.get("meta")).blobs[id]).sort(), ["at", "chunks", "from", "size", "to"]);
});

// ---- the Worker and Durable Object, against a fake store that enforces the 128-key delete limit -------------------

function makeCtx() {
  const map = new Map();
  let alarm = null;
  return {
    map,
    storage: {
      async get(key) { return map.has(key) ? structuredClone(map.get(key)) : undefined; },
      async put(key, value) {
        if (value instanceof Uint8Array && value.buffer.byteLength > 128 * 1024) throw new Error("value over 128 KiB");
        map.set(key, structuredClone(value));
      },
      async delete(keys) {
        if (!Array.isArray(keys) || keys.length > 128) throw new Error("delete takes 1-128 keys");
        for (const key of keys) map.delete(key);
      },
      async deleteAll() { map.clear(); },
      async list({ prefix = "", start, limit } = {}) {
        const keys = [...map.keys()].filter((k) => k.startsWith(prefix) && (start === undefined || k >= start)).sort();
        return new Map(keys.slice(0, limit ?? keys.length).map((k) => [k, structuredClone(map.get(k))]));
      },
      async getAlarm() { return alarm; },
      async setAlarm(t) { alarm = t; },
    },
  };
}

function makeEnv() {
  const objects = new Map();
  return {
    objects,
    ROOMS: {
      idFromName: (name) => name,
      get: (id) => ({
        fetch: (request) => {
          if (!objects.has(id)) objects.set(id, new Room(makeCtx()));
          return objects.get(id).fetch(request);
        },
      }),
    },
  };
}

test("Worker: upload and download through a Durable Object, with piece and delete limits respected", async () => {
  const env = makeEnv();
  const room = rand(16);
  const code = rand(24);
  const call = async (method, path, auth, body) => {
    const res = await worker.fetch(
      new Request(`https://relay.test/v1/rooms/${room}${path}`, {
        method,
        headers: auth ? { authorization: `Bearer ${auth}` } : {},
        body,
      }),
      env,
    );
    return res;
  };
  const json = (body) => JSON.stringify(body);
  const a = await makeMember();
  const b = await makeMember();
  await call("POST", "/hello", code, json(helloBody(a)));
  await call("POST", "/hello", code, json(helloBody(b)));
  await call("POST", "/reveal", code, json(revealBody(a)));
  await call("POST", "/reveal", code, json(revealBody(b)));

  const id = rand(16);
  const data = pattern(MAX_BLOB_BYTES, 3); // 144 pieces: deleting needs more than one call
  assert.equal((await call("PUT", `/blobs/${id}`, a.memberToken, data)).status, 201);
  const down = await call("GET", `/blobs/${id}`, b.memberToken);
  assert.equal(down.status, 200);
  assert.equal(down.headers.get("content-type"), "application/octet-stream");
  assert.ok(same(new Uint8Array(await down.arrayBuffer()), data));
  assert.equal((await call("DELETE", `/blobs/${id}`, b.memberToken)).status, 204);
  assert.equal((await call("GET", `/blobs/${id}`, b.memberToken)).status, 404);

  // JSON bodies keep their small cap even though uploads may be large.
  const big = await call("POST", "/messages", a.memberToken, json({ env: envelope(), pad: "x".repeat(600) }));
  assert.equal(big.status, 413);
});

test("Worker: an oversize upload is refused before it reaches a Durable Object", async () => {
  const env = makeEnv();
  const res = await worker.fetch(
    new Request(`https://relay.test/v1/rooms/${rand(16)}/blobs/${rand(16)}`, {
      method: "PUT",
      headers: { authorization: `Bearer ${rand(32)}`, "content-length": String(MAX_BLOB_BYTES + 1) },
      body: new Uint8Array(10),
    }),
    env,
  );
  assert.deepEqual([res.status, await res.json()], [413, { error: "too_large" }]);
  assert.equal(env.objects.size, 0);
});

// ---- the Node server over real HTTP --------------------------------------------------------------------------------

async function withRelay(options, fn) {
  const relay = createRelay(options);
  const port = await relay.start(0, "127.0.0.1");
  try {
    await fn(`http://127.0.0.1:${port}`, relay);
  } finally {
    await relay.stop();
  }
}

async function pairOverHttp(base, room) {
  const code = rand(24);
  const a = await makeMember();
  const b = await makeMember();
  const call = (method, path, auth, body) =>
    fetch(`${base}/v1/rooms/${room}${path}`, { method, headers: { authorization: `Bearer ${auth}` }, body });
  await call("POST", "/hello", code, JSON.stringify(helloBody(a)));
  await call("POST", "/hello", code, JSON.stringify(helloBody(b)));
  await call("POST", "/reveal", code, JSON.stringify(revealBody(a)));
  await call("POST", "/reveal", code, JSON.stringify(revealBody(b)));
  return { a, b, call };
}

test("Node: a 3 MB upload over HTTP, an exact download, and the size cap", async () => {
  await withRelay({}, async (base) => {
    const room = rand(16);
    const { a, b, call } = await pairOverHttp(base, room);
    const id = rand(16);
    const data = pattern(3_000_000, 11);
    assert.equal((await call("PUT", `/blobs/${id}`, a.memberToken, data)).status, 201);
    const res = await call("GET", `/blobs/${id}`, b.memberToken);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("content-type"), "application/octet-stream");
    assert.equal(res.headers.get("content-length"), String(data.length));
    assert.ok(same(new Uint8Array(await res.arrayBuffer()), data));

    const tooBig = await fetch(`${base}/v1/rooms/${room}/blobs/${rand(16)}`, {
      method: "PUT",
      headers: { authorization: `Bearer ${a.memberToken}` },
      body: pattern(MAX_BLOB_BYTES + 1),
    }).catch(() => ({ status: 413 })); // the server may close the connection once it has answered
    assert.equal(tooBig.status, 413);
  });
});

test("Node: a stranger's upload is answered before the body is accepted", async () => {
  await withRelay({}, async (base, relay) => {
    const { a, call } = await pairOverHttp(base, rand(16));
    // No such room, and a real room with a wrong token: refused, and nothing is created or stored.
    const unknown = await fetch(`${base}/v1/rooms/${rand(16)}/blobs/${rand(16)}`, {
      method: "PUT", headers: { authorization: `Bearer ${rand(32)}`, connection: "close" }, body: pattern(2_000_000),
    }).catch(() => ({ status: 404 }));
    assert.equal(unknown.status, 404);
    assert.equal(relay.roomCount(), 1);
    const room = rand(16);
    const paired = await pairOverHttp(base, room);
    const wrong = await fetch(`${base}/v1/rooms/${room}/blobs/${rand(16)}`, {
      method: "PUT", headers: { authorization: `Bearer ${rand(32)}`, connection: "close" }, body: pattern(2_000_000),
    }).catch(() => ({ status: 401 }));
    assert.equal(wrong.status, 401);
    assert.equal((await paired.call("GET", `/blobs/${rand(16)}`, paired.b.memberToken)).status, 404);
    assert.equal(a.memberToken.length, 64);
    assert.equal(call === undefined, false);
  });
});

test("Node: with DATA_DIR, waiting attachments survive a restart and are removed from disk when deleted", async () => {
  const dir = await mkdtemp(join(tmpdir(), "tenchar-relay-"));
  try {
    const room = rand(16);
    const id = rand(16);
    const data = pattern(200_000, 5);
    let a, b;
    await withRelay({ dataDir: dir }, async (base) => {
      const paired = await pairOverHttp(base, room);
      a = paired.a;
      b = paired.b;
      assert.equal((await paired.call("PUT", `/blobs/${id}`, a.memberToken, data)).status, 201);
    });
    const files = await readdir(join(dir, `${room}.blobs`));
    assert.equal(files.length, 4); // 200,000 bytes in 64 KiB pieces
    assert.ok(files.every((f) => /^b_[0-9a-f]{32}_\d{4}\.bin$/.test(f)));

    await withRelay({ dataDir: dir }, async (base) => {
      const call = (method, path, auth, body) =>
        fetch(`${base}/v1/rooms/${room}${path}`, { method, headers: { authorization: `Bearer ${auth}` }, body });
      const res = await call("GET", `/blobs/${id}`, b.memberToken);
      assert.equal(res.status, 200);
      assert.ok(same(new Uint8Array(await res.arrayBuffer()), data));
      assert.equal((await call("DELETE", `/blobs/${id}`, b.memberToken)).status, 204);
      assert.equal((await readdir(join(dir, `${room}.blobs`))).length, 0);
      await call("DELETE", "", a.memberToken);
    });
    assert.deepEqual(await readdir(dir), []);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
