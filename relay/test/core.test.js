import assert from "node:assert/strict";
import { test } from "node:test";
import { commitHex, parseRoute } from "../src/core.js";
import {
  envelope,
  helloBody,
  makeHarness,
  makeMember,
  pair,
  rand,
  revealBody,
} from "./helpers.js";

const H = 60 * 60 * 1000;
const D = 24 * H;

test("commit hash matches a value computed independently with Python hashlib", async () => {
  const ik = Array.from({ length: 32 }, (_, i) => i.toString(16).padStart(2, "0")).join("");
  const ek = Array.from({ length: 32 }, (_, i) => (i + 32).toString(16).padStart(2, "0")).join("");
  const id = Array.from({ length: 16 }, (_, i) => (i + 64).toString(16).padStart(2, "0")).join("");
  assert.equal(
    await commitHex(ik, ek, id),
    "728abd52535bd50eabbf6b43fcbc2fa7bd8197ba7e724ea87abd55931df1a76e",
  );
});

test("token hashes match values computed independently with Python hashlib", async () => {
  const h = makeHarness();
  const codeToken = Array.from({ length: 24 }, (_, i) => (i + 0x80).toString(16)).join("");
  const memberToken = Array.from({ length: 32 }, (_, i) => (i + 0xa0).toString(16)).join("");
  const a = await makeMember({ memberToken });
  assert.equal(
    a.memberTokenHash,
    "00e988677eecf94c0bb9233371c7c0d6f4db8ebdcdecb7c5ebaa666f17249227",
  );
  await h.call("hello", { auth: codeToken, body: helloBody(a) });
  assert.equal(
    (await h.storage.get("meta")).codeTokenHash,
    "caaa103f63119c936c7ef540cf0ef850a93f0a7d1a3faffcfc1e9ff87280704c",
  );
  // The memberToken only authenticates if SHA-256(memberToken) equals the stored hash.
  const state = await h.call("state", { auth: memberToken });
  assert.equal(state.status, 200);
});

test("full handshake, then messages both ways", async () => {
  const h = makeHarness();
  const a = await makeMember();
  const b = await makeMember();

  assert.deepEqual((await h.call("hello", { auth: h.codeToken, body: helloBody(a) })).body, { members: 1 });
  assert.deepEqual((await h.call("hello", { auth: h.codeToken, body: helloBody(b) })).body, { members: 2 });

  const s1 = await h.call("state", { auth: h.codeToken });
  assert.equal(s1.body.sealed, false);
  assert.equal(s1.body.expiresAt, h.clock.t + D);
  assert.deepEqual(s1.body.members.map((m) => m.commit).sort(), [a.commit, b.commit].sort());
  assert.ok(s1.body.members.every((m) => m.ik === null && m.ek === null));

  assert.deepEqual((await h.call("reveal", { auth: h.codeToken, body: revealBody(a) })).body, { sealed: false });
  assert.deepEqual((await h.call("reveal", { auth: b.memberToken, body: revealBody(b) })).body, { sealed: true });

  // The codeToken is dead for good, the memberToken still reads the peer's keys.
  assert.equal((await h.call("state", { auth: h.codeToken })).status, 410);
  const s2 = await h.call("state", { auth: a.memberToken });
  assert.equal(s2.status, 200);
  assert.equal(s2.body.sealed, true);
  assert.equal(s2.body.expiresAt, null);
  assert.equal(s2.body.members.find((m) => m.memberId === b.memberId).ik, b.ik);

  // a -> b
  assert.equal((await h.call("post_message", { auth: a.memberToken, body: { env: envelope("A") } })).status, 201);
  assert.equal((await h.call("post_message", { auth: a.memberToken, body: { env: envelope("B") } })).status, 201);
  const forB = await h.call("get_messages", { auth: b.memberToken });
  assert.deepEqual(forB.body, {
    messages: [{ id: 1, env: envelope("A") }, { id: 2, env: envelope("B") }],
    more: false,
  });
  // The sender never gets its own messages back.
  assert.deepEqual((await h.call("get_messages", { auth: a.memberToken })).body, { messages: [], more: false });

  // Reading does not delete; ack does.
  assert.equal((await h.call("get_messages", { auth: b.memberToken })).body.messages.length, 2);
  assert.deepEqual((await h.call("get_messages", { auth: b.memberToken, query: "after=1" })).body.messages.map((m) => m.id), [2]);
  assert.deepEqual((await h.call("ack", { auth: b.memberToken, body: { upTo: 1 } })).body, { removed: 1 });
  assert.deepEqual((await h.call("get_messages", { auth: b.memberToken })).body.messages.map((m) => m.id), [2]);
  assert.deepEqual((await h.call("ack", { auth: b.memberToken, body: { upTo: 99 } })).body, { removed: 1 });
  assert.deepEqual((await h.call("get_messages", { auth: b.memberToken })).body.messages, []);

  // b -> a uses its own queue.
  await h.call("post_message", { auth: b.memberToken, body: { env: envelope("C") } });
  assert.equal((await h.call("get_messages", { auth: a.memberToken })).body.messages[0].env, envelope("C"));
});

