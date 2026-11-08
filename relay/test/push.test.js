import assert from "node:assert/strict";
import { test } from "node:test";
import { parsePushHosts, parsePushUrl } from "../src/push.js";
import { createRelay } from "../src/node.js";
import worker, { Room } from "../src/worker.js";
import { envelope, helloBody, makeHarness, makeMember, pair, rand, revealBody } from "./helpers.js";

const TOPIC = "abcdefghijklmnopqrstuvwxyz012345";
const URL_OK = `https://ntfy.sh/${TOPIC}`;
const HOSTS = ["ntfy.sh"];

function withNotifier(options = {}) {
  const calls = [];
  const h = makeHarness({ notify: (...args) => calls.push(args), pushHosts: HOSTS, ...options });
  return { h, calls };
}
const register = (h, who, url) => h.call("push", { auth: who.memberToken, body: { url } });
const send = (h, who) => h.call("post_message", { auth: who.memberToken, body: { env: envelope() } });

test("parsePushUrl accepts only a plain https topic on an allowed host", () => {
  assert.equal(parsePushUrl(URL_OK, HOSTS), URL_OK);
  // The host is compared case-insensitively and written back in lower case.
  assert.equal(parsePushUrl(`HTTPS://NTFY.SH/${TOPIC}`, HOSTS), URL_OK);
  assert.equal(parsePushUrl("https://push.example/" + TOPIC, ["push.example"]), "https://push.example/" + TOPIC);
  const bad = [
    `http://ntfy.sh/${TOPIC}`, // not https
    `ftp://ntfy.sh/${TOPIC}`,
    `https://evil.example/${TOPIC}`, // not on the list
    `https://ntfy.sh.evil.example/${TOPIC}`, // a look-alike
    `https://sub.ntfy.sh/${TOPIC}`, // no wildcard
    "https://ntfy.sh", // no topic
    "https://ntfy.sh/",
    `https://ntfy.sh/${TOPIC}/extra`, // more than one path segment
    `https://ntfy.sh/${TOPIC}/`,
    "https://ntfy.sh/short", // a guessable topic
    `https://ntfy.sh/${"a".repeat(65)}`,
    `https://ntfy.sh/${TOPIC}?x=1`,
    `https://ntfy.sh/${TOPIC}#a`,
    `https://ntfy.sh:8443/${TOPIC}`,
    `https://user:pw@ntfy.sh/${TOPIC}`,
    `https://ntfy.sh@evil.example/${TOPIC}`, // the host is what follows the "@"
    `https://127.0.0.1/${TOPIC}`,
    `https://[::1]/${TOPIC}`,
    `https://localhost/${TOPIC}`,
    ` https://ntfy.sh/${TOPIC}`,
    `https://ntfy.sh/${TOPIC}\n`,
    `https://ntfy.sh/${TOPIC.slice(0, 20)}%2e%2e`,
    "https://ntfy.sh/" + "a".repeat(120), // over the length limit
    "", "x", null, undefined, 42, {}, [],
  ];
  for (const value of bad) assert.equal(parsePushUrl(value, HOSTS), null, `should refuse ${JSON.stringify(value)}`);
});

test("parsePushHosts: unset is the default, a list is lower-cased, none turns it off", () => {
  assert.deepEqual(parsePushHosts(undefined), ["ntfy.sh"]);
  assert.deepEqual(parsePushHosts(" A.example , b.example,, "), ["a.example", "b.example"]);
  assert.deepEqual(parsePushHosts("none"), []);
  assert.deepEqual(parsePushHosts(""), []);
});

test("only a member can register, and a bad address is refused", async () => {
  const { h } = withNotifier();
  const { a } = await pair(h);
  assert.equal((await register(h, a, URL_OK)).status, 200);
  assert.deepEqual((await register(h, a, URL_OK)).body, { push: true });
  // The code token that opened the room is no use here, nor is nothing at all.
  assert.equal((await h.call("push", { auth: h.codeToken, body: { url: URL_OK } })).status, 401);
  assert.equal((await h.call("push", { body: { url: URL_OK } })).status, 401);
  for (const url of ["http://ntfy.sh/" + TOPIC, "https://evil.example/" + TOPIC, `${URL_OK}?x=1`]) {
    const r = await register(h, a, url);
    assert.deepEqual([r.status, r.body], [400, { error: "bad_push_url" }]);
  }
  assert.equal((await h.call("push", { auth: a.memberToken, body: { url: 5 } })).status, 400);
  assert.equal((await h.call("push", { auth: a.memberToken, body: {} })).status, 400);
});

test("a message for someone with a registered address sends one fixed notice to that address only", async () => {
  const { h, calls } = withNotifier();
  const { a, b } = await pair(h);
  await register(h, b, URL_OK);
  assert.equal((await send(h, a)).status, 201);
  assert.deepEqual(calls, [[URL_OK]]); // just the address: nothing about the sender or the message
});

test("nobody is notified without a registration, and never about their own messages", async () => {
  const { h, calls } = withNotifier();
  const { a, b } = await pair(h);
  await send(h, a);
  assert.equal(calls.length, 0);
  await register(h, a, URL_OK); // a registers, b does not
  await send(h, a); // a's own message goes to b, who has nothing registered
  assert.equal(calls.length, 0);
  await send(h, b); // b's message goes to a
  assert.deepEqual(calls, [[URL_OK]]);
});

test("a burst of messages gives one notice, then another after ten seconds", async () => {
  const { h, calls } = withNotifier();
  const { a, b } = await pair(h);
  await register(h, b, URL_OK);
  for (let i = 0; i < 25; i++) await send(h, a);
  assert.equal(calls.length, 1);
  h.clock.t += 9_000;
  await send(h, a);
  assert.equal(calls.length, 1);
  h.clock.t += 2_000;
  await send(h, a);
  assert.equal(calls.length, 2);
});

