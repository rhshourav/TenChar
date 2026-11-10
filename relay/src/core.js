// Relay logic, independent of where it runs. The storage object is injected:
//   get(key) -> value | undefined
//   put(key, value)
//   delete(keys: string[])
//   deleteAll()
//   list(prefix, {start, limit}) -> [[key, value], ...] in key order, start inclusive
// Values are JSON-like objects, or Uint8Array for attachment pieces.
// Cloudflare Durable Object storage and the in-memory store used by the Node
// server both fit. Nothing here logs.

import {
  BLOB_STORE_CHUNK,
  COMMIT_PREFIX,
  ENVELOPE_B64_CHARS,
  MAX_BLOBS,
  MAX_BLOB_BYTES,
  MAX_QUEUE,
  MAX_ROOM_BLOB_BYTES,
  PAGE_SIZE,
  PAIRING_WINDOW_MS,
  RETENTION_MS,
  TOUCH_INTERVAL_MS,
} from "./constants.js";

const hexRe = (n) => new RegExp(`^[0-9a-f]{${n}}$`);
const RE_ROOM = hexRe(32);
const RE_MEMBER_ID = hexRe(32);
const RE_BLOB_ID = hexRe(32);
const RE_HASH = hexRe(64);
const RE_KEY = hexRe(64);
const RE_CODE_TOKEN = hexRe(48);
const RE_MEMBER_TOKEN = hexRe(64);
const RE_ENVELOPE = new RegExp(`^[A-Za-z0-9+/]{${ENVELOPE_B64_CHARS}}$`);
const RE_BEARER = /^Bearer ([0-9a-f]+)$/;

const encoder = new TextEncoder();

export function isRoomId(value) {
  return typeof value === "string" && RE_ROOM.test(value);
}

export function hexToBytes(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.substr(i * 2, 2), 16);
  return out;
}

export function bytesToHex(bytes) {
  let s = "";
  for (const b of bytes) s += b.toString(16).padStart(2, "0");
  return s;
}

async function sha256Hex(bytes) {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)));
}

/** Compares two strings without stopping at the first difference. */
function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function commitHex(ikHex, ekHex, memberIdHex) {
  const prefix = encoder.encode(COMMIT_PREFIX);
  const ik = hexToBytes(ikHex);
  const ek = hexToBytes(ekHex);
  const id = hexToBytes(memberIdHex);
  const all = new Uint8Array(prefix.length + ik.length + ek.length + id.length);
  all.set(prefix, 0);
  all.set(ik, prefix.length);
  all.set(ek, prefix.length + ik.length);
  all.set(id, prefix.length + ik.length + ek.length);
  return sha256Hex(all);
}

/** Runs async functions one at a time. */
export class Mutex {
  #tail = Promise.resolve();
  run(fn) {
    const result = this.#tail.then(() => fn());
    this.#tail = result.catch(() => {});
    return result;
  }
}

const ok = (status, body) => ({ status, body });
const fail = (status, error) => ({ status, body: { error } });

const queuePrefix = (memberId) => `q:${memberId}:`;
const queueKey = (memberId, seq) => `${queuePrefix(memberId)}${String(seq).padStart(10, "0")}`;
const seqOfKey = (key) => Number(key.slice(key.lastIndexOf(":") + 1));
const blobPrefix = (id) => `b:${id}:`;
const blobKey = (id, index) => `${blobPrefix(id)}${String(index).padStart(4, "0")}`;

/**
 * Parses method and path. Returns {room, action, blob?} or {status, error}.
 * Actions: hello, state, reveal, post_message, get_messages, ack, watch, peek, put_blob, get_blob, delete_blob, delete.
 */