test("a third phone cannot join, and the same phone can retry", async () => {
  const h = makeHarness();
  const a = await makeMember();
  const b = await makeMember();
  const c = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  assert.equal((await h.call("hello", { auth: h.codeToken, body: helloBody(a) })).status, 200);
  await h.call("hello", { auth: h.codeToken, body: helloBody(b) });
  const third = await h.call("hello", { auth: h.codeToken, body: helloBody(c) });
  assert.deepEqual([third.status, third.body], [409, { error: "full" }]);
  assert.equal((await h.call("hello", { auth: h.codeToken, body: helloBody(a) })).status, 200);
});

test("hello with a changed commit or a reused token hash is a conflict", async () => {
  const h = makeHarness();
  const a = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  const changed = await h.call("hello", { auth: h.codeToken, body: { ...helloBody(a), commit: rand(32) } });
  assert.deepEqual([changed.status, changed.body], [409, { error: "conflict" }]);
  const other = await makeMember();
  const reused = await h.call("hello", {
    auth: h.codeToken,
    body: { ...helloBody(other), memberTokenHash: a.memberTokenHash },
  });
  assert.deepEqual([reused.status, reused.body], [409, { error: "conflict" }]);
});

test("concurrent hellos are serialised: exactly two members get in", async () => {
  const h = makeHarness();
  const members = await Promise.all([makeMember(), makeMember(), makeMember(), makeMember()]);
  const results = await Promise.all(members.map((m) => h.call("hello", { auth: h.codeToken, body: helloBody(m) })));
  assert.equal(results.filter((r) => r.status === 200).length, 2);
  assert.equal(results.filter((r) => r.status === 409 && r.body.error === "full").length, 2);
  assert.equal((await h.storage.get("meta")).members.length, 2);
});

test("wrong or malformed credentials are rejected", async () => {
  const h = makeHarness();
  const a = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });

  const bad = [
    null,
    rand(24).toUpperCase(), // uppercase hex
    rand(24), // right shape, wrong token
    rand(32), // member shape, unknown token
    rand(10), // neither shape
  ];
  for (const auth of bad) {
    const r = await h.call("state", { auth });
    assert.equal(r.status, 401, String(auth));
  }
  assert.equal((await h.call("hello", { body: helloBody(a) })).status, 401);
  assert.equal((await h.call("hello", { auth: a.memberToken, body: helloBody(a) })).status, 401);
  assert.equal((await h.call("state", { auth: h.codeToken })).status, 200);
});

test("unknown room is 404 on everything except hello", async () => {
  const h = makeHarness();
  for (const action of ["state", "reveal", "get_messages", "delete"]) {
    assert.equal((await h.call(action, { auth: rand(24), body: {} })).status, 404, action);
  }
});

test("reveal needs both commits and a matching commit", async () => {
  const h = makeHarness();
  const a = await makeMember();
  const b = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });

  const early = await h.call("reveal", { auth: h.codeToken, body: revealBody(a) });
  assert.deepEqual([early.status, early.body], [409, { error: "not_ready" }]);

  await h.call("hello", { auth: h.codeToken, body: helloBody(b) });
  const forged = await h.call("reveal", { auth: h.codeToken, body: { ...revealBody(a), ek: rand(32) } });
  assert.deepEqual([forged.status, forged.body], [400, { error: "commit_mismatch" }]);

  // A member token cannot reveal for the other member.
  const wrongOwner = await h.call("reveal", { auth: a.memberToken, body: revealBody(b) });
  assert.equal(wrongOwner.status, 403);

  const unknown = await h.call("reveal", { auth: h.codeToken, body: { ...revealBody(a), memberId: rand(16) } });
  assert.equal(unknown.status, 400);

  // Repeating a valid reveal changes nothing.
  assert.equal((await h.call("reveal", { auth: h.codeToken, body: revealBody(a) })).status, 200);
  assert.equal((await h.call("reveal", { auth: h.codeToken, body: revealBody(a) })).status, 200);
  assert.equal((await h.storage.get("meta")).sealed, false);
});

