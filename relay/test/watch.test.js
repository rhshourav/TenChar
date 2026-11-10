import assert from "node:assert/strict";
import { test } from "node:test";
import { envelope, makeHarness, pair, rand, sha256Hex } from "./helpers.js";

const register = async (h, who, token) =>
  h.call("watch", { auth: who.memberToken, body: { tokenHash: await sha256Hex(token) } });
const peek = (h, token) => h.call("peek", { auth: token });
const send = (h, who) => h.call("post_message", { auth: who.memberToken, body: { env: envelope() } });

test("a watch token sees how many messages wait, and nothing else", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const watch = rand(32);
  assert.deepEqual((await register(h, b, watch)).body, { watch: true });

  assert.deepEqual((await peek(h, watch)).body, { waiting: 0, latest: 0 });
  await send(h, a);
  await send(h, a);
  assert.deepEqual((await peek(h, watch)).body, { waiting: 2, latest: 2 });

  // Acknowledging empties the queue; "latest" never goes back, so a new message is always a new number.
  await h.call("ack", { auth: b.memberToken, body: { upTo: 2 } });
  assert.deepEqual((await peek(h, watch)).body, { waiting: 0, latest: 2 });
  await send(h, a);
  assert.deepEqual((await peek(h, watch)).body, { waiting: 1, latest: 3 });

  // a's messages are for b: a's own queue is separate.
  const aWatch = rand(32);
  await register(h, a, aWatch);
  assert.deepEqual((await peek(h, aWatch)).body, { waiting: 0, latest: 0 });
});

test("a watch token opens nothing else", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const watch = rand(32);
  await register(h, b, watch);
  await send(h, a);
  for (const [action, opts] of [
    ["get_messages", {}],
    ["state", {}],
    ["ack", { body: { upTo: 1 } }],
    ["post_message", { body: { env: envelope() } }],
    ["watch", { body: { tokenHash: "" } }],
    ["delete", {}],
    ["put_blob", { bytes: new Uint8Array(10), blob: rand(16) }],
    ["get_blob", { blob: rand(16) }],
  ]) {
    assert.equal((await h.call(action, { auth: watch, ...opts })).status, 401, action);
  }
  // Nothing was lost.
  assert.equal((await h.call("get_messages", { auth: b.memberToken })).body.messages.length, 1);
});

test("peek refuses member tokens, the pairing token, unknown tokens and unsealed rooms", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const watch = rand(32);
  await register(h, b, watch);
  assert.equal((await peek(h, a.memberToken)).status, 401);
  assert.equal((await peek(h, b.memberToken)).status, 401);
  assert.equal((await peek(h, h.codeToken)).status, 401);
  assert.equal((await peek(h, rand(32))).status, 401);
  assert.equal((await h.call("peek", {})).status, 401);

  const early = makeHarness();
  const a2 = await (await import("./helpers.js")).makeMember();
  await early.call("hello", { auth: early.codeToken, body: { memberId: a2.memberId, memberTokenHash: a2.memberTokenHash, commit: a2.commit } });
  assert.equal((await early.call("watch", { auth: early.codeToken, body: { tokenHash: await sha256Hex(watch) } })).status, 401);
});

test("only a member registers, the hash must be well formed, and removing works", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const watch = rand(32);
  const hash = await sha256Hex(watch);
  assert.equal((await h.call("watch", { auth: h.codeToken, body: { tokenHash: hash } })).status, 401);
  assert.equal((await h.call("watch", { body: { tokenHash: hash } })).status, 401);
  for (const tokenHash of ["xyz", hash.toUpperCase(), hash.slice(1), 5, null]) {
    assert.equal((await h.call("watch", { auth: b.memberToken, body: { tokenHash } })).status, 400, String(tokenHash));
  }
  assert.equal((await h.call("watch", { auth: b.memberToken, body: {} })).status, 400);
  // A watch hash equal to a member token hash would let a watch token pose as a member.
  const clash = await h.call("watch", { auth: b.memberToken, body: { tokenHash: a.memberTokenHash } });
  assert.deepEqual([clash.status, clash.body], [409, { error: "conflict" }]);

  await register(h, b, watch);
  assert.equal((await peek(h, watch)).status, 200);
  assert.deepEqual((await h.call("watch", { auth: b.memberToken, body: { tokenHash: "" } })).body, { watch: false });
  assert.equal((await peek(h, watch)).status, 401);
});

test("peek does not keep a room alive and the hash is never shown to the other member", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const watch = rand(32);
  await register(h, b, watch);
  const D = 24 * 60 * 60 * 1000;
  h.clock.t += 6 * D;
  assert.equal((await peek(h, watch)).status, 200);
  h.clock.t += 2 * D; // 8 days with no member activity, however often it was watched
  assert.equal((await peek(h, watch)).status, 404);

  const h2 = makeHarness();
  const p = await pair(h2);
  const w = rand(32);
  await register(h2, p.b, w);
  const state = JSON.stringify((await h2.call("state", { auth: p.a.memberToken })).body);
  assert.ok(!state.includes(await sha256Hex(w)) && !state.includes(w) && !state.includes("watch"));
  assert.ok(!JSON.stringify(h2.storage.snapshot()).includes(w), "the token itself is never stored");
});

test("the old push route and relay-initiated requests are gone", async () => {
  const realFetch = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return new Response(null, { status: 200 });
  };
  try {
    const h = makeHarness();
    const { a, b } = await pair(h);
    await register(h, b, rand(32));
    await send(h, a);
    assert.equal(calls, 0, "the relay must never make an outgoing request");
  } finally {
    globalThis.fetch = realFetch;
  }
});