export function parseRoute(method, pathname) {
  const m = /^\/v1\/rooms\/([^/]+)(?:\/([a-z]+)(?:\/([^/]+))?)?$/.exec(pathname);
  if (!m || !isRoomId(m[1])) return { status: 404, error: "not_found" };
  const [, room, tail, blob] = m;
  // An id is only allowed after "blobs", and "blobs" alone is not a route.
  if (blob !== undefined && (tail !== "blobs" || !RE_BLOB_ID.test(blob))) return { status: 404, error: "not_found" };
  if (tail === "blobs" && blob === undefined) return { status: 404, error: "not_found" };
  const table = {
    "POST hello": "hello",
    "GET state": "state",
    "POST reveal": "reveal",
    "POST messages": "post_message",
    "GET messages": "get_messages",
    "POST ack": "ack",
    "POST watch": "watch",
    "GET peek": "peek",
    "PUT blobs": "put_blob",
    "GET blobs": "get_blob",
    "DELETE blobs": "delete_blob",
    "DELETE ": "delete",
  };
  const action = table[`${method} ${tail ?? ""}`];
  if (action) return blob === undefined ? { room, action } : { room, action, blob };
  const known = Object.keys(table).some((k) => k.endsWith(` ${tail ?? ""}`));
  return known ? { status: 405, error: "method_not_allowed" } : { status: 404, error: "not_found" };
}

export class RoomCore {
  #storage;
  #now;
  #mutex = new Mutex();

  constructor(storage, { now = () => Date.now() } = {}) {
    this.#storage = storage;
    this.#now = now;
  }

  /**
   * req: {action, authorization, query: URLSearchParams|null, bodyText: string, bodyBytes?: Uint8Array, blob?: string}
   * Returns {status, body}, or {status, bytes} for a downloaded blob. Requests are processed one at a time.
   */
  handle(req) {
    return this.#mutex.run(() => this.#handle(req));
  }

