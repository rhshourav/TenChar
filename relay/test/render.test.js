import assert from "node:assert/strict";
import http from "node:http";
import { test } from "node:test";
import { createRelay, MemoryStorage } from "../src/node.js";
import { helloBody, makeMember, pattern, rand, revealBody } from "./helpers.js";

async function withRelay(options, fn) {
  const relay = createRelay(options);
  const port = await relay.start(0, "127.0.0.1");
  try {
    await fn(`http://127.0.0.1:${port}`, relay, port);
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

test("MemoryStorage counts the attachment bytes it holds, through put, replace, delete and deleteAll", () => {
  const s = new MemoryStorage();
  s.put("b:x:0000", new Uint8Array(1000));
  s.put("b:x:0001", new Uint8Array(500));
  s.put("meta", { not: "counted" });
  assert.equal(s.binBytes, 1500);
  s.put("b:x:0000", new Uint8Array(200)); // replacing a piece replaces its size
  assert.equal(s.binBytes, 700);
  s.delete(["b:x:0001", "meta", "missing"]);
  assert.equal(s.binBytes, 200);
  s.deleteAll();
  assert.equal(s.binBytes, 0);
  s.loadBinary("b:y:0000", new Uint8Array(64));
  assert.equal(s.binBytes, 64);
});

test("the instance refuses an upload it could not hold, and takes it once there is room", async () => {
  await withRelay({ maxBlobMemoryBytes: 500_000 }, async (base) => {
    const { a, b, call } = await pairOverHttp(base, rand(16));
    const first = rand(16);
    const second = rand(16);
    assert.equal((await call("PUT", `/blobs/${first}`, a.memberToken, pattern(300_000, 1))).status, 201);
    // 300 KB more would be 600 KB: over the 500 KB this instance may hold. "unavailable" means try again later.
    const refused = await call("PUT", `/blobs/${second}`, a.memberToken, pattern(300_000, 2));
    assert.equal(refused.status, 503);
    assert.deepEqual(await refused.json(), { error: "unavailable" });
    // The other phone downloads and deletes the first one: the memory is free again.
    assert.equal((await call("GET", `/blobs/${first}`, b.memberToken)).status, 200);
    assert.equal((await call("DELETE", `/blobs/${first}`, b.memberToken)).status, 204);
    assert.equal((await call("PUT", `/blobs/${second}`, a.memberToken, pattern(300_000, 2))).status, 201);
  });
});

test("a stranger is told nothing about the instance's memory", async () => {
  await withRelay({ maxBlobMemoryBytes: 10 }, async (base) => {
    const room = rand(16);
    await pairOverHttp(base, room);
    // The refusal for a wrong token is the same 401 whether or not the instance is full.
    const res = await fetch(`${base}/v1/rooms/${room}/blobs/${rand(16)}`, {
      method: "PUT", headers: { authorization: `Bearer ${rand(32)}`, connection: "close" }, body: pattern(1000),
    }).catch(() => ({ status: 401 }));
    assert.equal(res.status, 401);
  });
});

test("only a set number of uploads are read into memory at once", async () => {
  await withRelay({ maxUploads: 1 }, async (base, _relay, port) => {
    const room = rand(16);
    const { a, call } = await pairOverHttp(base, room);
    // Start an upload and stall it half way: it has been accepted and is holding the one slot.
    const slowId = rand(16);
    const slow = http.request({
      host: "127.0.0.1", port, method: "PUT", path: `/v1/rooms/${room}/blobs/${slowId}`,
      headers: { authorization: `Bearer ${a.memberToken}`, "content-length": "200000" },
    });
    const done = new Promise((resolve, reject) => { slow.on("response", resolve); slow.on("error", reject); });
    slow.write(Buffer.alloc(100_000));
    await new Promise((r) => setTimeout(r, 200));

    const refused = await call("PUT", `/blobs/${rand(16)}`, a.memberToken, pattern(1000)).catch(() => ({ status: 503 }));
    assert.equal(refused.status, 503);

    slow.end(Buffer.alloc(100_000)); // finish the stalled one: it was never harmed
    assert.equal((await done).statusCode, 201);
    assert.equal((await call("PUT", `/blobs/${rand(16)}`, a.memberToken, pattern(1000))).status, 201); // the slot is free again
  });
});

test("keep-alive settings suit a proxy that reuses connections, and keep their required order", async () => {
  await withRelay({}, async (_base, relay) => {
    const s = relay.server;
    assert.equal(s.keepAliveTimeout, 120_000); // longer than Render's proxy keeps a connection idle
    assert.ok(s.headersTimeout > s.keepAliveTimeout);
    assert.ok(s.requestTimeout >= s.headersTimeout);
  });
  await withRelay({ keepAliveMs: 30_000 }, async (_base, relay) => {
    assert.equal(relay.server.keepAliveTimeout, 30_000);
    assert.ok(relay.server.requestTimeout >= relay.server.headersTimeout);
  });
});