test("removing the address stops the notices", async () => {
  const { h, calls } = withNotifier();
  const { a, b } = await pair(h);
  await register(h, b, URL_OK);
  assert.deepEqual((await register(h, b, "")).body, { push: false });
  await send(h, a);
  assert.equal(calls.length, 0);
});

test("the address is never shown to the other member", async () => {
  const { h } = withNotifier();
  const { a, b } = await pair(h);
  await register(h, a, URL_OK);
  await register(h, b, `https://ntfy.sh/${"z".repeat(32)}`);
  const state = JSON.stringify((await h.call("state", { auth: b.memberToken })).body);
  assert.ok(!state.includes("ntfy.sh") && !state.includes(TOPIC) && !state.includes("push"));
});

test("a failing or throwing push service never affects delivery", async () => {
  const throwing = withNotifier({ notify: () => { throw new Error("boom"); } });
  const t = await pair(throwing.h);
  await register(throwing.h, t.b, URL_OK);
  assert.equal((await send(throwing.h, t.a)).status, 201);

  const rejecting = withNotifier({ notify: () => Promise.reject(new Error("down")) });
  const r = await pair(rejecting.h);
  await register(rejecting.h, r.b, URL_OK);
  assert.equal((await send(rejecting.h, r.a)).status, 201);
  const got = await rejecting.h.call("get_messages", { auth: r.b.memberToken, query: "after=0" });
  assert.equal(got.body.messages.length, 1);
});

test("push is unavailable when it is off or no notifier is configured", async () => {
  for (const options of [{ pushHosts: [] }, { notify: null }]) {
    const { h } = withNotifier(options);
    const { a } = await pair(h);
    const r = await register(h, a, URL_OK);
    assert.deepEqual([r.status, r.body], [501, { error: "push_unavailable" }]);
  }
});

test("over real HTTP: register, send, and the notice is attempted once", async () => {
  const calls = [];
  const relay = createRelay({ pushHosts: HOSTS, notify: (url) => calls.push(url) });
  const port = await relay.start(0, "127.0.0.1");
  try {
    const base = `http://127.0.0.1:${port}/v1/rooms/${rand(16)}`;
    const code = rand(24);
    const call = async (method, path, auth, body) => {
      const res = await fetch(base + path, {
        method,
        headers: { authorization: `Bearer ${auth}` },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return { status: res.status, json: await res.json().catch(() => null) };
    };
    const a = await makeMember();
    const b = await makeMember();
    await call("POST", "/hello", code, helloBody(a));
    await call("POST", "/hello", code, helloBody(b));
    await call("POST", "/reveal", code, revealBody(a));
    await call("POST", "/reveal", code, revealBody(b));
    assert.deepEqual((await call("POST", "/push", b.memberToken, { url: URL_OK })).json, { push: true });
    assert.equal((await call("POST", "/messages", a.memberToken, { env: envelope() })).status, 201);
    assert.deepEqual(calls, [URL_OK]);
  } finally {
    await relay.stop();
  }
});

test("Worker: the Durable Object passes the allow-list from the environment and keeps itself alive while notifying", async () => {
  const sent = [];
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    sent.push({ url: String(url), init });
    return new Response(null, { status: 200 });
  };
  try {
    const waiting = [];
    const map = new Map();
    const ctx = {
      storage: {
        get: async (k) => (map.has(k) ? structuredClone(map.get(k)) : undefined),
        put: async (k, v) => void map.set(k, structuredClone(v)),
        delete: async (ks) => void ks.forEach((k) => map.delete(k)),
        deleteAll: async () => map.clear(),
        list: async ({ prefix = "", start, limit } = {}) => {
          const keys = [...map.keys()].filter((k) => k.startsWith(prefix) && (start === undefined || k >= start)).sort();
          return new Map(keys.slice(0, limit ?? keys.length).map((k) => [k, structuredClone(map.get(k))]));
        },
        getAlarm: async () => null,
        setAlarm: async () => {},
      },
      waitUntil: (p) => waiting.push(p),
    };
    const room = new Room(ctx, { PUSH_HOSTS: "push.example" });
    const id = rand(16);
    const code = rand(24);
    const a = await makeMember();
    const b = await makeMember();
    const call = async (method, path, auth, body) =>
      (await room.fetch(new Request(`https://relay.test/v1/rooms/${id}${path}`, {
        method,
        headers: { authorization: `Bearer ${auth}` },
        body: body === undefined ? undefined : JSON.stringify(body),
      })));
    await call("POST", "/hello", code, helloBody(a));
    await call("POST", "/hello", code, helloBody(b));
    await call("POST", "/reveal", code, revealBody(a));
    await call("POST", "/reveal", code, revealBody(b));
    // ntfy.sh is not on this environment's list, push.example is.
    assert.equal((await call("POST", "/push", b.memberToken, { url: URL_OK })).status, 400);
    assert.equal((await call("POST", "/push", b.memberToken, { url: `https://push.example/${TOPIC}` })).status, 200);
    assert.equal((await call("POST", "/messages", a.memberToken, { env: envelope() })).status, 201);
    await Promise.all(waiting);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].url, `https://push.example/${TOPIC}`);
    assert.equal(sent[0].init.method, "POST");
    assert.equal(sent[0].init.body, "New message");
    assert.equal(sent[0].init.redirect, "manual"); // never follows a redirect to somewhere else
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("Worker: without PUSH_HOSTS the default list is used", async () => {
  const route = await worker.fetch(new Request("https://relay.test/v1/health"), { ROOMS: {} });
  assert.equal(route.status, 200);
  assert.deepEqual(parsePushHosts(undefined), ["ntfy.sh"]);
});