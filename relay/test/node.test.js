import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdtemp, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { createRelay } from "../src/node.js";
import { envelope, helloBody, makeMember, rand, revealBody } from "./helpers.js";

async function withRelay(options, fn) {
  const relay = createRelay(options);
  const port = await relay.start(0, "127.0.0.1");
  try {
    await fn(`http://127.0.0.1:${port}`, relay);
  } finally {
    await relay.stop();
  }
}

const api = (base, room) => async (method, path, { auth, body, raw, headers } = {}) => {
  const res = await fetch(`${base}/v1/rooms/${room}${path}`, {
    method,
    headers: { ...(auth ? { authorization: `Bearer ${auth}` } : {}), ...headers },
    body: raw ?? (body === undefined ? undefined : JSON.stringify(body)),
  });
  const text = await res.text();
  return { status: res.status, headers: res.headers, json: text ? JSON.parse(text) : null };
};

test("health, routing and headers", async () => {
  await withRelay({}, async (base) => {
    const health = await fetch(`${base}/v1/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { ok: true });
    assert.equal(health.headers.get("cache-control"), "no-store");
    assert.equal((await fetch(`${base}/v1/health`, { method: "POST" })).status, 405);
    assert.equal((await fetch(`${base}/nope`)).status, 404);
    assert.equal((await fetch(`${base}/v1/rooms/zzzz/state`)).status, 404);
    const room = api(base, "a".repeat(32));
    assert.equal((await room("GET", "/hello")).status, 405);
    assert.equal((await room("GET", "/state", { auth: rand(24) })).status, 404);
  });
});

test("handshake and a message over real HTTP", async () => {
  await withRelay({}, async (base, relay) => {
    const call = api(base, rand(16));
    const code = rand(24);
    const a = await makeMember();
    const b = await makeMember();
    assert.equal((await call("POST", "/hello", { auth: code, body: helloBody(a) })).json.members, 1);
    assert.equal((await call("POST", "/hello", { auth: code, body: helloBody(b) })).json.members, 2);
    assert.equal((await call("POST", "/reveal", { auth: code, body: revealBody(a) })).json.sealed, false);
    assert.equal((await call("POST", "/reveal", { auth: code, body: revealBody(b) })).json.sealed, true);
    assert.equal((await call("GET", "/state", { auth: code })).status, 410);

    const sent = await call("POST", "/messages", { auth: a.memberToken, body: { env: envelope("Q") } });
    assert.equal(sent.status, 201);
    assert.equal(sent.headers.get("content-type"), "application/json");
    const got = await call("GET", "/messages?after=0", { auth: b.memberToken });
    assert.deepEqual(got.json, { messages: [{ id: 1, env: envelope("Q") }], more: false });
    assert.equal((await call("POST", "/ack", { auth: b.memberToken, body: { upTo: 1 } })).json.removed, 1);

    const gone = await call("DELETE", "", { auth: a.memberToken });
    assert.equal(gone.status, 204);
    assert.equal(gone.json, null);
    assert.equal((await call("GET", "/state", { auth: b.memberToken })).status, 404);
    assert.equal(relay.roomCount(), 0);
  });
});

test("oversized, malformed and non-UTF-8 bodies", async () => {
  await withRelay({}, async (base, relay) => {
    const call = api(base, rand(16));
    const code = rand(24);
    const big = await call("POST", "/hello", { auth: code, raw: "x".repeat(513) });
    assert.deepEqual([big.status, big.json], [413, { error: "too_large" }]);
    assert.equal((await call("POST", "/hello", { auth: code, raw: "x".repeat(512) })).status, 400);
    assert.equal((await call("POST", "/hello", { auth: code, raw: "{oops" })).status, 400);
    const res = await fetch(`${base}/v1/rooms/${"b".repeat(32)}/hello`, {
      method: "POST",
      headers: { authorization: `Bearer ${code}` },
      body: new Uint8Array([0x7b, 0xff, 0xfe, 0x7d]),
    });
    assert.equal(res.status, 400);
    // None of that created a room.
    assert.equal(relay.roomCount(), 0);
  });
});

test("rooms are capped by maxRooms", async () => {
  await withRelay({ maxRooms: 1 }, async (base) => {
    const code = rand(24);
    const first = api(base, rand(16));
    const second = api(base, rand(16));
    assert.equal((await first("POST", "/hello", { auth: code, body: helloBody(await makeMember()) })).status, 200);
    assert.equal((await second("POST", "/hello", { auth: code, body: helloBody(await makeMember()) })).status, 503);
  });
});

test("probing unknown rooms does not leave state behind", async () => {
  await withRelay({}, async (base, relay) => {
    for (let i = 0; i < 20; i++) await api(base, rand(16))("GET", "/state", { auth: rand(24) });
    assert.equal(relay.roomCount(), 0);
  });
});

test("concurrent hellos over HTTP admit exactly two phones", async () => {
  await withRelay({}, async (base) => {
    const call = api(base, rand(16));
    const code = rand(24);
    const members = await Promise.all(Array.from({ length: 6 }, () => makeMember()));
    const results = await Promise.all(members.map((m) => call("POST", "/hello", { auth: code, body: helloBody(m) })));
    assert.equal(results.filter((r) => r.status === 200).length, 2);
    assert.equal(results.filter((r) => r.status === 409).length, 4);
  });
});

test("with DATA_DIR, rooms and queued messages survive a restart", async () => {
  const dir = await mkdtemp(join(tmpdir(), "tenchar-relay-"));
  try {
    const roomId = rand(16);
    const code = rand(24);
    const a = await makeMember();
    const b = await makeMember();

    await withRelay({ dataDir: dir }, async (base) => {
      const call = api(base, roomId);
      await call("POST", "/hello", { auth: code, body: helloBody(a) });
      await call("POST", "/hello", { auth: code, body: helloBody(b) });
      await call("POST", "/reveal", { auth: code, body: revealBody(a) });
      await call("POST", "/reveal", { auth: code, body: revealBody(b) });
      await call("POST", "/messages", { auth: a.memberToken, body: { env: envelope("Z") } });
    });
    assert.deepEqual(await readdir(dir), [`${roomId}.json`]);

    await withRelay({ dataDir: dir }, async (base, relay) => {
      assert.equal(relay.roomCount(), 1);
      const call = api(base, roomId);
      const got = await call("GET", "/messages", { auth: b.memberToken });
      assert.equal(got.json.messages[0].env, envelope("Z"));
      await call("DELETE", "", { auth: a.memberToken });
    });
    assert.deepEqual(await readdir(dir), []);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("a damaged data file is skipped, not fatal", async () => {
  const dir = await mkdtemp(join(tmpdir(), "tenchar-relay-"));
  try {
    const { writeFile } = await import("node:fs/promises");
    await writeFile(join(dir, `${"c".repeat(32)}.json`), "not json");
    await writeFile(join(dir, "unrelated.txt"), "x");
    await withRelay({ dataDir: dir }, async (_base, relay) => {
      assert.equal(relay.roomCount(), 0);
    });
    assert.ok(existsSync(join(dir, "unrelated.txt")));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
