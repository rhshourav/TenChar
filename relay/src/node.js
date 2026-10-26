// Node server for the same relay logic (used on Render). Standard library only.
// State lives in memory. If DATA_DIR is set, each room is also written to
// DATA_DIR/<room>.json after every change, so a restart keeps rooms and
// queued messages. Nothing is logged except the listening port.

import { createServer as createHttpServer } from "node:http";
import { mkdir, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { MAX_BODY_BYTES } from "./constants.js";
import { Mutex, RoomCore, isRoomId, parseRoute } from "./core.js";

const BASE_HEADERS = { "cache-control": "no-store", "x-content-type-options": "nosniff" };
const SWEEP_INTERVAL_MS = 60 * 60 * 1000;

export class MemoryStorage {
  #map = new Map();
  version = 0;

  get size() {
    return this.#map.size;
  }
  get(key) {
    const raw = this.#map.get(key);
    return raw === undefined ? undefined : JSON.parse(raw);
  }
  put(key, value) {
    this.#map.set(key, JSON.stringify(value));
    this.version++;
  }
  delete(keys) {
    for (const key of keys) this.#map.delete(key);
    this.version++;
  }
  deleteAll() {
    this.#map.clear();
    this.version++;
  }
  list(prefix, { start, limit } = {}) {
    const keys = [...this.#map.keys()]
      .filter((key) => key.startsWith(prefix) && (start === undefined || key >= start))
      .sort();
    return keys.slice(0, limit ?? keys.length).map((key) => [key, JSON.parse(this.#map.get(key))]);
  }
  snapshot() {
    return Object.fromEntries(this.#map);
  }
  load(snapshot) {
    for (const [key, raw] of Object.entries(snapshot)) this.#map.set(key, raw);
    this.version++;
  }
}

function send(res, status, body, { close = false } = {}) {
  const headers = { ...BASE_HEADERS };
  if (close) headers.connection = "close";
  if (status === 204 || body === null) {
    res.writeHead(status, headers).end();
  } else {
    headers["content-type"] = "application/json";
    res.writeHead(status, headers).end(JSON.stringify(body));
  }
}

function readBody(req, max) {
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

export function createRelay({ dataDir = null, maxRooms = 10000, now = () => Date.now() } = {}) {
  const rooms = new Map(); // room id -> {storage, core, pending, persisted, persistMutex}

  function newEntry() {
    const storage = new MemoryStorage();
    return { storage, core: new RoomCore(storage, { now }), pending: 0, persisted: 0, persistMutex: new Mutex() };
  }

  async function persist(id, entry) {
    if (!dataDir) return;
    await entry.persistMutex.run(async () => {
      if (entry.storage.version === entry.persisted) return;
      const file = join(dataDir, `${id}.json`);
      if (entry.storage.size === 0) {
        await rm(file, { force: true });
      } else {
        const tmp = `${file}.tmp`;
        await writeFile(tmp, JSON.stringify(entry.storage.snapshot()), { mode: 0o600 });
        await rename(tmp, file);
      }
      entry.persisted = entry.storage.version;
    });
  }

  function forgetIfEmpty(id, entry) {
    if (entry.pending === 0 && entry.storage.size === 0) rooms.delete(id);
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

  async function handle(req, res) {
    const url = new URL(req.url ?? "/", "http://relay.invalid");

    if (url.pathname === "/v1/health") {
      return req.method === "GET"
        ? send(res, 200, { ok: true })
        : send(res, 405, { error: "method_not_allowed" });
    }
    const route = parseRoute(req.method ?? "", url.pathname);
    if (route.error) return send(res, route.status, { error: route.error });

    const body = await readBody(req, MAX_BODY_BYTES);
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
      });
      await persist(route.room, entry);
      send(res, result.status, result.body);
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
  server.headersTimeout = 10_000;
  server.requestTimeout = 15_000;

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
  const relay = createRelay({ dataDir, maxRooms: Number(process.env.MAX_ROOMS) || 10000 });
  const port = await relay.start(Number(process.env.PORT) || 8787);
  console.log(`tenchar relay listening on port ${port}`);
  if (!dataDir) {
    console.warn("DATA_DIR is not set: rooms and queued messages are lost when this process restarts.");
  }
  process.on("SIGTERM", () => relay.stop().then(() => process.exit(0)));
}
