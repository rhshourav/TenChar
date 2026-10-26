// Exercises worker.js and the Room Durable Object against a fake that behaves
// like Durable Object storage, including the 128-key limit on delete.

import assert from "node:assert/strict";
import { test } from "node:test";
import worker, { Room } from "../src/worker.js";
import { envelope, helloBody, makeMember, rand, revealBody } from "./helpers.js";

function makeCtx() {
  const map = new Map();
  let alarm = null;
  const clone = (v) => structuredClone(v);
  return {
    map,
    storage: {
      async get(key) {
        return map.has(key) ? clone(map.get(key)) : undefined;
      },
      async put(key, value) {
        map.set(key, clone(value));
      },
      async delete(keys) {
        if (!Array.isArray(keys) || keys.length > 128) throw new Error("delete takes 1-128 keys");
        for (const key of keys) map.delete(key);
      },
      async deleteAll() {
        map.clear();
      },
      async list({ prefix = "", start, limit } = {}) {
        const keys = [...map.keys()].filter((k) => k.startsWith(prefix) && (start === undefined || k >= start)).sort();
        return new Map(keys.slice(0, limit ?? keys.length).map((k) => [k, clone(map.get(k))]));
      },
      async getAlarm() {
        return alarm;
      },
      async setAlarm(time) {
        alarm = time;
      },
    },
  };
}

function makeEnv() {
  const objects = new Map();
  const ctxs = new Map();
  return {
    objects,
    ctxs,
    ROOMS: {
      idFromName: (name) => name,
      get: (id) => ({
        fetch: (request) => {
          if (!objects.has(id)) {
            const ctx = makeCtx();
            ctxs.set(id, ctx);
            objects.set(id, new Room(ctx));
          }
          return objects.get(id).fetch(request);
        },
      }),
    },
  };
}

const call = (env, room) => async (method, path, { auth, body, raw } = {}) => {
  const res = await worker.fetch(
    new Request(`https://relay.test/v1/rooms/${room}${path}`, {
      method,
      headers: auth ? { authorization: `Bearer ${auth}` } : {},
      body: raw ?? (body === undefined ? undefined : JSON.stringify(body)),
    }),
    env,
  );
  const text = await res.text();
  return { status: res.status, headers: res.headers, json: text ? JSON.parse(text) : null };
};

test("Worker routes, rejects, and answers health", async () => {
  const env = makeEnv();
  const health = await worker.fetch(new Request("https://relay.test/v1/health"), env);
  assert.deepEqual([health.status, await health.json()], [200, { ok: true }]);
  assert.equal((await worker.fetch(new Request("https://relay.test/v1/health", { method: "POST" }), env)).status, 405);
  assert.equal((await worker.fetch(new Request("https://relay.test/other"), env)).status, 404);
  assert.equal((await worker.fetch(new Request("https://relay.test/v1/rooms/xyz/state"), env)).status, 404);
  // Bad routes never reach a Durable Object.
  assert.equal(env.objects.size, 0);
});

test("handshake, messages, and acking more than 128 messages work through the Durable Object", async () => {
  const env = makeEnv();
  const room = rand(16);
  const c = call(env, room);
  const code = rand(24);
  const a = await makeMember();
  const b = await makeMember();
  assert.equal((await c("POST", "/hello", { auth: code, body: helloBody(a) })).status, 200);
  assert.equal((await c("POST", "/hello", { auth: code, body: helloBody(b) })).status, 200);
  assert.equal((await c("POST", "/reveal", { auth: code, body: revealBody(a) })).status, 200);
  assert.equal((await c("POST", "/reveal", { auth: code, body: revealBody(b) })).json.sealed, true);
  assert.equal((await c("GET", "/state", { auth: code })).status, 410);

  for (let i = 0; i < 250; i++) {
    const r = await c("POST", "/messages", { auth: a.memberToken, body: { env: envelope() } });
    assert.equal(r.status, 201);
  }
  const first = await c("GET", "/messages", { auth: b.memberToken });
  assert.equal(first.json.messages.length, 100);
  assert.equal(first.json.more, true);
  const acked = await c("POST", "/ack", { auth: b.memberToken, body: { upTo: 250 } });
  assert.deepEqual(acked.json, { removed: 250 });
  assert.deepEqual((await c("GET", "/messages", { auth: b.memberToken })).json.messages, []);
});

test("body cap: a declared oversize body never reaches a Durable Object", async () => {
  const env = makeEnv();
  // A Request built in code has no Content-Length, so set it as the network would.
  const res = await worker.fetch(
    new Request(`https://relay.test/v1/rooms/${rand(16)}/hello`, {
      method: "POST",
      headers: { authorization: `Bearer ${rand(24)}`, "content-length": "513" },
      body: "x".repeat(513),
    }),
    env,
  );
  assert.deepEqual([res.status, await res.json()], [413, { error: "too_large" }]);
  assert.equal(env.objects.size, 0);
});

test("body cap: the Durable Object enforces it too when no Content-Length is present", async () => {
  const env = makeEnv();
  const res = await call(env, rand(16))("POST", "/hello", { auth: rand(24), raw: "x".repeat(513) });
  assert.deepEqual([res.status, res.json], [413, { error: "too_large" }]);
});

test("body cap: a streamed body without content-length is cut off, and bad JSON is 400", async () => {
  const env = makeEnv();
  assert.equal((await call(env, rand(16))("POST", "/hello", { auth: rand(24), raw: "{oops" })).status, 400);
  const streamed = await worker.fetch(
    new Request(`https://relay.test/v1/rooms/${rand(16)}/hello`, {
      method: "POST",
      headers: { authorization: `Bearer ${rand(24)}` },
      body: new ReadableStream({
        start(controller) {
          controller.enqueue(new Uint8Array(300));
          controller.enqueue(new Uint8Array(300));
          controller.close();
        },
      }),
      duplex: "half",
    }),
    env,
  );
  assert.equal(streamed.status, 413);
});

test("the Durable Object sets an alarm for expiry and the alarm deletes the room", async () => {
  const env = makeEnv();
  const room = rand(16);
  const c = call(env, room);
  const before = Date.now();
  await c("POST", "/hello", { auth: rand(24), body: helloBody(await makeMember()) });
  const ctx = env.ctxs.get(room);
  const alarm = await ctx.storage.getAlarm();
  assert.ok(alarm >= before + 24 * 3600 * 1000 && alarm <= Date.now() + 24 * 3600 * 1000);
  assert.ok(ctx.map.size > 0);

  const realNow = Date.now;
  try {
    Date.now = () => realNow() + 25 * 3600 * 1000;
    await env.objects.get(room).alarm();
  } finally {
    Date.now = realNow;
  }
  assert.equal(ctx.map.size, 0);
});

test("an alarm that fires early reschedules instead of deleting", async () => {
  const env = makeEnv();
  const room = rand(16);
  const c = call(env, room);
  await c("POST", "/hello", { auth: rand(24), body: helloBody(await makeMember()) });
  const ctx = env.ctxs.get(room);
  const expiry = await ctx.storage.getAlarm();
  await ctx.storage.setAlarm(0);
  await env.objects.get(room).alarm();
  assert.equal(await ctx.storage.getAlarm(), expiry);
  assert.ok(ctx.map.size > 0);
});
