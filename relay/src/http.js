// Helpers for Web-standard Request/Response (Cloudflare Workers).

const BASE_HEADERS = { "cache-control": "no-store", "x-content-type-options": "nosniff" };

export function jsonResponse(status, body) {
  if (status === 204 || body === null) return new Response(null, { status, headers: BASE_HEADERS });
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...BASE_HEADERS, "content-type": "application/json" },
  });
}

/**
 * Reads the body, giving up as soon as it exceeds max bytes.
 * Returns {text}, {tooLarge: true} or {invalid: true} (not UTF-8).
 */
export async function readBodyCapped(request, max) {
  const declared = request.headers.get("content-length");
  if (declared !== null && Number(declared) > max) return { tooLarge: true };
  if (!request.body) return { text: "" };

  const reader = request.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > max) {
      await reader.cancel();
      return { tooLarge: true };
    }
    chunks.push(value);
  }
  const all = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    all.set(chunk, offset);
    offset += chunk.byteLength;
  }
  try {
    return { text: new TextDecoder("utf-8", { fatal: true }).decode(all) };
  } catch {
    return { invalid: true };
  }
}
