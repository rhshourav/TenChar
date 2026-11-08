// Values shared with the Android app. test/constants.test.js compares them with
// app/src/main/java/com/rhshourav/tenchar/Constants.kt.

export const PAIRING_WINDOW_HOURS = 24;
export const RETENTION_DAYS = 7;
export const MAX_BODY_BYTES = 512;

// [version 1][ratchet pub 32][prevChainLen 4][n 4] + nonce 12 + frame 66 + tag 16
export const HEADER_BYTES = 41;
export const NONCE_BYTES = 12;
export const FRAME_BYTES = 66;
export const TAG_BYTES = 16;
export const ENVELOPE_BYTES = HEADER_BYTES + NONCE_BYTES + FRAME_BYTES + TAG_BYTES; // 135

// Relay-only choices (not in the Android constants).
export const MAX_QUEUE = 1000; // waiting messages per recipient
export const PAGE_SIZE = 100; // messages per GET; must stay <= 128 (Durable Object delete limit)
export const TOUCH_INTERVAL_MS = 60 * 60 * 1000; // write lastActive at most hourly

export const PAIRING_WINDOW_MS = PAIRING_WINDOW_HOURS * 60 * 60 * 1000;
export const RETENTION_MS = RETENTION_DAYS * 24 * 60 * 60 * 1000;

export const COMMIT_PREFIX = "tenchar-commit-v1";

// Optional push: a wake-up notice to an address the recipient registered.
export const PUSH_MIN_INTERVAL_MS = 10 * 1000; // at most one notice per recipient in this time
export const PUSH_URL_MAX = 128;
export const DEFAULT_PUSH_HOSTS = ["ntfy.sh"]; // override with PUSH_HOSTS; "none" turns push off

// Base64 of ENVELOPE_BYTES with no padding. Requires a multiple of 3.
if (ENVELOPE_BYTES % 3 !== 0) throw new Error("ENVELOPE_BYTES must be a multiple of 3");
export const ENVELOPE_B64_CHARS = (ENVELOPE_BYTES / 3) * 4; // 180