// Host identity via GET /dsh-go/v1/contributions against a real disposable `dsh web`:
// the same profile booted twice reports different hostInstanceId values and an old
// If-None-Match returns 200 with a new ETag; within one boot a matching If-None-Match
// is a 304. Token stays in this process's memory; the Host is always stopped in finally.
//
//   node runtime/tests/host-identity-live.mjs <dsh-prefix> [--port N]
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const PREFIX = process.argv[2];
if (!PREFIX) { console.error("usage: host-identity-live.mjs <dsh-prefix> [--port N]"); process.exit(2); }
const PREFERRED_PORT = process.argv.includes("--port") ? Number(process.argv[process.argv.indexOf("--port") + 1]) : 38233;
const DSH = new URL(`file://${join(PREFIX, "node_modules/@deepseek-ai/dsh/lib/bin.js")}`).pathname;
const BRIDGE = new URL("../bridge/dsh-go-bridge/lib/index.js", import.meta.url).pathname;
const CAPS = "v=1&app=0.10.0&locale=zh-CN";

const tmp = mkdtempSync(join(tmpdir(), "dsh-host-identity-"));
mkdirSync(join(tmp, "home", ".dsh"), { recursive: true });
mkdirSync(join(tmp, "work"), { recursive: true });
writeFileSync(join(tmp, "bridge.patch.yml"), `- insert:\n  - id: dsh-go-bridge\n    name: ${BRIDGE}\n`);
const failures = [];
const check = (ok, msg) => { console.log(`  ${ok ? "PASS" : "FAIL"} ${msg}`); if (!ok) failures.push(msg); };

const choosePort = async (preferred) => new Promise((resolve, reject) => {
  const listen = (port) => {
    const server = createServer();
    server.once("error", (error) => {
    if (error.code !== "EADDRINUSE") reject(error);
      else if (port !== preferred) reject(error);
      else listen(0);
    });
    server.listen({ host: "127.0.0.1", port, exclusive: true }, () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  };
  listen(preferred);
});

const sanitize = (text) => text.replace(/token=[A-Za-z0-9_-]+/g, "token=<redacted>");
const firstErrors = (output) => output
  .split(/\r?\n/)
  .filter((line) => line.trim())
  .slice(0, 8)
  .map(sanitize)
  .join("\n");

async function boot() {
  const port = await choosePort(PREFERRED_PORT);
  if (port !== PREFERRED_PORT) console.log(`  preferred port ${PREFERRED_PORT} occupied, using ${port}`);
  let child;
  let output = "";
  let outputError;
  const exited = new Promise((resolve) => {
    child = spawn(process.execPath, [DSH, "web", "--patch", join(tmp, "bridge.patch.yml"), "--host", "127.0.0.1", "--port", String(port), "--no-open"], {
      cwd: join(tmp, "work"),
      env: {
        PATH: `/home/rakan/dev/node/bin:/home/rakan/projects/dsh-Su-worktrees/root-candidate70/runtime/.work/x86_64/pnpm-prefix/node_modules/.bin:/usr/bin:/bin`,
        HOME: join(tmp, "home"),
        DSH_HOME: join(tmp, "home", ".dsh"),
        LANG: "C.UTF-8",
        UV_USE_IO_URING: "0",
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    const capture = (chunk) => { output += chunk.toString(); };
    child.stdout.on("data", capture);
    child.stderr.on("data", capture);
    child.once("error", (error) => { outputError = error; });
    child.once("exit", (code, signal) => resolve({ code, signal }));
  });
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("boot timeout")), 120_000);
    const tokenMatch = () => output.match(/dsh web: http:\/\/127\.0\.0\.1:\d+\/\?token=([A-Za-z0-9_-]+)/)?.[1];
    const checkReady = async () => {
      try {
        const token = tokenMatch();
        if (!token) return;
        const response = await fetch(`http://127.0.0.1:${port}/`);
        await response.arrayBuffer();
        clearTimeout(timer);
        resolve(token);
      } catch {}
    };
    const interval = setInterval(checkReady, 250);
    exited.then(() => {
      clearInterval(interval);
      clearTimeout(timer);
      reject(new Error(`dsh exited before ready${outputError ? `: ${outputError}` : ""}\n${firstErrors(output)}`));
    });
  });
  try {
    const token = await ready;
    return { port, token, stop: async () => { if (child.exitCode === null) child.kill("SIGTERM"); return exited; } };
  } catch (error) {
    if (child.exitCode === null) child.kill("SIGTERM");
    await exited;
    throw error;
  }
}

process.on("unhandledRejection", (error) => {
  failures.push(`unhandledRejection: ${error}`);
});

const contributions = async (host, headers = {}) => {
  const login = await fetch(`http://127.0.0.1:${host.port}/?token=${encodeURIComponent(host.token)}`, { redirect: "manual" });
  if (![302, 303].includes(login.status)) throw new Error(`login -> ${login.status}`);
  const cookie = login.headers.get("set-cookie")?.split(";")[0];
  if (!cookie) throw new Error("no cookie from login");
  const response = await fetch(`http://127.0.0.1:${host.port}/dsh-go/v1/contributions?${CAPS}`, { headers: { cookie, ...headers } });
  return { status: response.status, etag: response.headers.get("etag"), body: response.status === 304 ? null : await response.json() };
};

let host;
try {
  host = await boot();
  const first = await contributions(host);
  check(first.status === 200 && typeof first.body?.hostInstanceId === "string" && first.body.hostInstanceId.length >= 32, `boot 1 -> ${first.status}, hostInstanceId ${first.body?.hostInstanceId}`);
  const within = await contributions(host, { "if-none-match": first.etag });
  check(within.status === 304, `same boot, same ETag -> 304 (got ${within.status})`);
  await host.stop();

  host = await boot();
  const second = await contributions(host);
  check(second.status === 200, `boot 2 with old If-None-Match -> ${second.status}`);
  check(second.body?.hostInstanceId !== first.body?.hostInstanceId, `boot 2 hostInstanceId ${second.body?.hostInstanceId} differs from boot 1`);
  check(second.etag !== first.etag, `boot 2 ETag ${second.etag} differs from boot 1 ${first.etag}`);
} catch (error) {
  failures.push(String(error?.cause ?? error));
} finally {
  await host?.stop();
  rmSync(tmp, { recursive: true, force: true });
}
for (const failure of failures) console.error(`FAIL ${failure}`);
if (failures.length) process.exit(1);
