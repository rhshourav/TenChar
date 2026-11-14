// Runs a relay behind TLS so the real Kotlin client (which refuses plain http) can talk to it.
// usage: node tls_relay.mjs <path-to-relay-dir> <port>
import { createServer } from "node:https";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
const [dir, port] = [process.argv[2], Number(process.argv[3])];
const { createRelay } = await import(pathToFileURL(`${dir}/src/node.js`).href);
const relay = createRelay({});
await relay.start(0, "127.0.0.1");
const listener = relay.server.listeners("request")[0];
createServer({ key: readFileSync("key.pem"), cert: readFileSync("cert.pem") }, listener).listen(port, "127.0.0.1", () => console.log("listening", port));
