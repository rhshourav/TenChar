import { RoomCore, bytesToHex, commitHex, hexToBytes } from "../src/core.js";
import { MemoryStorage } from "../src/node.js";

export const rand = (n) => bytesToHex(crypto.getRandomValues(new Uint8Array(n)));

export async function sha256Hex(hex) {
  return bytesToHex(new Uint8Array(await crypto.subtle.digest("SHA-256", hexToBytes(hex))));
}

export async function makeMember(overrides = {}) {
  const m = {
    memberId: rand(16),
    memberToken: rand(32),
    ik: rand(32),
    ek: rand(32),
    ...overrides,
  };
  m.memberTokenHash = await sha256Hex(m.memberToken);
  m.commit = await commitHex(m.ik, m.ek, m.memberId);
  return m;
}

export const helloBody = (m) => ({ memberId: m.memberId, memberTokenHash: m.memberTokenHash, commit: m.commit });
export const revealBody = (m) => ({ memberId: m.memberId, ik: m.ik, ek: m.ek });

/** A valid 180-character envelope. */
export const envelope = (fill = "A") => fill.repeat(180);

export function makeHarness() {
  const clock = { t: 1_800_000_000_000 };
  const storage = new MemoryStorage();
  const core = new RoomCore(storage, { now: () => clock.t });
  const call = (action, { auth, body, query } = {}) =>
    core.handle({
      action,
      authorization: auth ? `Bearer ${auth}` : null,
      query: new URLSearchParams(query ?? ""),
      bodyText: body === undefined ? "" : typeof body === "string" ? body : JSON.stringify(body),
    });
  return { clock, storage, core, call, codeToken: rand(24) };
}

/** Pairs two members through the full handshake and returns them. */
export async function pair(h) {
  const a = await makeMember();
  const b = await makeMember();
  await h.call("hello", { auth: h.codeToken, body: helloBody(a) });
  await h.call("hello", { auth: h.codeToken, body: helloBody(b) });
  await h.call("reveal", { auth: h.codeToken, body: revealBody(a) });
  const last = await h.call("reveal", { auth: h.codeToken, body: revealBody(b) });
  if (!last.body.sealed) throw new Error("pairing helper failed");
  return { a, b };
}
