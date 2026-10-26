import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { test } from "node:test";
import {
  ENVELOPE_B64_CHARS,
  ENVELOPE_BYTES,
  FRAME_BYTES,
  MAX_BODY_BYTES,
  PAIRING_WINDOW_HOURS,
  PAGE_SIZE,
  RETENTION_DAYS,
} from "../src/constants.js";

const kotlin = new URL("../../app/src/main/java/com/rhshourav/tenchar/Constants.kt", import.meta.url);

test("relay constants equal the Android constants", { skip: !existsSync(kotlin) && "Constants.kt not found" }, () => {
  const source = readFileSync(kotlin, "utf8");
  const value = (name) => {
    const m = new RegExp(`const val ${name} = ([0-9_]+)`).exec(source);
    assert.ok(m, `${name} not found in Constants.kt`);
    return Number(m[1].replaceAll("_", ""));
  };
  assert.equal(PAIRING_WINDOW_HOURS, value("PAIRING_WINDOW_HOURS"));
  assert.equal(RETENTION_DAYS, value("RETENTION_DAYS"));
  assert.equal(MAX_BODY_BYTES, value("MAX_BODY_BYTES"));
  // FRAME_BYTES = 2 + MAX_UTF8_BYTES in Kotlin.
  assert.equal(FRAME_BYTES, 2 + value("MAX_UTF8_BYTES"));
});

test("envelope sizes", () => {
  assert.equal(ENVELOPE_BYTES, 135);
  assert.equal(ENVELOPE_B64_CHARS, 180);
  assert.equal(Buffer.alloc(ENVELOPE_BYTES).toString("base64").length, ENVELOPE_B64_CHARS);
  // A request carrying one envelope fits the body cap.
  assert.ok(JSON.stringify({ env: "A".repeat(ENVELOPE_B64_CHARS) }).length < MAX_BODY_BYTES);
  // Durable Objects delete at most 128 keys per call.
  assert.ok(PAGE_SIZE <= 128);
});