test("messages are refused before sealing and malformed envelopes are rejected", async () => {
  const h = makeHarness();
  const a = await makeMember();
  const b = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  await h.call("hello", { auth: h.codeToken, body: helloBody(b) });
  assert.equal((await h.call("post_message", { auth: a.memberToken, body: { env: envelope() } })).status, 409);
  assert.equal((await h.call("get_messages", { auth: a.memberToken })).status, 409);
  assert.equal((await h.call("ack", { auth: a.memberToken, body: { upTo: 1 } })).status, 409);

  const sealed = makeHarness();
  const { a: x } = await pair(sealed);
  const cases = [
    { env: "A".repeat(179) },
    { env: "A".repeat(181) },
    { env: "A".repeat(179) + "=" },
    { env: "A".repeat(179) + "-" }, // base64url, not standard
    { env: 5 },
    {},
  ];
  for (const body of cases) {
    assert.equal((await sealed.call("post_message", { auth: x.memberToken, body })).status, 400, JSON.stringify(body).slice(0, 40));
  }
  // The code token never works for messages.
  assert.equal((await sealed.call("post_message", { auth: sealed.codeToken, body: { env: envelope() } })).status, 401);
});

test("bad JSON bodies and bad query values are 400", async () => {
  const h = makeHarness();
  const a = await makeMember();
  assert.equal((await h.call("hello", { auth: h.codeToken, body: "{not json" })).status, 400);
  assert.equal((await h.call("hello", { auth: h.codeToken, body: "[]" })).status, 400);
  assert.equal((await h.call("hello", { auth: h.codeToken, body: "null" })).status, 400);
  assert.equal((await h.call("hello", { auth: h.codeToken, body: { memberId: "xyz" } })).status, 400);
  assert.equal(
    (await h.call("hello", { auth: h.codeToken, body: { ...helloBody(a), commit: helloBody(a).commit.toUpperCase() } })).status,
    400,
  );
  // No room was created by the failed attempts.
  assert.equal(await h.storage.get("meta"), undefined);

  const { a: m } = await pair(h);
  for (const q of ["after=-1", "after=abc", "after=1.5", "after=1e3", `after=${"9".repeat(16)}`]) {
    assert.equal((await h.call("get_messages", { auth: m.memberToken, query: q })).status, 400, q);
  }
  for (const upTo of [-1, 1.5, "3", null]) {
    assert.equal((await h.call("ack", { auth: m.memberToken, body: { upTo } })).status, 400, String(upTo));
  }
});

test("the pairing window is 24 hours, then the room is deleted", async () => {
  const h = makeHarness();
  const a = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  h.clock.t += D - 1;
  assert.equal((await h.call("state", { auth: h.codeToken })).status, 200);
  h.clock.t += 1;
  assert.equal((await h.call("state", { auth: h.codeToken })).status, 404);
  assert.equal(h.storage.size, 0);
  // The code can be used again for a brand new room (documented limitation).
  const b = await makeMember();
  assert.equal((await h.call("hello", { auth: h.codeToken, body: helloBody(b) })).body.members, 1);
});

test("a sealed room expires after 7 idle days, and activity extends it", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  h.clock.t += 6 * D;
  assert.equal((await h.call("get_messages", { auth: a.memberToken })).status, 200);
  h.clock.t += 6 * D;
  assert.equal((await h.call("get_messages", { auth: b.memberToken })).status, 200); // 6 days after last activity
  h.clock.t += 7 * D;
  assert.equal((await h.call("get_messages", { auth: b.memberToken })).status, 404);
  assert.equal(h.storage.size, 0);
});

