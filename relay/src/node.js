// Node server for the same relay logic (used on Render). Standard library only.
// State lives in memory. If DATA_DIR is set, each room is also written to
// DATA_DIR/<room>.json after every change, and each attachment piece to
// DATA_DIR/<room>.blobs/ once, so a restart keeps rooms, queued messages and
// waiting attachments. Nothing is logged except the listening port.

import { createServer as createHttpServer } from "node:http";
import { mkdir, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { MAX_BLOB_BYTES, MAX_BODY_BYTES } from "./constants.js";
import { Mutex, RoomCore, isRoomId, parseRoute } from "./core.js";

/** Only an attachment upload may be larger than a small JSON body. */
const bodyLimit = (action) => (action === "put_blob" ? MAX_BLOB_BYTES : MAX_BODY_BYTES);

const BASE_HEADERS = { "cache-control": "no-store", "x-content-type-options": "nosniff" };
const SWEEP_INTERVAL_MS = 60 * 60 * 1000;
// A 9 MiB upload from a phone on a slow link needs far longer than a small JSON request.
const REQUEST_TIMEOUT_MS = 120_000;

export class MemoryStorage {
  #map = new Map(); // key -> JSON text
  #bin = new Map(); // key -> Uint8Array (attachment pieces)
  #binBytes = 0;
  version = 0;
  /** Binary keys written / removed since the last flush to disk. */
  binDirty = new Set();
  binGone = new Set();

  get size() {
    return this.#map.size + this.#bin.size;
  }
  /** Bytes of attachment held in memory by this room. */
  get binBytes() {
    return this.#binBytes;
  }
  get(key) {
    if (this.#bin.has(key)) return this.#bin.get(key);
    const raw = this.#map.get(key);
    return raw === undefined ? undefined : JSON.parse(raw);
  }
  put(key, value) {
    if (value instanceof Uint8Array) {
      this.#binBytes += value.length - (this.#bin.get(key)?.length ?? 0);
      this.#bin.set(key, value);
      this.binDirty.add(key);
      this.binGone.delete(key);
    } else {
      this.#map.set(key, JSON.stringify(value));
    }
    this.version++;
  }
  delete(keys) {
    for (const key of keys) {
      this.#map.delete(key);
      const had = this.#bin.get(key);
      if (this.#bin.delete(key)) {
        this.#binBytes -= had.length;
        this.binDirty.delete(key);
        this.binGone.add(key);
      }
    }
    this.version++;
  }
  deleteAll() {
    for (const key of this.#bin.keys()) {
      this.binDirty.delete(key);
      this.binGone.add(key);
    }
    this.#map.clear();
    this.#bin.clear();
    this.#binBytes = 0;
    this.version++;
  }
  list(prefix, { start, limit } = {}) {
    const keys = [...this.#map.keys(), ...this.#bin.keys()]
      .filter((key) => key.startsWith(prefix) && (start === undefined || key >= start))
      .sort();
    return keys.slice(0, limit ?? keys.length).map((key) => [key, this.get(key)]);
  }
  /** The JSON part only. Attachment pieces are saved one by one (see binaryPiece). */
  snapshot() {
    return Object.fromEntries(this.#map);
  }
  binaryPiece(key) {
    return this.#bin.get(key);
  }
  load(snapshot) {
    for (const [key, raw] of Object.entries(snapshot)) this.#map.set(key, raw);
    this.version++;
  }
  loadBinary(key, bytes) {
    this.#binBytes += bytes.length - (this.#bin.get(key)?.length ?? 0);
    this.#bin.set(key, bytes);
    this.version++;
  }
}

function send(res, status, body, { close = false, bytes = null } = {}) {
  const headers = { ...BASE_HEADERS };
  if (close) headers.connection = "close";
  if (bytes) {
    headers["content-type"] = "application/octet-stream";
    headers["content-length"] = bytes.length;
    res.writeHead(status, headers).end(bytes);
  } else if (status === 204 || body === null) {
    res.writeHead(status, headers).end();
  } else {
    headers["content-type"] = "application/json";
    res.writeHead(status, headers).end(JSON.stringify(body));
  }
}

function readBody(req, max, binary = false) {
  return new Promise((resolve) => {
    const declared = req.headers["content-length"];
    if (declared !== undefined && Number(declared) > max) return resolve({ tooLarge: true });
    const chunks = [];
    let total = 0;
    let finished = false;
    req.on("data", (chunk) => {
      if (finished) return;
      total += chunk.length;
      if (total > max) {
        finished = true;
        resolve({ tooLarge: true });
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (finished) return;
      finished = true;
      if (binary) return resolve({ bytes: new Uint8Array(Buffer.concat(chunks)) });
      try {
        resolve({ text: new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)) });
      } catch {
        resolve({ invalid: true });
      }
    });
    req.on("error", () => {
      if (finished) return;
      finished = true;
      resolve({ invalid: true });
    });
  });
}

/**
 * maxBlobMemoryBytes: all waiting attachments together. A free Render instance has 512 MB; a host that kills the process
 * for using too much memory loses every room, so uploads that would not fit are refused (503, "try again") instead.
 * maxUploads: uploads read into memory at once. keepAliveMs: how long an idle connection stays open; Render's proxy
 * reuses connections, so this must be longer than the proxy's own idle timeout (Render's docs say to use 120 s).
 */
export function createRelay({
  dataDir = null,
  maxRooms = 10000,
  maxBlobMemoryBytes = 96 * 1024 * 1024,
  maxUploads = 4,
  keepAliveMs = 120_000,
  now = () => Date.now(),
} = {}) {
  const rooms = new Map(); // room id -> {storage, core, pending, persisted, persistMutex}

  function newEntry() {
    const storage = new MemoryStorage();
    return { storage, core: new RoomCore(storage, { now }), pending: 0, persisted: 0, persistMutex: new Mutex() };
  }

  const blobFile = (id, key) => join(dataDir, `${id}.blobs`, `${key.replaceAll(":", "_")}.bin`);

  async function writeFileAtomic(file, data) {
    const tmp = `${file}.tmp`;
    await writeFile(tmp, data, { mode: 0o600 });
    await rename(tmp, file);
  }

  async function persist(id, entry) {
    if (!dataDir) return;
    await entry.persistMutex.run(async () => {
      if (entry.storage.version === entry.persisted) return;
      const file = join(dataDir, `${id}.json`);
      const { binDirty, binGone } = entry.storage;
      if (entry.storage.size === 0) {
        await rm(file, { force: true });
        await rm(join(dataDir, `${id}.blobs`), { recursive: true, force: true });
        binDirty.clear();
        binGone.clear();
      } else {
        // Attachment pieces are written once, when they arrive, never rewritten with every message.
        if (binDirty.size > 0) await mkdir(join(dataDir, `${id}.blobs`), { recursive: true, mode: 0o700 });
        for (const key of [...binDirty]) {
          const piece = entry.storage.binaryPiece(key);
          if (piece) await writeFileAtomic(blobFile(id, key), piece);
          binDirty.delete(key);
        }
        for (const key of [...binGone]) {
          await rm(blobFile(id, key), { force: true });
          binGone.delete(key);
        }
        await writeFileAtomic(file, JSON.stringify(entry.storage.snapshot()));
      }
      entry.persisted = entry.storage.version;
    });
  }

  function forgetIfEmpty(id, entry) {
    if (entry.pending === 0 && entry.storage.size === 0) rooms.delete(id);
  }

  async function loadPieces(id, entry) {
    let names = [];
    try {
      names = await readdir(join(dataDir, `${id}.blobs`));
    } catch {
      return; // no attachments in this room
    }
    for (const name of names) {
      const m = /^(b_[0-9a-f]{32}_\d{4})\.bin$/.exec(name);
      if (!m) continue;
      const bytes = new Uint8Array(await readFile(join(dataDir, `${id}.blobs`, name)));
      entry.storage.loadBinary(m[1].replaceAll("_", ":"), bytes);
    }
    entry.storage.binDirty.clear();
  }

  async function load() {
    if (!dataDir) return;
    await mkdir(dataDir, { recursive: true, mode: 0o700 });
    for (const name of await readdir(dataDir)) {
      const id = name.endsWith(".json") ? name.slice(0, -5) : "";
      if (!isRoomId(id)) continue;
      try {
        const entry = newEntry();
        entry.storage.load(JSON.parse(await readFile(join(dataDir, name), "utf8")));
        await loadPieces(id, entry);
        entry.persisted = entry.storage.version;
        rooms.set(id, entry);
      } catch {
        // A damaged file is skipped; that room is lost.
      }
    }
    await sweep();
  }

  async function sweep() {
    for (const [id, entry] of [...rooms]) {
      await entry.core.sweep();
      await persist(id, entry);
      forgetIfEmpty(id, entry);
    }
  }

  let activeUploads = 0;

  function storedBlobBytes() {
    let total = 0;
    for (const entry of rooms.values()) total += entry.storage.binBytes;
    return total;
  }

  async function handle(req, res) {
    const upload = { active: false };
    try {
      await handleInner(req, res, upload);
    } finally {
      if (upload.active) activeUploads--;
    }
  }

  async function handleInner(req, res, upload) {
    const url = new URL(req.url ?? "/", "http://relay.invalid");

    if (url.pathname === "/v1/health") {
      return req.method === "GET"
        ? send(res, 200, { ok: true })
        : send(res, 405, { error: "method_not_allowed" });
    }
    const route = parseRoute(req.method ?? "", url.pathname);
    if (route.error) return send(res, route.status, { error: route.error });

    if (route.action === "put_blob") {
      // Only a member of an existing room gets to send megabytes.
      const room = rooms.get(route.room);
      const refused = room ? await room.core.authorize(req.headers.authorization ?? null) : { status: 404, body: { error: "not_found" } };
      if (refused) {
        res.once("finish", () => req.socket?.destroy());
        return send(res, refused.status, refused.body, { close: true });
      }
      // Only now (a member of a real room) look at what this instance can hold.
      const declared = Number(req.headers["content-length"]) || MAX_BLOB_BYTES;
      if (activeUploads >= maxUploads || storedBlobBytes() + declared > maxBlobMemoryBytes) {
        res.once("finish", () => req.socket?.destroy());
        return send(res, 503, { error: "unavailable" }, { close: true });
      }
      upload.active = true;
      activeUploads++;
    }
    const body = await readBody(req, bodyLimit(route.action), route.action === "put_blob");
    if (body.tooLarge) {
      res.once("finish", () => req.socket?.destroy());
      return send(res, 413, { error: "too_large" }, { close: true });
    }
    if (body.invalid) return send(res, 400, { error: "bad_request" });

    let entry = rooms.get(route.room);
    if (!entry) {
      if (rooms.size >= maxRooms) return send(res, 503, { error: "unavailable" });
      entry = newEntry();
      rooms.set(route.room, entry);
    }
    entry.pending++;
    try {
      const result = await entry.core.handle({
        action: route.action,
        authorization: req.headers.authorization ?? null,
        query: url.searchParams,
        bodyText: body.text,
        bodyBytes: body.bytes,
        blob: route.blob,
      });
      await persist(route.room, entry);
      send(res, result.status, result.body, { bytes: result.bytes ?? null });
    } finally {
      entry.pending--;
      forgetIfEmpty(route.room, entry);
    }
  }

  const server = createHttpServer((req, res) => {
    handle(req, res).catch(() => {
      if (!res.headersSent) send(res, 500, { error: "internal" });
      else res.destroy();
    });
  });
  // Render's proxy keeps connections to this server open and reuses them. If Node closes an idle one first (its default
  // is 5 seconds) a request can land on a connection that is just being closed, which shows up as an occasional 502.
  // The headers timeout must be longer than the keep-alive one, and the request timeout longer than both.
  server.keepAliveTimeout = keepAliveMs;
  server.headersTimeout = keepAliveMs + 5_000;
  server.requestTimeout = Math.max(REQUEST_TIMEOUT_MS, keepAliveMs + 60_000);

  let timer = null;
  return {
    server,
    roomCount: () => rooms.size,
    async start(port, host = "0.0.0.0") {
      await load();
      await new Promise((resolve) => server.listen(port, host, resolve));
      timer = setInterval(() => sweep().catch(() => {}), SWEEP_INTERVAL_MS);
      timer.unref();
      return server.address().port;
    },
    stop() {
      if (timer) clearInterval(timer);
      return new Promise((resolve) => server.close(() => resolve()));
    },
    sweep,
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const dataDir = process.env.DATA_DIR || null;
  const relay = createRelay({
    dataDir,
    maxRooms: Number(process.env.MAX_ROOMS) || 10000,
    maxBlobMemoryBytes: (Number(process.env.MAX_BLOB_MEMORY_MB) || 96) * 1024 * 1024,
    maxUploads: Number(process.env.MAX_UPLOADS) || 4,
    keepAliveMs: (Number(process.env.KEEP_ALIVE_SECONDS) || 120) * 1000,
  });
  const port = await relay.start(Number(process.env.PORT) || 8787);
  console.log(`tenchar relay listening on port ${port}`);
  if (!dataDir) {
    console.warn("DATA_DIR is not set: rooms and queued messages are lost when this process restarts.");
  }
  process.on("SIGTERM", () => relay.stop().then(() => process.exit(0)));
}