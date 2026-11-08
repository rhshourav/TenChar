// Optional wake-up pings. When a message is queued for someone who registered a push address, the relay sends
// that address a short, fixed "New message" notice, so their phone can wake without the app polling.
//
// What the relay learns: the address (it already sees every IP). What the push service learns: that something
// arrived, and when. Neither learns who sent it or what it said: the notice never varies.
//
// The relay makes a request to an address a client chose, so it is locked down: https only, a single-segment
// topic path, no credentials, query, fragment or port, and only for hosts on an allow-list. Without that a
// registered address could point the relay at anything it can reach.

import { DEFAULT_PUSH_HOSTS, PUSH_URL_MAX } from "./constants.js";

const RE_PUSH_PATH = /^\/[A-Za-z0-9_-]{16,64}$/;
const RE_VISIBLE_ASCII = /^[\x21-\x7e]+$/;

/** "a.example, B.example" -> ["a.example", "b.example"]. Unset means the default list; "none" or empty turns push off. */
export function parsePushHosts(value) {
  if (value === undefined || value === null) return [...DEFAULT_PUSH_HOSTS];
  return String(value)
    .split(",")
    .map((h) => h.trim().toLowerCase())
    .filter((h) => h !== "" && h !== "none");
}

/** The canonical https://host/topic, or null if the address is not acceptable. */
export function parsePushUrl(value, allowedHosts) {
  if (typeof value !== "string" || value.length === 0 || value.length > PUSH_URL_MAX) return null;
  if (!RE_VISIBLE_ASCII.test(value)) return null;
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  if (url.username !== "" || url.password !== "" || url.port !== "" || url.search !== "" || url.hash !== "") return null;
  if (!RE_PUSH_PATH.test(url.pathname)) return null;
  if (!allowedHosts.includes(url.hostname)) return null;
  return `https://${url.hostname}${url.pathname}`;
}

/** Sends the notice. Never throws, never follows a redirect, gives up after five seconds. */
export async function sendPush(url) {
  try {
    const res = await fetch(url, {
      method: "POST",
      body: "New message",
      headers: { Title: "TenChar", Tags: "lock" },
      redirect: "manual",
      signal: AbortSignal.timeout(5000),
    });
    await res.body?.cancel();
  } catch {
    // A push service that is down must never affect message delivery.
  }
}