test("queued messages older than 7 days are deleted", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  await h.call("post_message", { auth: a.memberToken, body: { env: envelope("A") } });
  h.clock.t += 6 * D;
  // Keeps the room alive and queues a second, newer message.
  await h.call("post_message", { auth: a.memberToken, body: { env: envelope("B") } });
  h.clock.t += 2 * D;
  const r = await h.call("get_messages", { auth: b.memberToken });
  assert.deepEqual(r.body.messages, [{ id: 2, env: envelope("B") }]);
  assert.equal((await h.storage.get("meta")).queueLen[b.memberId], 1);
});

test("a recipient's queue is capped", async () => {
  const h = makeHarness();
  const { a } = await pair(h);
  for (let i = 0; i < 1000; i++) {
    const r = await h.call("post_message", { auth: a.memberToken, body: { env: envelope() } });
    if (r.status !== 201) assert.fail(`message ${i} got ${r.status}`);
  }
  const over = await h.call("post_message", { auth: a.memberToken, body: { env: envelope() } });
  assert.deepEqual([over.status, over.body], [429, { error: "queue_full" }]);
});

test("get_messages pages in order and reports more", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  for (let i = 0; i < 105; i++) await h.call("post_message", { auth: a.memberToken, body: { env: envelope() } });
  const first = await h.call("get_messages", { auth: b.memberToken });
  assert.equal(first.body.messages.length, 100);
  assert.equal(first.body.more, true);
  assert.equal(first.body.messages[0].id, 1);
  assert.equal(first.body.messages[99].id, 100);
  const second = await h.call("get_messages", { auth: b.memberToken, query: "after=100" });
  assert.deepEqual(second.body.messages.map((m) => m.id), [101, 102, 103, 104, 105]);
  assert.equal(second.body.more, false);
});

test("delete burns the room before and after sealing", async () => {
  const h = makeHarness();
  const a = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  const r = await h.call("delete", { auth: h.codeToken });
  assert.deepEqual([r.status, r.body], [204, null]);
  assert.equal((await h.call("state", { auth: h.codeToken })).status, 404);

  const h2 = makeHarness();
  const { a: x, b: y } = await pair(h2);
  assert.equal((await h2.call("delete", { auth: h2.codeToken })).status, 410);
  assert.equal((await h2.call("delete", { auth: x.memberToken })).status, 204);
  assert.equal((await h2.call("state", { auth: y.memberToken })).status, 404);
  assert.equal(h2.storage.size, 0);
});

test("sweep reports the next expiry and deletes expired rooms", async () => {
  const h = makeHarness();
  assert.equal(await h.core.sweep(), null);
  const a = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  assert.equal(await h.core.sweep(), h.clock.t + D);
  h.clock.t += D;
  assert.equal(await h.core.sweep(), null);
  assert.equal(h.storage.size, 0);
});

test("the relay never stores a token in the clear", async () => {
  const h = makeHarness();
  const { a, b } = await pair(h);
  const dump = JSON.stringify(h.storage.snapshot());
  for (const secret of [h.codeToken, a.memberToken, b.memberToken]) assert.ok(!dump.includes(secret));
});

test("route parsing", () => {
  const room = "a".repeat(32);
  assert.deepEqual(parseRoute("POST", `/v1/rooms/${room}/hello`), { room, action: "hello" });
  assert.deepEqual(parseRoute("GET", `/v1/rooms/${room}/messages`), { room, action: "get_messages" });
  assert.deepEqual(parseRoute("POST", `/v1/rooms/${room}/messages`), { room, action: "post_message" });
  assert.deepEqual(parseRoute("DELETE", `/v1/rooms/${room}`), { room, action: "delete" });
  assert.equal(parseRoute("GET", `/v1/rooms/${room}/hello`).status, 405);
  assert.equal(parseRoute("PUT", `/v1/rooms/${room}`).status, 405);
  assert.equal(parseRoute("GET", `/v1/rooms/${room}/nope`).status, 404);
  assert.equal(parseRoute("GET", `/v1/rooms/${"A".repeat(32)}/state`).status, 404); // uppercase
  assert.equal(parseRoute("GET", `/v1/rooms/${"a".repeat(31)}/state`).status, 404);
  assert.equal(parseRoute("GET", `/v1/rooms/${room}/state/extra`).status, 404);
  assert.equal(parseRoute("GET", "/").status, 404);
});
