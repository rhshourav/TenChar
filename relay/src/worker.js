// Cloudflare Worker entry. One Durable Object per room keeps "exactly two
// members" and "sealed" consistent; the Worker only routes. Nothing here logs.

import { MAX_BLOB_BYTES, MAX_BODY_BYTES } from "./constants.js";
import { RoomCore, parseRoute } from "./core.js";
import { jsonResponse, readBodyCapped, respond } from "./http.js";

/** Only an attachment upload may be larger than a small JSON body. */
const bodyLimit = (action) => (action === "put_blob" ? MAX_BLOB_BYTES : MAX_BODY_BYTES);

/** Adapts Durable Object storage to the interface RoomCore expects. */
export class DoStorage {
  #storage;
  constructor(storage) {
    this.#storage = storage;
  }
  get(key) {
    return this.#storage.get(key);
  }
  put(key, value) {
    return this.#storage.put(key, value);
  }
  async delete(keys) {
    await this.#storage.delete(keys); // at most 128 keys per call; core passes <= 100
  }
  async deleteAll() {
    await this.#storage.deleteAll();
  }
  async list(prefix, { start, limit } = {}) {
    const options = { prefix };
    if (start !== undefined) options.start = start;
    if (limit !== undefined) options.limit = limit;
    return [...(await this.#storage.list(options)).entries()];
  }
}

export class Room {
  #ctx;
  #core;

  constructor(ctx) {
    this.#ctx = ctx;
    this.#core = new RoomCore(new DoStorage(ctx.storage));
  }

  async fetch(request) {
    const url = new URL(request.url);
    const route = parseRoute(request.method, url.pathname);
    if (route.error) return jsonResponse(route.status, { error: route.error });

    const binary = route.action === "put_blob";
    if (binary) {
      const refused = await this.#core.authorize(request.headers.get("authorization"));
      if (refused) return respond(refused);
    }
    const body = await readBodyCapped(request, bodyLimit(route.action), { binary });
    if (body.tooLarge) return jsonResponse(413, { error: "too_large" });
    if (body.invalid) return jsonResponse(400, { error: "bad_request" });

    const result = await this.#core.handle({
      action: route.action,
      authorization: request.headers.get("authorization"),
      query: url.searchParams,
      bodyText: body.text,
      bodyBytes: body.bytes,
      blob: route.blob,
    });
    await this.#ensureAlarm();
    return respond(result);
  }

  /** Deletes the room when it expires, even if nobody calls again. */
  async alarm() {
    const next = await this.#core.sweep();
    if (next !== null) await this.#ctx.storage.setAlarm(next);
  }

  async #ensureAlarm() {
    if ((await this.#ctx.storage.getAlarm()) !== null) return;
    const next = await this.#core.sweep();
    if (next !== null) await this.#ctx.storage.setAlarm(next);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/v1/health") {
      return request.method === "GET"
        ? jsonResponse(200, { ok: true })
        : jsonResponse(405, { error: "method_not_allowed" });
    }
    const route = parseRoute(request.method, url.pathname);
    if (route.error) return jsonResponse(route.status, { error: route.error });

    const declared = request.headers.get("content-length");
    if (declared !== null && Number(declared) > bodyLimit(route.action)) return jsonResponse(413, { error: "too_large" });

    return env.ROOMS.get(env.ROOMS.idFromName(route.room)).fetch(request);
  },
};