  /**
   * Checks, before an attachment's body is read, that the room exists and the caller is one of its members.
   * Returns null if so, otherwise the error result. Keeps anyone without a valid token from making the relay
   * receive megabytes it will only throw away.
   */
  authorize(authorization) {
    return this.#mutex.run(async () => {
      const meta = await this.#load(this.#now());
      if (!meta) return fail(404, "not_found");
      const auth = await this.#auth(meta, authorization, {});
      return auth.error ?? null;
    });
  }

  /** Deletes the room if it has expired. Returns the time it will expire, or null. */
  sweep() {
    return this.#mutex.run(async () => {
      const meta = await this.#load(this.#now());
      return meta ? this.#expiryAt(meta) : null;
    });
  }

  #expiryAt(meta) {
    return meta.sealed ? meta.lastActive + RETENTION_MS : meta.createdAt + PAIRING_WINDOW_MS;
  }

  async #load(now) {
    const meta = (await this.#storage.get("meta")) ?? null;
    if (meta && now >= this.#expiryAt(meta)) {
      await this.#storage.deleteAll();
      return null;
    }
    return meta;
  }

  #save(meta) {
    return this.#storage.put("meta", meta);
  }

  async #handle({ action, authorization, query, bodyText, bodyBytes, blob }) {
    const now = this.#now();
    const meta = await this.#load(now);

    let body = null;
    if (action === "hello" || action === "reveal" || action === "post_message" || action === "ack" || action === "watch") {
      try {
        body = JSON.parse(bodyText);
      } catch {
        return fail(400, "bad_request");
      }
      if (body === null || typeof body !== "object" || Array.isArray(body)) return fail(400, "bad_request");
    }

    if (action === "hello") return this.#hello(meta, authorization, body, now);
    if (!meta) return fail(404, "not_found");

    const auth = await this.#auth(meta, authorization, {
      code: action === "state" || action === "reveal" || action === "delete",
      watch: action === "peek",
    });
    if (auth.error) return auth.error;
    await this.#touch(meta, auth, now);

    switch (action) {
      case "state":
        return ok(200, {
          sealed: meta.sealed,
          expiresAt: meta.sealed ? null : meta.createdAt + PAIRING_WINDOW_MS,
          members: meta.members.map((m) => ({ memberId: m.id, commit: m.commit, ik: m.ik, ek: m.ek })),
        });
      case "reveal":
        return this.#reveal(meta, auth, body, now);
      case "delete":
        await this.#storage.deleteAll();
        return ok(204, null);
      case "post_message":
        return this.#postMessage(meta, auth, body, now);
      case "get_messages":
        return this.#getMessages(meta, auth, query, now);
      case "ack":
        return this.#ack(meta, auth, body);
      case "watch":
        return this.#watch(meta, auth, body);
      case "peek":
        return this.#peek(meta, auth);
      case "put_blob":
        return this.#putBlob(meta, auth, blob, bodyBytes, now);
      case "get_blob":
        return this.#getBlob(meta, auth, blob);
      case "delete_blob":
        return this.#deleteBlob(meta, auth, blob);
      default:
        return fail(404, "not_found");
    }
  }

  /**
   * Wrong credentials are always 401; a right codeToken on a sealed room is 410.
   * `watch` means only a watch token is accepted (and only a watch token), `code` that the pairing token is too.
   */
  async #auth(meta, authorization, { code = false, watch = false } = {}) {
    const m = RE_BEARER.exec(authorization ?? "");
    if (!m) return { error: fail(401, "unauthorized") };
    const token = m[1];
    if (RE_CODE_TOKEN.test(token)) {
      if (!code) return { error: fail(401, "unauthorized") };
      const hash = await sha256Hex(hexToBytes(token));
      if (!safeEqual(hash, meta.codeTokenHash)) return { error: fail(401, "unauthorized") };
      if (meta.sealed) return { error: fail(410, "gone") };
      return { kind: "code" };
    }
    if (RE_MEMBER_TOKEN.test(token)) {
      const hash = await sha256Hex(hexToBytes(token));
      let member = null;
      for (const candidate of meta.members) {
        if (safeEqual(watch ? (candidate.watch ?? "") : candidate.tokenHash, hash)) member = candidate;
      }
      if (!member) return { error: fail(401, "unauthorized") };
      return { kind: watch ? "watch" : "member", member };
    }
    return { error: fail(401, "unauthorized") };
  }

  async #touch(meta, auth, now) {
    if (auth.kind === "member" && meta.sealed && now - meta.lastActive >= TOUCH_INTERVAL_MS) {
      meta.lastActive = now;
      await this.#save(meta);
    }
  }

  async #hello(meta, authorization, body, now) {
    const m = RE_BEARER.exec(authorization ?? "");
    if (!m || !RE_CODE_TOKEN.test(m[1])) return fail(401, "unauthorized");
    if (
      typeof body.memberId !== "string" || !RE_MEMBER_ID.test(body.memberId) ||
      typeof body.memberTokenHash !== "string" || !RE_HASH.test(body.memberTokenHash) ||
      typeof body.commit !== "string" || !RE_HASH.test(body.commit)
    ) {
      return fail(400, "bad_request");
    }
    const codeTokenHash = await sha256Hex(hexToBytes(m[1]));
    if (meta) {
      if (!safeEqual(meta.codeTokenHash, codeTokenHash)) return fail(401, "unauthorized");
      if (meta.sealed) return fail(410, "gone");
    } else {
      meta = {
        createdAt: now,
        lastActive: now,
        codeTokenHash,
        sealed: false,
        members: [],
        nextSeq: {},
        queueLen: {},
      };
    }

    const same = meta.members.find((x) => x.id === body.memberId);
    if (same) {
      const identical = safeEqual(same.tokenHash, body.memberTokenHash) && safeEqual(same.commit, body.commit);
      return identical ? ok(200, { members: meta.members.length }) : fail(409, "conflict");
    }
    if (meta.members.length >= 2) return fail(409, "full");
    if (meta.members.some((x) => safeEqual(x.tokenHash, body.memberTokenHash))) return fail(409, "conflict");

    meta.members.push({ id: body.memberId, tokenHash: body.memberTokenHash, commit: body.commit, ik: null, ek: null });
    await this.#save(meta);
    return ok(200, { members: meta.members.length });
  }

  async #reveal(meta, auth, body, now) {
    if (
      typeof body.memberId !== "string" || !RE_MEMBER_ID.test(body.memberId) ||
      typeof body.ik !== "string" || !RE_KEY.test(body.ik) ||
      typeof body.ek !== "string" || !RE_KEY.test(body.ek)
    ) {
      return fail(400, "bad_request");
    }
    const member = meta.members.find((x) => x.id === body.memberId);
    if (!member) return fail(400, "bad_request");
    if (auth.kind === "member" && auth.member.id !== body.memberId) return fail(403, "forbidden");
    if (meta.members.length < 2) return fail(409, "not_ready");
    if (!safeEqual(await commitHex(body.ik, body.ek, body.memberId), member.commit)) {
      return fail(400, "commit_mismatch");
    }
    member.ik = body.ik;
    member.ek = body.ek;
    if (meta.members.every((x) => x.ik !== null)) {
      meta.sealed = true;
      meta.lastActive = now;
    }
    await this.#save(meta);
    return ok(200, { sealed: meta.sealed });
  }

  #peerOf(meta, member) {
    return meta.members.find((x) => x.id !== member.id) ?? null;
  }

  async #postMessage(meta, auth, body, now) {
    if (!meta.sealed) return fail(409, "not_sealed");
    if (typeof body.env !== "string" || !RE_ENVELOPE.test(body.env)) return fail(400, "bad_request");
    const peer = this.#peerOf(meta, auth.member);
    if (!peer) return fail(409, "not_sealed");

    await this.#prune(meta, peer.id, now);
    if ((meta.queueLen[peer.id] ?? 0) >= MAX_QUEUE) return fail(429, "queue_full");
    const seq = (meta.nextSeq[peer.id] ?? 0) + 1;
    meta.nextSeq[peer.id] = seq;
    meta.queueLen[peer.id] = (meta.queueLen[peer.id] ?? 0) + 1;
    await this.#storage.put(queueKey(peer.id, seq), { env: body.env, at: now });
    await this.#save(meta);
    return ok(201, { ok: true });
  }

  /**
   * Registers (or, with an empty tokenHash, removes) the hash of this member's watch token. The token itself never
   * reaches the relay. It only opens `peek`, which says how many messages wait: no content, no ack, no sending.
   */
  async #watch(meta, auth, body) {
    if (typeof body.tokenHash !== "string") return fail(400, "bad_request");
    if (body.tokenHash === "") {
      auth.member.watch = null;
      await this.#save(meta);
      return ok(200, { watch: false });
    }
    if (!RE_HASH.test(body.tokenHash)) return fail(400, "bad_request");
    if (meta.members.some((x) => safeEqual(x.tokenHash, body.tokenHash))) return fail(409, "conflict");
    auth.member.watch = body.tokenHash;
    await this.#save(meta);
    return ok(200, { watch: true });
  }

  /** What a watch token may learn: how many messages wait for its member, and the newest sequence number ever queued. */
  #peek(meta, auth) {
    if (!meta.sealed) return fail(409, "not_sealed");
    const id = auth.member.id;
    return ok(200, { waiting: meta.queueLen[id] ?? 0, latest: meta.nextSeq[id] ?? 0 });
  }

  // ---- attachments -------------------------------------------------------------------------------------------
  // A blob is opaque ciphertext that one member uploads for the other. The relay cannot read it, learns its size
  // and when it arrived, and deletes it when the recipient says so or after RETENTION_MS.

  async #putBlob(meta, auth, id, bytes, now) {
    if (!meta.sealed) return fail(409, "not_sealed");
    const peer = this.#peerOf(meta, auth.member);
    if (!peer) return fail(409, "not_sealed");
    if (!(bytes instanceof Uint8Array) || bytes.length === 0) return fail(400, "bad_request");
    if (bytes.length > MAX_BLOB_BYTES) return fail(413, "too_large");

    meta.blobs ??= {};
    meta.blobBytes ??= 0;
    const existing = meta.blobs[id];
    // The same upload repeated (the phone never saw the answer) is accepted; a different one under that id is not.
    if (existing) {
      return existing.from === auth.member.id && existing.size === bytes.length ? ok(200, { ok: true }) : fail(409, "conflict");
    }
    await this.#pruneBlobs(meta, now);
    if (Object.keys(meta.blobs).length >= MAX_BLOBS || meta.blobBytes + bytes.length > MAX_ROOM_BLOB_BYTES) {
      return fail(429, "blob_quota");
    }
    const chunks = Math.ceil(bytes.length / BLOB_STORE_CHUNK);
    for (let i = 0; i < chunks; i++) {
      // A copy, not a view: a stored view would keep (or, in a Durable Object, clone) the whole upload.
      const piece = new Uint8Array(bytes.subarray(i * BLOB_STORE_CHUNK, (i + 1) * BLOB_STORE_CHUNK));
      await this.#storage.put(blobKey(id, i), piece);
    }
    meta.blobs[id] = { from: auth.member.id, to: peer.id, size: bytes.length, chunks, at: now };
    meta.blobBytes += bytes.length;
    await this.#save(meta);
    return ok(201, { ok: true });
  }

  async #getBlob(meta, auth, id) {
    if (!meta.sealed) return fail(409, "not_sealed");
    const info = meta.blobs?.[id];
    // Only the recipient may fetch it. To anyone else it does not exist.
    if (!info || info.to !== auth.member.id) return fail(404, "not_found");
    const pieces = await this.#storage.list(blobPrefix(id), { limit: info.chunks + 1 });
    if (pieces.length !== info.chunks) return fail(404, "not_found");
    const out = new Uint8Array(info.size);
    let at = 0;
    for (const [, piece] of pieces) {
      if (!(piece instanceof Uint8Array) || at + piece.length > out.length) return fail(404, "not_found");
      out.set(piece, at);
      at += piece.length;
    }
    if (at !== info.size) return fail(404, "not_found");
    return { status: 200, bytes: out };
  }

  async #deleteBlob(meta, auth, id) {
    if (!meta.sealed) return fail(409, "not_sealed");
    const info = meta.blobs?.[id];
    if (!info || (info.to !== auth.member.id && info.from !== auth.member.id)) return fail(404, "not_found");
    await this.#removeBlob(meta, id);
    await this.#save(meta);
    return ok(204, null);
  }

  /** Removes one blob's pieces and its record. The caller saves meta. */
  async #removeBlob(meta, id) {
    const info = meta.blobs[id];
    const keys = [];
    for (let i = 0; i < info.chunks; i++) keys.push(blobKey(id, i));
    for (let i = 0; i < keys.length; i += PAGE_SIZE) await this.#storage.delete(keys.slice(i, i + PAGE_SIZE));
    meta.blobBytes = Math.max(0, (meta.blobBytes ?? 0) - info.size);
    delete meta.blobs[id];
  }

  async #pruneBlobs(meta, now) {
    let removed = false;
    for (const [id, info] of Object.entries(meta.blobs ?? {})) {
      if (now - info.at >= RETENTION_MS) {
        await this.#removeBlob(meta, id);
        removed = true;
      }
    }
    if (removed) await this.#save(meta);
  }

  async #getMessages(meta, auth, query, now) {
    if (!meta.sealed) return fail(409, "not_sealed");
    const raw = query?.get("after") ?? "0";
    if (!/^\d{1,15}$/.test(raw)) return fail(400, "bad_request");
    const after = Number(raw);

    await this.#prune(meta, auth.member.id, now);
    const page = await this.#storage.list(queuePrefix(auth.member.id), {
      start: queueKey(auth.member.id, after + 1),
      limit: PAGE_SIZE + 1,
    });
    return ok(200, {
      messages: page.slice(0, PAGE_SIZE).map(([key, value]) => ({ id: seqOfKey(key), env: value.env })),
      more: page.length > PAGE_SIZE,
    });
  }

  async #ack(meta, auth, body) {
    if (!meta.sealed) return fail(409, "not_sealed");
    if (!Number.isSafeInteger(body.upTo) || body.upTo < 0) return fail(400, "bad_request");
    const id = auth.member.id;
    let removed = 0;
    for (;;) {
      const page = await this.#storage.list(queuePrefix(id), { limit: PAGE_SIZE });
      const dead = page.filter(([key]) => seqOfKey(key) <= body.upTo);
      if (dead.length === 0) break;
      await this.#storage.delete(dead.map(([key]) => key));
      removed += dead.length;
      if (dead.length < page.length) break;
    }
    if (removed > 0) {
      meta.queueLen[id] = Math.max(0, (meta.queueLen[id] ?? 0) - removed);
      await this.#save(meta);
    }
    return ok(200, { removed });
  }

  /** Messages are stored oldest first, so expired ones are always a prefix. */
  async #prune(meta, memberId, now) {
    let removed = 0;
    for (;;) {
      const page = await this.#storage.list(queuePrefix(memberId), { limit: PAGE_SIZE });
      const dead = page.filter(([, value]) => now - value.at >= RETENTION_MS);
      if (dead.length === 0) break;
      await this.#storage.delete(dead.map(([key]) => key));
      removed += dead.length;
      if (dead.length < page.length) break;
    }
    if (removed > 0) {
      meta.queueLen[memberId] = Math.max(0, (meta.queueLen[memberId] ?? 0) - removed);
      await this.#save(meta);
    }
  }
}